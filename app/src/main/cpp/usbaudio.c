// ink music's own way to a USB DAC: the song's samples straight to the DAC's streaming endpoint, past Android's
// audio (its mixer resamples to 48 kHz and works the volume in; Android's own bit-perfect path is the phone maker's
// to offer, and Samsung's S26 offers none). The DAC is claimed from Android (UsbDac.kt), and here its
// isochronous endpoint is fed through Linux's usbfs on the descriptor Android hands over: transfers kept in flight,
// each filled from a ring the player writes into, sized by the DAC's own clock (its feedback endpoint) where it's
// asynchronous. A sample reaches the DAC as the same number it was in the file: 16 bit and 24 bit samples, given as
// float or 16 bit, are scaled back exactly into the DAC's slot (gain 1). Bronson, 2026-11-01: "a killer feature".

#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <math.h>
#include <unistd.h>
#include <time.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ink-usb", __VA_ARGS__)

#define URBS 16
#define MAX_PACKETS 32

struct urb_ctx { int frames; unsigned gen; };

// the equalizer on the way to the DAC (exact with your equalizer): peaking filters, one a band, in 64 bit
#define BANDS 10
#define CHANNELS 8
typedef struct { double b0, b1, b2, a1, a2; } biquad;
typedef struct { double x1, x2, y1, y2; } biquad_state;

typedef struct {
    int fd;
    unsigned char ep, fb_ep;
    int max_packet, fb_max;
    int period;            // (micro)frames between packets
    int per_second;        // packets a second
    int high_speed;
    int packets;           // packets a transfer

    int rate, channels, in_float, in_frame, sub, bits, out_frame;

    pthread_mutex_t lock;
    unsigned char *ring;
    size_t ring_size, head, used;   // bytes, under lock

    atomic_int playing, running;
    atomic_llong played;            // frames of the song sent out (completed transfers)
    atomic_uint gen;                // a flush's count: transfers filled before it don't count
    atomic_int underruns;
    int starved;                    // the ring ran dry (the stream's thread only)
    int primed;                     // the song's first samples have gone (before them, no gap counts)
    atomic_uint fb;                 // samples a packet, 16.16 (0: none yet)
    atomic_int gain_bits;           // float gain as bits (1.0: untouched)
    atomic_int stage, reaps;        // where the stream's thread is, and how many have come back (for the log)
    double acc;

    int eq_n;                       // bands in use (0: untouched), under lock
    biquad eq[BANDS];
    biquad_state eqs[BANDS][CHANNELS];
    double pre;                     // headroom for the boosts, so nothing clips

    pthread_t thread;
    struct usbdevfs_urb *urbs[URBS];
    struct urb_ctx ctx[URBS];
    struct usbdevfs_urb *fb_urb;
} dac_t;

static float gain_of(dac_t *d) { int b = atomic_load(&d->gain_bits); float g; memcpy(&g, &b, 4); return g; }

// one sample to the DAC's slot: the value scaled to its bits, MSB-justified in the slot (UAC's Type I), little-endian
static inline void put(unsigned char *o, int sub, int bits, double v) {
    double full = ldexp(1.0, bits - 1);
    double s = nearbyint(v * full);
    if (s > full - 1) s = full - 1;
    if (s < -full) s = -full;
    long long x = (long long) s << (sub * 8 - bits);
    for (int i = 0; i < sub; i++) o[i] = (unsigned char) (x >> (8 * i));
}

