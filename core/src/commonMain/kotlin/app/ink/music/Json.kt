package app.ink.music

/**
 * JSON as org.json has it, shared (2026-11-07): on the phone and the computer org.json itself (the same classes the apps
 * already pass in and out), on the iPhone ink music's own of the same shape (iosMain). Only what core uses.
 */
expect class JSONObject() {
    constructor(source: String)
    constructor(map: Map<*, *>)
    fun put(key: String, value: Any?): JSONObject
    fun opt(key: String): Any?
    fun has(key: String): Boolean
    fun remove(key: String): Any?
    fun keys(): Iterator<String>
    fun length(): Int
    fun optString(key: String): String
    fun optString(key: String, fallback: String): String
    fun optInt(key: String): Int
    fun optInt(key: String, fallback: Int): Int
    fun optLong(key: String): Long
    fun optLong(key: String, fallback: Long): Long
    fun optDouble(key: String): Double
    fun optDouble(key: String, fallback: Double): Double
    fun optBoolean(key: String): Boolean
    fun optBoolean(key: String, fallback: Boolean): Boolean
    fun optJSONObject(key: String): JSONObject?
    fun optJSONArray(key: String): JSONArray?
    fun getString(key: String): String
    fun getInt(key: String): Int
    fun getLong(key: String): Long
    fun getDouble(key: String): Double
    fun getBoolean(key: String): Boolean
    fun getJSONObject(key: String): JSONObject
    fun getJSONArray(key: String): JSONArray
    fun isNull(key: String): Boolean
}

expect class JSONArray() {
    constructor(source: String)
    constructor(collection: Collection<*>)
    fun put(value: Any?): JSONArray
    fun length(): Int
    fun get(index: Int): Any
    fun opt(index: Int): Any?
    fun optString(index: Int): String
    fun optString(index: Int, fallback: String): String
    fun optInt(index: Int): Int
    fun optLong(index: Int): Long
    fun optDouble(index: Int): Double
    fun optBoolean(index: Int): Boolean
    fun optJSONObject(index: Int): JSONObject?
    fun optJSONArray(index: Int): JSONArray?
    fun getString(index: Int): String
    fun getInt(index: Int): Int
    fun getLong(index: Int): Long
    fun getJSONObject(index: Int): JSONObject
    fun getJSONArray(index: Int): JSONArray
    fun remove(index: Int): Any?
}
