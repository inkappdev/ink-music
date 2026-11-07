package app.ink.music

/**
 * JSON on the iPhone, as org.json does it on the phone and the computer: a value missing or null gives an opt's default,
 * a get throws; numbers kept as Int, Long or Double as they read; written compact, "</" as "<\/".
 */
internal object JsonNull { override fun toString() = "null" }

class JSONException(message: String) : RuntimeException(message)

private fun wrap(v: Any?): Any? = when (v) {
    null -> null
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double, is Float, is Short, is Byte -> v
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), x) } }
    is Collection<*> -> JSONArray(v)
    is Array<*> -> JSONArray(v.toList())
    else -> v.toString()
}

private fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun bool(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}

actual class JSONObject actual constructor() {
    internal val map = LinkedHashMap<String, Any?>()

    actual constructor(source: String) : this() {
        val p = Parser(source)
        val v = p.value()
        p.end()
        if (v !is JSONObject) throw JSONException("not a JSON object")
        map.putAll(v.map)
    }

    actual constructor(map: Map<*, *>) : this() { map.forEach { (k, v) -> if (k != null) put(k.toString(), v) } }

    actual fun put(key: String, value: Any?): JSONObject { if (value == null) map.remove(key) else map[key] = wrap(value); return this }
    actual fun opt(key: String): Any? = map[key]
    actual fun has(key: String): Boolean = map.containsKey(key)
    actual fun remove(key: String): Any? = map.remove(key)
    actual fun keys(): Iterator<String> = map.keys.toList().iterator()
    actual fun length(): Int = map.size
    actual fun isNull(key: String): Boolean = map[key].let { it == null || it === JsonNull }

    private fun present(key: String): Any? = map[key]?.takeIf { it !== JsonNull }
    actual fun optString(key: String): String = optString(key, "")
    actual fun optString(key: String, fallback: String): String = present(key)?.let { if (it is Double) Writer.number(it) else it.toString() } ?: fallback
    actual fun optInt(key: String): Int = optInt(key, 0)
    actual fun optInt(key: String, fallback: Int): Int = num(present(key))?.toInt() ?: fallback
    actual fun optLong(key: String): Long = optLong(key, 0L)
    actual fun optLong(key: String, fallback: Long): Long = present(key).let { v -> if (v is Long || v is Int) (v as Number).toLong() else num(v)?.toLong() ?: fallback }
    actual fun optDouble(key: String): Double = optDouble(key, Double.NaN)
    actual fun optDouble(key: String, fallback: Double): Double = num(present(key)) ?: fallback
    actual fun optBoolean(key: String): Boolean = optBoolean(key, false)
    actual fun optBoolean(key: String, fallback: Boolean): Boolean = bool(present(key)) ?: fallback
    actual fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    actual fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    private fun need(key: String): Any = present(key) ?: throw JSONException("JSONObject[\"$key\"] not found.")
    actual fun getString(key: String): String = need(key) as? String ?: throw JSONException("JSONObject[\"$key\"] is not a string.")
    actual fun getInt(key: String): Int = num(need(key))?.toInt() ?: throw JSONException("JSONObject[\"$key\"] is not a number.")
    actual fun getLong(key: String): Long = need(key).let { v -> if (v is Long || v is Int) (v as Number).toLong() else num(v)?.toLong() } ?: throw JSONException("JSONObject[\"$key\"] is not a number.")
    actual fun getDouble(key: String): Double = num(need(key)) ?: throw JSONException("JSONObject[\"$key\"] is not a number.")
    actual fun getBoolean(key: String): Boolean = bool(need(key)) ?: throw JSONException("JSONObject[\"$key\"] is not a Boolean.")
    actual fun getJSONObject(key: String): JSONObject = need(key) as? JSONObject ?: throw JSONException("JSONObject[\"$key\"] is not a JSONObject.")
    actual fun getJSONArray(key: String): JSONArray = need(key) as? JSONArray ?: throw JSONException("JSONObject[\"$key\"] is not a JSONArray.")

    override fun toString(): String = StringBuilder().also { Writer.write(this, it) }.toString()
}