// n frames from the ring into out (the DAC's format); what's missing, silence. Returns the song's frames taken.
static int fill(dac_t *d, unsigned char *out, int n) {
    int got = 0;
    if (atomic_load(&d->playing)) {
        atomic_store(&d->stage, 5);
        pthread_mutex_lock(&d->lock);
        atomic_store(&d->stage, 6);
        size_t have = d->used / d->in_frame;
        got = (int) (have < (size_t) n ? have : (size_t) n);
        size_t tail = (d->head + d->ring_size - d->used) % d->ring_size;
        float g = gain_of(d);
        for (int f = 0; f < got; f++) {
            for (int c = 0; c < d->channels; c++) {
                double v;
                if (d->in_float) { float x; unsigned char b[4];
                    for (int i = 0; i < 4; i++) b[i] = d->ring[(tail + i) % d->ring_size];
                    memcpy(&x, b, 4); v = x; tail = (tail + 4) % d->ring_size;
                } else { short x = (short) (d->ring[tail] | (d->ring[(tail + 1) % d->ring_size] << 8));
                    v = x / 32768.0; tail = (tail + 2) % d->ring_size; }
                if (d->eq_n && c < CHANNELS) {
                    v *= d->pre;
                    for (int k = 0; k < d->eq_n; k++) {
                        biquad *q = &d->eq[k]; biquad_state *st = &d->eqs[k][c];
                        double y = q->b0 * v + q->b1 * st->x1 + q->b2 * st->x2 - q->a1 * st->y1 - q->a2 * st->y2;
                        st->x2 = st->x1; st->x1 = v; st->y2 = st->y1; st->y1 = y; v = y;
                    }
                }
                if (g != 1.0f) v *= g;
                put(out + (size_t) f * d->out_frame + (size_t) c * d->sub, d->sub, d->bits, v);
            }
        }
        d->used -= (size_t) got * d->in_frame;
        pthread_mutex_unlock(&d->lock);
        // a gap: the ring ran dry mid-song, then the song went on (its end, with nothing after, isn't one; nor its
        // start, played before the first of it had come: a song a moment late isn't a gap, 2026-11-01)
        if (got > 0) d->primed = 1;
        if (got < n) { if (d->primed) d->starved = 1; }
        else if (d->starved) { d->starved = 0; atomic_fetch_add(&d->underruns, 1); }
    }
    memset(out + (size_t) got * d->out_frame, 0, (size_t) (n - got) * d->out_frame);
    return got;
}

// a transfer's packets sized by the DAC's clock (feedback) or the nominal rate, filled, sent
static int submit(dac_t *d, int i) {
    struct usbdevfs_urb *u = d->urbs[i];
    unsigned fb = atomic_load(&d->fb);
    double spp = fb ? fb / 65536.0 : (double) d->rate / d->per_second;
    int total = 0, real = 0, offset = 0;
    for (int p = 0; p < d->packets; p++) {
        d->acc += spp;
        int n = (int) d->acc;
        d->acc -= n;
        int most = d->max_packet / d->out_frame;
        if (n > most) n = most;
        real += fill(d, (unsigned char *) u->buffer + offset, n);
        u->iso_frame_desc[p].length = (unsigned) (n * d->out_frame);
        u->iso_frame_desc[p].actual_length = 0;
        u->iso_frame_desc[p].status = 0;
        offset += n * d->out_frame;
        total += n;
    }
    u->buffer_length = offset;
    u->status = 0;
    u->start_frame = 0;
    u->actual_length = 0;
    u->error_count = 0;
    d->ctx[i].frames = real;
    d->ctx[i].gen = atomic_load(&d->gen);
    if (ioctl(d->fd, USBDEVFS_SUBMITURB, u) < 0) { LOG("submit: %s", strerror(errno)); return -1; }
    return 0;
}

static int submit_fb(dac_t *d) {
    struct usbdevfs_urb *u = d->fb_urb;
    u->iso_frame_desc[0].length = (unsigned) d->fb_max;
    u->iso_frame_desc[0].actual_length = 0;
    u->buffer_length = d->fb_max;
    u->status = 0;
    if (ioctl(d->fd, USBDEVFS_SUBMITURB, u) < 0) { LOG("feedback submit: %s", strerror(errno)); return -1; }
    return 0;
}

// the DAC's word on its clock: 10.14 in 3 bytes at full speed, 16.16 in 4 at high speed (per microframe); read
// against the nominal rate, as Linux does, since some DACs send it in another shape
static void feedback(dac_t *d, const unsigned char *b, int len) {
    if (len < 3) return;
    unsigned v = b[0] | (b[1] << 8) | (b[2] << 16) | (len >= 4 ? (unsigned) b[3] << 24 : 0);
    double nominal = (double) d->rate / d->per_second;     // samples a packet
    double shapes[] = { d->high_speed ? v / 65536.0 : v / 16384.0, v / 65536.0, v / 16384.0, v / 8192.0, v / 131072.0 };
    for (int i = 0; i < 5; i++) {
        double spp = shapes[i] * d->period;
        if (fabs(spp - nominal) <= nominal * .05) { atomic_store(&d->fb, (unsigned) (spp * 65536.0)); return; }
    }
    // not believable: the nominal rate stays
}

