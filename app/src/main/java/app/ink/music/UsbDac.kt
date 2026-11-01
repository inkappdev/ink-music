package app.ink.music

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** The native stream to a DAC's endpoint (usbaudio.c): opened on Android's descriptor for the device, fed by the player. */
object UsbStream {
    init { System.loadLibrary("inkusb") }
    @JvmStatic external fun open(fd: Int, ep: Int, maxPacket: Int, interval: Int, fbEp: Int, fbMax: Int): Long
    @JvmStatic external fun speed(h: Long): Int
    @JvmStatic external fun start(h: Long, rate: Int, channels: Int, inFloat: Boolean, sub: Int, bits: Int, ringMs: Int): Boolean
    @JvmStatic external fun write(h: Long, buf: java.nio.ByteBuffer, off: Int, len: Int): Int
    @JvmStatic external fun play(h: Long, on: Boolean)
    @JvmStatic external fun flush(h: Long)
    @JvmStatic external fun played(h: Long): Long
    @JvmStatic external fun buffered(h: Long): Int
    @JvmStatic external fun underruns(h: Long): Int
    @JvmStatic external fun state(h: Long): Int
    @JvmStatic external fun clock(h: Long): Double
    @JvmStatic external fun gain(h: Long, g: Float)
    @JvmStatic external fun eq(h: Long, db: FloatArray?, hz: IntArray?)
    @JvmStatic external fun stop(h: Long)
    @JvmStatic external fun close(h: Long)
    @JvmStatic external fun reattach(fd: Int, iface: Int)
    @JvmStatic external fun reset(fd: Int): Boolean
}

/**
 * ink music's own way to a USB DAC (settings, sound, exact to a usb dac; Bronson, 2026-11-01, "a killer feature"):
 * the DAC claimed from Android and played to directly, the song's own samples at its own rate, on any phone. Android's
 * bit-perfect path is the maker's to offer (Samsung's S26 offers a FiiO KA11 none, and lists no 44.1 kHz for it), so
 * this is UAPP's way: the DAC's descriptors read here (USB Audio Class 1 and 2), its clock set to the song's rate,
 * its volume its own (the samples untouched), the samples sent by usbaudio.c. While it's held Android can't use it
 * (its sounds go to the phone's speaker); let go, Android's driver has it back.
 */
object UsbDac {
    /** A way the DAC takes a stream: an alternate setting of its streaming interface. */
    data class Alt(
        val iface: Int, val alt: Int, val channels: Int, val sub: Int, val bits: Int,
        val ep: Int, val maxPacket: Int, val interval: Int, val fbEp: Int, val fbMax: Int, val terminal: Int, val rates: List<Int>,
    )

    /** What the DAC is, read from its descriptors. */
    data class Shape(
        val uac2: Boolean, val control: Int, val alts: List<Alt>,
        val clocks: Map<Int, Int>,          // a terminal's clock (UAC2: input terminal id to clock source id)
        val volumeUnit: Int,                // the feature unit with a volume control (0: none)
        val volumeMaster: Boolean,          // on the master channel (else on each)
        val high: Boolean,                  // USB 2 (high speed, on a phone's port): packets each microframe
    )