actual class JSONArray actual constructor() {
    internal val list = ArrayList<Any?>()

    actual constructor(source: String) : this() {
        val p = Parser(source)
        val v = p.value()
        p.end()
        if (v !is JSONArray) throw JSONException("not a JSON array")
        list.addAll(v.list)
    }

    actual constructor(collection: Collection<*>) : this() { collection.forEach { list.add(wrap(it)) } }

    actual fun put(value: Any?): JSONArray { list.add(wrap(value) ?: JsonNull); return this }
    actual fun length(): Int = list.size
    actual fun get(index: Int): Any = list.getOrNull(index)?.takeIf { it !== JsonNull } ?: throw JSONException("JSONArray[$index] not found.")
    actual fun opt(index: Int): Any? = list.getOrNull(index)
    private fun present(i: Int): Any? = list.getOrNull(i)?.takeIf { it !== JsonNull }
    actual fun optString(index: Int): String = optString(index, "")
    actual fun optString(index: Int, fallback: String): String = present(index)?.let { if (it is Double) Writer.number(it) else it.toString() } ?: fallback
    actual fun optInt(index: Int): Int = num(present(index))?.toInt() ?: 0
    actual fun optLong(index: Int): Long = present(index).let { v -> if (v is Long || v is Int) (v as Number).toLong() else num(v)?.toLong() ?: 0L }
    actual fun optDouble(index: Int): Double = num(present(index)) ?: Double.NaN
    actual fun optBoolean(index: Int): Boolean = bool(present(index)) ?: false
    actual fun optJSONObject(index: Int): JSONObject? = list.getOrNull(index) as? JSONObject
    actual fun optJSONArray(index: Int): JSONArray? = list.getOrNull(index) as? JSONArray
    actual fun getString(index: Int): String = get(index) as? String ?: throw JSONException("JSONArray[$index] is not a string.")
    actual fun getInt(index: Int): Int = num(get(index))?.toInt() ?: throw JSONException("JSONArray[$index] is not a number.")
    actual fun getLong(index: Int): Long = get(index).let { v -> if (v is Long || v is Int) (v as Number).toLong() else num(v)?.toLong() } ?: throw JSONException("JSONArray[$index] is not a number.")
    actual fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw JSONException("JSONArray[$index] is not a JSONObject.")
    actual fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw JSONException("JSONArray[$index] is not a JSONArray.")
    actual fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null

    override fun toString(): String = StringBuilder().also { Writer.write(this, it) }.toString()
}

/** JSON written as org.json writes it. */
internal object Writer {
    fun number(d: Double): String = when {
        d.isNaN() || d.isInfinite() -> "null"
        d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
        else -> d.toString()
    }

    fun write(v: Any?, out: StringBuilder) {
        when (v) {
            null, JsonNull -> out.append("null")
            is JSONObject -> {
                out.append('{'); var first = true
                for ((k, x) in v.map) { if (!first) out.append(','); first = false; quote(k, out); out.append(':'); write(x, out) }
                out.append('}')
            }
            is JSONArray -> {
                out.append('['); v.list.forEachIndexed { i, x -> if (i > 0) out.append(','); write(x, out) }; out.append(']')
            }
            is String -> quote(v, out)
            is Double -> out.append(number(v))
            is Float -> out.append(number(v.toDouble()))
            is Number, is Boolean -> out.append(v.toString())
            else -> quote(v.toString(), out)
        }
    }

    fun quote(s: String, out: StringBuilder) {
        out.append('"')
        var prev = ' '
        for (c in s) {
            when {
                c == '"' || c == '\\' -> out.append('\\').append(c)
                c == '/' && prev == '<' -> out.append("\\/")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c in '\u0080'..'\u009f' || c in ' '..'⃿' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
            prev = c
        }
        out.append('"')
    }
}

/** JSON read: objects, arrays, strings (escapes and surrogates), numbers, true, false, null. */
private class Parser(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun end() { ws(); if (i < s.length) throw JSONException("text after the JSON at $i") }
    fun value(): Any? {
        ws()
        if (i >= s.length) throw JSONException("JSON ended early")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"', '\'' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", JsonNull)
            else -> if (c == '-' || c.isDigit()) number() else throw JSONException("unexpected '$c' at $i")
        }
    }
    fun word(w: String, v: Any): Any { if (!s.startsWith(w, i)) throw JSONException("unexpected at $i"); i += w.length; return v }
    fun obj(): JSONObject {
        val o = JSONObject(); i++; ws()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            ws(); val k = str(); ws()
            if (i >= s.length || s[i] != ':') throw JSONException("expected ':' at $i")
            i++
            o.map[k] = value(); ws()
            if (i >= s.length) throw JSONException("JSON ended early")
            when (s[i]) { ',' -> i++; '}' -> { i++; return o }; else -> throw JSONException("expected ',' or '}' at $i") }
        }
    }
    fun arr(): JSONArray {
        val a = JSONArray(); i++; ws()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            a.list.add(value()); ws()
            if (i >= s.length) throw JSONException("JSON ended early")
            when (s[i]) { ',' -> i++; ']' -> { i++; return a }; else -> throw JSONException("expected ',' or ']' at $i") }
        }
    }
    fun str(): String {
        val q = s[i]
        if (q != '"' && q != '\'') throw JSONException("expected a string at $i")
        i++
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) throw JSONException("a string never ended")
            val c = s[i++]
            when {
                c == q -> return b.toString()
                c == '\\' -> {
                    val e = s[i++]
                    when (e) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                }
                else -> b.append(c)
            }
        }
    }
    fun number(): Any {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
        val t = s.substring(start, i)
        if (t.none { it == '.' || it == 'e' || it == 'E' }) {
            t.toIntOrNull()?.let { return it }
            t.toLongOrNull()?.let { return it }
        }
        return t.toDoubleOrNull() ?: throw JSONException("not a number: $t")
    }
}