static void *run(void *arg) {
    dac_t *d = arg;
    setpriority(PRIO_PROCESS, 0, -19);   // urgent audio's priority, as Android gives its own
    for (int i = 0; i < URBS; i++) if (submit(d, i) < 0) { atomic_store(&d->running, 0); break; }
    if (d->fb_urb && atomic_load(&d->running)) submit_fb(d);
    int out = atomic_load(&d->running) ? URBS + (d->fb_urb ? 1 : 0) : 0;
    int reaped = 0, errors = 0; time_t last = 0;
    LOG("thread: %d in flight", out);
    while (out > 0) {
        struct usbdevfs_urb *u = NULL;
        atomic_store(&d->stage, 1);
        if (ioctl(d->fd, USBDEVFS_REAPURB, &u) < 0) {
            if (errno == EINTR) continue;
            LOG("reap: %s", strerror(errno));
            break;
        }
        out--;
        atomic_store(&d->stage, 2); atomic_fetch_add(&d->reaps, 1);
        if (u == d->fb_urb) {
            if (u->status == 0 && u->iso_frame_desc[0].status == 0) feedback(d, u->buffer, (int) u->iso_frame_desc[0].actual_length);
            if (atomic_load(&d->running) && submit_fb(d) == 0) out++;
            continue;
        }
        int i = (int) (intptr_t) u->usercontext;
        if (u->status != 0) errors++;
        // every ten seconds: how it goes
        struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
        reaped++;
        if (ts.tv_sec / 10 != last) {
            last = ts.tv_sec / 10;
            pthread_mutex_lock(&d->lock); size_t used = d->used; pthread_mutex_unlock(&d->lock);
            LOG("%s: %d back, played %lld, ring %zu bytes, underruns %d, errors %d, clock %.1f Hz", atomic_load(&d->playing) ? "playing" : "paused",
                reaped, (long long) atomic_load(&d->played), used, atomic_load(&d->underruns), errors, atomic_load(&d->fb) / 65536.0 * d->per_second);
        }
        if (d->ctx[i].gen == atomic_load(&d->gen)) atomic_fetch_add(&d->played, d->ctx[i].frames);
        atomic_store(&d->stage, 3);
        if (atomic_load(&d->running) && submit(d, i) == 0) out++;
        atomic_store(&d->stage, 4);
    }
    LOG("stream ended");
    return NULL;
}

static struct usbdevfs_urb *iso_urb(unsigned char ep, int packets, int size, void *ctx) {
    struct usbdevfs_urb *u = calloc(1, sizeof(*u) + (size_t) packets * sizeof(struct usbdevfs_iso_packet_desc));
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = ep;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = calloc(1, (size_t) size);
    u->buffer_length = size;
    u->number_of_packets = packets;
    u->usercontext = ctx;
    return u;
}

static dac_t *of(jlong h) { return (dac_t *) (intptr_t) h; }

JNIEXPORT jlong JNICALL Java_app_ink_music_UsbStream_open(JNIEnv *e, jclass c, jint fd, jint ep, jint maxPacket, jint interval, jint fbEp, jint fbMax) {
    dac_t *d = calloc(1, sizeof(dac_t));
    d->fd = fd; d->ep = (unsigned char) ep; d->fb_ep = (unsigned char) fbEp; d->max_packet = maxPacket; d->fb_max = fbMax;
    int speed = ioctl(fd, USBDEVFS_GET_SPEED, NULL);
    d->high_speed = speed >= USB_SPEED_HIGH;
    int iv = interval < 1 ? 1 : interval;
    d->period = d->high_speed ? 1 << (iv - 1) : 1;
    d->per_second = (d->high_speed ? 8000 : 1000) / d->period;
    // about 1 ms a transfer, 16 in flight: 16 ms between the ring and the DAC. 128 packets in flight at most: the
    // S26's controller stopped giving 16 of 16 back (256 in flight) after the 255th, for good (2026-11-01)
    d->packets = d->per_second / 1000; if (d->packets < 1) d->packets = 1; if (d->packets > MAX_PACKETS) d->packets = MAX_PACKETS;
    pthread_mutex_init(&d->lock, NULL);
    float one = 1.0f; int b; memcpy(&b, &one, 4); atomic_store(&d->gain_bits, b);
    LOG("open: speed %d, ep 0x%02x max %d every %d (%d/s, %d a transfer), feedback 0x%02x", speed, ep, maxPacket, d->period, d->per_second, d->packets, fbEp);
    return (jlong) (intptr_t) d;
}