    fun dac(m: UsbManager): UsbDevice? = m.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { i -> d.getInterface(i).let { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 && it.endpointCount > 0 } }
    }

    /** The DAC's descriptors read: its streaming settings, clocks and volume. */
    fun shape(raw: ByteArray): Shape {
        var i = 0
        var iface = -1; var alt = 0; var cls = 0; var sub = 0
        var uac2 = false; var control = -1; var high = false
        val clocks = HashMap<Int, Int>()
        var volumeUnit = 0; var master = true
        val alts = ArrayList<Alt>()
        var channels = 0; var subslot = 0; var bits = 0; var terminal = 0; var pcm = true
        var rates = emptyList<Int>()
        var ep = 0; var maxPacket = 0; var interval = 0; var fbEp = 0; var fbMax = 0
        fun u8(k: Int) = raw[k].toInt() and 0xff
        fun u16(k: Int) = u8(k) or (u8(k + 1) shl 8)
        fun u32(k: Int) = u16(k) or (u16(k + 2) shl 16)
        fun close() {
            if (cls == 1 && sub == 2 && alt > 0 && ep != 0 && pcm && bits > 0)
                alts += Alt(iface, alt, channels, subslot, bits, ep, maxPacket, interval, fbEp, fbMax, terminal, rates)
        }
        while (i + 1 < raw.size) {
            val len = u8(i); val type = u8(i + 1)
            if (len < 2 || i + len > raw.size) break
            when (type) {
                1 -> high = u16(i + 2) >= 0x0200   // the device: USB 2 or later
                4 -> { // interface
                    close()
                    iface = u8(i + 2); alt = u8(i + 3); cls = u8(i + 5); sub = u8(i + 6)
                    channels = 0; subslot = 0; bits = 0; terminal = 0; pcm = true; rates = emptyList()
                    ep = 0; maxPacket = 0; interval = 0; fbEp = 0; fbMax = 0
                    if (cls == 1 && sub == 1) control = iface
                }
                0x24 -> if (cls == 1 && sub == 1) when (u8(i + 2)) { // audio control
                    1 -> uac2 = u16(i + 3) >= 0x0200
                    2 -> if (uac2 && len >= 8) clocks[u8(i + 3)] = u8(i + 7)            // input terminal: its clock
                    6 -> if (volumeUnit == 0) {                                            // feature unit
                        if (uac2) {
                            val masterVol = (u32(i + 5) shr 2) and 3
                            val ch1Vol = if (len >= 13) (u32(i + 9) shr 2) and 3 else 0
                            if (masterVol == 3) { volumeUnit = u8(i + 3); master = true }
                            else if (ch1Vol == 3) { volumeUnit = u8(i + 3); master = false }
                        } else {
                            val size = u8(i + 5)
                            val m0 = if (size > 0) u8(i + 6) else 0
                            val m1 = if (size > 0 && len >= 7 + 2 * size) u8(i + 6 + size) else 0
                            if (m0 and 2 != 0) { volumeUnit = u8(i + 3); master = true }
                            else if (m1 and 2 != 0) { volumeUnit = u8(i + 3); master = false }
                        }
                    }
                } else if (cls == 1 && sub == 2) when (u8(i + 2)) { // audio streaming
                    1 -> if (uac2) { terminal = u8(i + 3); pcm = u32(i + 6) and 1 != 0; channels = u8(i + 10) }
                        else { terminal = u8(i + 3); pcm = u16(i + 5) == 1 }
                    2 -> if (u8(i + 3) == 1) {
                        if (uac2) { subslot = u8(i + 4); bits = u8(i + 5) }
                        else {
                            channels = u8(i + 4); subslot = u8(i + 5); bits = u8(i + 6)
                            val n = u8(i + 7)
                            rates = if (n == 0) listOf(u8(i + 8) or (u8(i + 9) shl 8) or (u8(i + 10) shl 16), u8(i + 11) or (u8(i + 12) shl 8) or (u8(i + 13) shl 16))
                                else (0 until n).map { k -> val o = i + 8 + 3 * k; u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) }
                        }
                    } else pcm = false
                }
                5 -> if (cls == 1 && sub == 2) { // endpoint
                    val addr = u8(i + 2); val attr = u8(i + 3)
                    val size = u16(i + 4).let { (it and 0x7ff) * (1 + ((it shr 11) and 3)) }
                    if (attr and 3 == 1) {
                        if (addr and 0x80 == 0) { ep = addr; maxPacket = size; interval = u8(i + 6) }
                        else if ((attr shr 4) and 3 == 1) { fbEp = addr; fbMax = size }
                    }
                }
            }
            i += len
        }
        close()
        return Shape(uac2, control, alts, clocks, volumeUnit, master, high)
    }

    // ---- the DAC held ----

    class Held(val device: UsbDevice, val connection: UsbDeviceConnection, val shape: Shape, private val claimed: List<UsbInterface>) {
        private var current: Stream? = null
        private var streamAlt: Alt? = null
        private var rate = 0
        /** The DAC as kept between plug-ins: vendor and product. */
        val key get() = "%04x:%04x".format(device.vendorId, device.productId)

        /**
         * One stream to the DAC (a song's rate and setting). Ended when the next begins, the DAC goes or exact's turned
         * off; after that nothing reaches the native side (its memory's gone), and a write says so (the player then
         * asks again where the song goes).
         */
        inner class Stream(private var h: Long, val rate: Int, val alt: Alt) {
            private var lastPlayed = 0L
            val live get() = synchronized(this@Held) { h != 0L }
            fun write(buf: java.nio.ByteBuffer, off: Int, len: Int): Int = synchronized(this@Held) { if (h == 0L) -1 else UsbStream.write(h, buf, off, len) }
            fun play(on: Boolean) = synchronized(this@Held) { if (h != 0L) UsbStream.play(h, on) }
            fun flush() = synchronized(this@Held) { if (h != 0L) { UsbStream.flush(h); lastPlayed = 0 } }
            fun played(): Long = synchronized(this@Held) { if (h != 0L) UsbStream.played(h).also { lastPlayed = it } else lastPlayed }
            fun gain(g: Float) = synchronized(this@Held) { if (h != 0L) UsbStream.gain(h, g) }
            /** The equalizer on the way (dB a band at [Eq.TEN]), or none. */
            fun eq(db: FloatArray?) = synchronized(this@Held) { if (h != 0L) UsbStream.eq(h, db, if (db != null) Eq.TEN.toIntArray() else null) }
            fun underruns(): Int = synchronized(this@Held) { if (h != 0L) UsbStream.underruns(h) else 0 }
            fun clock(): Double = synchronized(this@Held) { if (h != 0L) UsbStream.clock(h) else 0.0 }
            /** This stream over (only if it's still the DAC's: the next song's may have begun already). */
            fun end() = synchronized(this@Held) { if (current === this) this@Held.end() }
            internal fun close() { if (h != 0L) { UsbStream.close(h); h = 0 } }
        }

        /** What's streaming now, for the signal path. */
        val now: Stream? get() = synchronized(this) { current }
        val name: String get() = device.productName?.trim().orEmpty().ifEmpty { "usb dac" }

        /** The rates the DAC's clock takes (UAC2: asked; UAC1: from the descriptors). */
        val rates: List<Int> by lazy {
            if (!shape.uac2) shape.alts.flatMap { it.rates }.distinct().sorted()
            else shape.alts.firstOrNull()?.let { a -> shape.clocks[a.terminal]?.let { clockRates(it) } }.orEmpty()
        }

        private fun clockRates(clock: Int): List<Int> {
            val index = (clock shl 8) or shape.control
            val head = ByteArray(2)
            if (connection.controlTransfer(0xA1, 0x02, 0x0100, index, head, 2, 1000) < 2) return emptyList()
            val n = (head[0].toInt() and 0xff) or ((head[1].toInt() and 0xff) shl 8)
            val all = ByteArray(2 + 12 * n)
            if (connection.controlTransfer(0xA1, 0x02, 0x0100, index, all, all.size, 1000) < all.size) return emptyList()
            fun u32(k: Int) = (0..3).fold(0) { acc, b -> acc or ((all[k + b].toInt() and 0xff) shl (8 * b)) }
            val out = ArrayList<Int>()
            for (r in 0 until n) {
                val o = 2 + 12 * r; val min = u32(o); val max = u32(o + 4); val res = u32(o + 8)
                if (res == 0 || min == max) out += min
                else for (f in listOf(44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000)) if (f in min..max && (f - min) % res == 0) out += f
            }
            return out.distinct().sorted()
        }

        /** The setting for a stream: its channels, the most bits it has, room enough for the rate. */
        fun altFor(rate: Int, channels: Int): Alt? {
            if (rate !in rates) return null
            return shape.alts.filter { it.channels == channels && (it.rates.isEmpty() || rate in it.rates) }
                .filter { a -> perPacket(a, rate) * a.sub * a.channels <= a.maxPacket }
                .maxByOrNull { it.bits }
        }

        // the most frames a packet carries at this rate (one more than its share, for the DAC's clock running fast)
        private fun perPacket(a: Alt, rate: Int): Int {
            val perSecond = if (shape.high) 8000 / (1 shl (a.interval.coerceAtLeast(1) - 1)) else 1000
            return rate / perSecond + 2
        }

        /** A stream at `rate` started: the clock set, the setting chosen, the native stream running (paused). */
        @Synchronized
        fun begin(rate: Int, channels: Int, inFloat: Boolean): Stream? {
            val a = altFor(rate, channels) ?: return null
            end()
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == a.iface && it.alternateSetting == a.alt } ?: return null
            val zero = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == a.iface && it.alternateSetting == 0 }
            zero?.let { connection.setInterface(it) }
            if (this.rate != rate || !shape.uac2) setRate(a, rate)
            if (!connection.setInterface(intf)) { android.util.Log.i("ink-usb", "setting ${a.iface}/${a.alt} refused"); return null }
            if (!shape.uac2) setRate(a, rate)
            val h = UsbStream.open(connection.fileDescriptor, a.ep, a.maxPacket, a.interval, a.fbEp, a.fbMax)
            if (!UsbStream.start(h, rate, channels, inFloat, a.sub, a.bits, 250)) { UsbStream.close(h); return null }
            streamAlt = a
            this.rate = rate
            return Stream(h, rate, a).also { current = it; it.eq(UsbDac.eq) }
        }

        private fun setRate(a: Alt, rate: Int) {
            val ok = if (shape.uac2) {
                val clock = shape.clocks[a.terminal] ?: return
                val b = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(), (rate shr 24).toByte())
                connection.controlTransfer(0x21, 0x01, 0x0100, (clock shl 8) or shape.control, b, 4, 1000) >= 0
            } else {
                val b = byteArrayOf(rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte())
                connection.controlTransfer(0x22, 0x01, 0x0100, a.ep, b, 3, 1000) >= 0
            }
            android.util.Log.i("ink-usb", "rate $rate: ${if (ok) "set" else "refused"}")
        }

        /** The DAC's own volume, in dB (its range: [volumeRange]); the samples untouched. */
        fun volume(db: Float): Boolean {
            val unit = shape.volumeUnit.takeIf { it != 0 } ?: return false
            val v = Math.round(db * 256).coerceIn(-32767, 32767)
            val b = byteArrayOf(v.toByte(), (v shr 8).toByte())
            val channels = if (shape.volumeMaster) listOf(0) else listOf(1, 2)
            return channels.all { ch -> connection.controlTransfer(0x21, 0x01, (0x02 shl 8) or ch, (unit shl 8) or shape.control, b, 2, 1000) >= 0 }
        }

        /** The DAC's volume range in dB (min to max), or null: it has none of its own. */
        val volumeRange: ClosedFloatingPointRange<Float>? by lazy {
            val unit = shape.volumeUnit.takeIf { it != 0 } ?: return@lazy null
            val ch = if (shape.volumeMaster) 0 else 1
            fun s16(b: ByteArray, k: Int) = ((b[k].toInt() and 0xff) or (b[k + 1].toInt() shl 8)).toShort().toInt() / 256f
            if (shape.uac2) {
                val b = ByteArray(8)
                if (connection.controlTransfer(0xA1, 0x02, (0x02 shl 8) or ch, (unit shl 8) or shape.control, b, 8, 1000) < 8) null
                else s16(b, 2)..s16(b, 4)
            } else {
                val lo = ByteArray(2); val hi = ByteArray(2)
                if (connection.controlTransfer(0xA1, 0x82, (0x02 shl 8) or ch, (unit shl 8) or shape.control, lo, 2, 1000) < 2 ||
                    connection.controlTransfer(0xA1, 0x83, (0x02 shl 8) or ch, (unit shl 8) or shape.control, hi, 2, 1000) < 2) null
                else s16(lo, 0)..s16(hi, 0)
            }
        }

        /** Samples scaled by this when the DAC has no volume of its own (1: untouched). */
        @Volatile var digital = 1f
            private set

        /**
         * How loud, 0 to 1: the DAC's own volume where it has one (the samples untouched), else the samples scaled.
         * 1 is the DAC's full level, each step down a little more (50 dB across the whole way).
         */
        fun level(f: Float) {
            val db = if (f <= 0f) -120f else (f - 1f) * 50f
            val r = volumeRange
            if (r != null) { volume(db.coerceIn(r.start, r.endInclusive)); digital = 1f }
            else digital = if (f <= 0f) 0f else Math.pow(10.0, db / 20.0).toFloat()
            android.util.Log.i("ink-usb", "level $f: ${if (r != null) "the dac's own, %.1f db".format(db.coerceIn(r.start, r.endInclusive)) else "scaled %.3f".format(digital)}")
        }

        /** The stream stopped (the DAC still held). */
        @Synchronized
        fun end() {
            current?.close(); current = null
            streamAlt?.let { a -> (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.id == a.iface && it.alternateSetting == 0 }?.let { connection.setInterface(it) } }
            streamAlt = null
        }

        /** Let go: Android's driver given the DAC back. */
        @Synchronized
        fun release(reset: Boolean) {
            end()
            val fd = connection.fileDescriptor
            claimed.forEach { connection.releaseInterface(it) }
            claimed.map { it.id }.distinct().forEach { UsbStream.reattach(fd, it) }
            // and reset, so it comes to Android anew (given back alone, Android's driver never took it again); only
            // when exact's turned off: else the DAC, come anew, would be taken again at once
            if (reset) UsbStream.reset(fd)
            connection.close()
        }
    }

    @Volatile var held: Held? = null
        private set

    /**
     * The equalizer on the way to the DAC (dB a band), or null: off, or flat. Exact with your equalizer: worked out
     * in 64 bit and sent on at the song's own rate, no mixer, no resampling; not every sample as it was any more,
     * and the signal path says so.
     */
    @Volatile var eq: FloatArray? = null
        set(v) { field = v; held?.now?.eq(v) }

    /** How loud the DAC is, 0 to 1 (its own volume): the volume buttons' while it's held. */
    @Volatile var level = .3f
        private set
    /** Told when a DAC is held or let go (the buttons become its, or Android's again). */
    @Volatile var onChange: (() -> Unit)? = null

    /** The volume buttons' turn: the DAC's own volume, the samples untouched; kept for this DAC. */
    fun turn(v: Float) {
        level = v.coerceIn(0f, 1f)
        val h = held ?: return
        h.level(level)
        levels?.let { f -> runCatching { val o = runCatching { org.json.JSONObject(f.readText()) }.getOrDefault(org.json.JSONObject()); f.writeText(o.put(h.key, level.toDouble()).toString()) } }
    }
    // each DAC's own level, kept (the player's files)
    private var levels: java.io.File? = null

    /** The DAC claimed from Android, if there is one and ink music may (Android's permission asked for on the screens). */
    @Synchronized
    fun hold(c: Context): Held? {
        held?.let { h -> if (dac(c.getSystemService(UsbManager::class.java) ?: return null)?.deviceName == h.device.deviceName) return h; release() }
        val m = c.getSystemService(UsbManager::class.java) ?: return null
        val d = dac(m) ?: return null
        if (!m.hasPermission(d)) { android.util.Log.i("ink-usb", "${d.productName}: no permission yet"); return null }
        // as loud as Android had it: its media volume for the DAC, read before the DAC's taken from it
        val am = c.getSystemService(android.media.AudioManager::class.java)
        val was = am?.let { it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) } ?: .3f
        val conn = m.openDevice(d) ?: return null
        val shape = shape(conn.rawDescriptors ?: run { conn.close(); return null })
        val audio = (0 until d.interfaceCount).map { d.getInterface(it) }.filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.alternateSetting == 0 }
            .filter { it.interfaceSubclass == 1 || shape.alts.any { a -> a.iface == it.id } }
        val claimed = audio.filter { conn.claimInterface(it, true) }
        if (claimed.size < audio.size || shape.alts.isEmpty()) { claimed.forEach { conn.releaseInterface(it) }; conn.close(); android.util.Log.i("ink-usb", "${d.productName}: not claimed"); return null }
        val h = Held(d, conn, shape, claimed)
        android.util.Log.i("ink-usb", "${h.name}: uac${if (shape.uac2) 2 else 1}, rates ${h.rates}, settings " +
            shape.alts.joinToString { "${it.iface}/${it.alt} ${it.channels}ch ${it.bits}/${it.sub * 8} ep ${"%02x".format(it.ep)} ${it.maxPacket} fb ${"%02x".format(it.fbEp)}" } +
            ", volume ${h.volumeRange ?: "none"}")
        // the buttons' steps (20): this DAC's own level as it was left, else the level Android had, on a step
        levels = java.io.File(c.filesDir, "usb-levels.json")
        val kept = runCatching { org.json.JSONObject(levels!!.readText()).optDouble(h.key, Double.NaN) }.getOrDefault(Double.NaN)
        level = if (!kept.isNaN()) kept.toFloat() else Math.round(was * PlayerService.STEPS) / PlayerService.STEPS.toFloat()
        h.level(level)
        held = h
        onChange?.invoke()
        return h
    }

    @Synchronized
    fun release(reset: Boolean = false) { val was = held ?: return; was.release(reset); held = null; onChange?.invoke() }

    /** Android's word on ink music using the DAC (broadcast to the player, which looks again). */
    const val ALLOWED = "app.ink.music.USB_ALLOWED"

    /** On the screens: Android asked to let ink music use the DAC, if there is one and it hasn't yet. */
    fun ask(c: Context) {
        val m = c.getSystemService(UsbManager::class.java) ?: return
        val d = dac(m) ?: return
        if (m.hasPermission(d)) return
        val pi = android.app.PendingIntent.getBroadcast(c, 0, android.content.Intent(ALLOWED).setPackage(c.packageName),
            android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        m.requestPermission(d, pi)
    }
}