JNIEXPORT jint JNICALL Java_app_ink_music_UsbStream_speed(JNIEnv *e, jclass c, jlong h) { return of(h)->high_speed; }

JNIEXPORT jboolean JNICALL Java_app_ink_music_UsbStream_start(JNIEnv *e, jclass c, jlong h, jint rate, jint channels, jboolean inFloat, jint sub, jint bits, jint ringMs) {
    dac_t *d = of(h);
    d->rate = rate; d->channels = channels; d->in_float = inFloat; d->in_frame = (inFloat ? 4 : 2) * channels;
    d->sub = sub; d->bits = bits; d->out_frame = sub * channels; d->acc = 0;
    d->ring_size = (size_t) rate * d->in_frame * ringMs / 1000;
    d->ring_size -= d->ring_size % d->in_frame;
    d->ring = malloc(d->ring_size); d->head = 0; d->used = 0;
    atomic_store(&d->played, 0); atomic_store(&d->fb, 0); atomic_store(&d->underruns, 0); atomic_store(&d->playing, 0);
    int size = d->max_packet * d->packets;
    for (int i = 0; i < URBS; i++) d->urbs[i] = iso_urb(d->ep, d->packets, size, (void *) (intptr_t) i);
    d->fb_urb = d->fb_ep ? iso_urb(d->fb_ep, 1, d->fb_max, NULL) : NULL;
    atomic_store(&d->running, 1);
    if (pthread_create(&d->thread, NULL, run, d) != 0) { atomic_store(&d->running, 0); return JNI_FALSE; }
    LOG("start: %d Hz, %d ch, %s in, %d bit in %d bytes out", rate, channels, inFloat ? "float" : "16 bit", bits, sub);
    return JNI_TRUE;
}

// as much of len bytes as there's room for: how many were taken
JNIEXPORT jint JNICALL Java_app_ink_music_UsbStream_write(JNIEnv *e, jclass c, jlong h, jobject buf, jint off, jint len) {
    dac_t *d = of(h);
    unsigned char *src = (*e)->GetDirectBufferAddress(e, buf);
    if (!src || !d->ring) return -1;
    src += off;
    pthread_mutex_lock(&d->lock);
    size_t room = d->ring_size - d->used;
    size_t n = (size_t) len < room ? (size_t) len : room;
    n -= n % d->in_frame;
    size_t first = d->ring_size - d->head < n ? d->ring_size - d->head : n;
    memcpy(d->ring + d->head, src, first);
    memcpy(d->ring, src + first, n - first);
    d->head = (d->head + n) % d->ring_size;
    d->used += n;
    pthread_mutex_unlock(&d->lock);
    return (jint) n;
}

JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_play(JNIEnv *e, jclass c, jlong h, jboolean on) {
    atomic_store(&of(h)->playing, on ? 1 : 0);
}

JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_flush(JNIEnv *e, jclass c, jlong h) {
    dac_t *d = of(h);
    pthread_mutex_lock(&d->lock);
    d->used = 0;
    atomic_fetch_add(&d->gen, 1);
    atomic_store(&d->played, 0);
    d->starved = 0;
    d->primed = 0;
    pthread_mutex_unlock(&d->lock);
}

JNIEXPORT jlong JNICALL Java_app_ink_music_UsbStream_played(JNIEnv *e, jclass c, jlong h) { return atomic_load(&of(h)->played); }
JNIEXPORT jint JNICALL Java_app_ink_music_UsbStream_buffered(JNIEnv *e, jclass c, jlong h) {
    dac_t *d = of(h); pthread_mutex_lock(&d->lock); int f = (int) (d->used / d->in_frame); pthread_mutex_unlock(&d->lock); return f;
}
JNIEXPORT jint JNICALL Java_app_ink_music_UsbStream_state(JNIEnv *e, jclass c, jlong h) { return atomic_load(&of(h)->stage) * 1000000 + atomic_load(&of(h)->reaps); }
JNIEXPORT jint JNICALL Java_app_ink_music_UsbStream_underruns(JNIEnv *e, jclass c, jlong h) { return atomic_load(&of(h)->underruns); }
JNIEXPORT jdouble JNICALL Java_app_ink_music_UsbStream_clock(JNIEnv *e, jclass c, jlong h) {
    dac_t *d = of(h); unsigned fb = atomic_load(&d->fb); return fb ? fb / 65536.0 * d->per_second : 0;
}

// the equalizer's bands (dB at each centre, Hz), as RBJ's peaking filters at the stream's rate, an octave wide;
// none at all for flat (the samples left as they were)
JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_eq(JNIEnv *e, jclass c, jlong h, jfloatArray dbs, jintArray hzs) {
    dac_t *d = of(h);
    pthread_mutex_lock(&d->lock);
    d->eq_n = 0;
    double most = 0;
    if (dbs) {
        int n = (*e)->GetArrayLength(e, dbs); if (n > BANDS) n = BANDS;
        jfloat *db = (*e)->GetFloatArrayElements(e, dbs, NULL);
        jint *hz = (*e)->GetIntArrayElements(e, hzs, NULL);
        for (int i = 0; i < n; i++) {
            if (fabs(db[i]) < 0.05 || hz[i] >= d->rate * 0.45) continue;
            double A = pow(10, db[i] / 40.0), w = 2 * M_PI * hz[i] / d->rate, cw = cos(w), alpha = sin(w) / (2 * 1.41);
            double a0 = 1 + alpha / A;
            biquad q = { (1 + alpha * A) / a0, -2 * cw / a0, (1 - alpha * A) / a0, -2 * cw / a0, (1 - alpha / A) / a0 };
            d->eq[d->eq_n++] = q;
            if (db[i] > most) most = db[i];
        }
        (*e)->ReleaseFloatArrayElements(e, dbs, db, JNI_ABORT);
        (*e)->ReleaseIntArrayElements(e, hzs, hz, JNI_ABORT);
    }
    memset(d->eqs, 0, sizeof(d->eqs));
    d->pre = pow(10, -most / 20.0);
    pthread_mutex_unlock(&d->lock);
    LOG("equalizer: %d bands, headroom %.1f db", d->eq_n, most);
}

JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_gain(JNIEnv *e, jclass c, jlong h, jfloat g) {
    int b; memcpy(&b, &g, 4); atomic_store(&of(h)->gain_bits, b);
}

// the stream stopped (the DAC kept): its transfers called back, the thread ended, the ring let go
JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_stop(JNIEnv *e, jclass c, jlong h) {
    dac_t *d = of(h);
    if (!atomic_exchange(&d->running, 0)) return;
    for (int i = 0; i < URBS; i++) if (d->urbs[i]) ioctl(d->fd, USBDEVFS_DISCARDURB, d->urbs[i]);
    if (d->fb_urb) ioctl(d->fd, USBDEVFS_DISCARDURB, d->fb_urb);
    pthread_join(d->thread, NULL);
    for (int i = 0; i < URBS; i++) { if (d->urbs[i]) { free(d->urbs[i]->buffer); free(d->urbs[i]); d->urbs[i] = NULL; } }
    if (d->fb_urb) { free(d->fb_urb->buffer); free(d->fb_urb); d->fb_urb = NULL; }
    pthread_mutex_lock(&d->lock); free(d->ring); d->ring = NULL; d->used = 0; pthread_mutex_unlock(&d->lock);
}

JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_close(JNIEnv *e, jclass c, jlong h) {
    dac_t *d = of(h);
    Java_app_ink_music_UsbStream_stop(e, c, h);
    pthread_mutex_destroy(&d->lock);
    free(d);
}

// Android's own audio given the DAC back: its driver bound to the interface again (claiming took it away)
JNIEXPORT void JNICALL Java_app_ink_music_UsbStream_reattach(JNIEnv *e, jclass c, jint fd, jint iface) {
    struct usbdevfs_ioctl cmd = { .ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
    if (ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0) LOG("reattach %d: %s", iface, strerror(errno));
}
