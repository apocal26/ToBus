package com.example.data.network

/**
 * Lightweight, zero-dependency recursive-descent JSON parser written in pure Kotlin.
 *
 * Used by parsers that must run identically on both Android devices and fast pure-JVM unit tests
 * (where `org.json.JSONObject` stubs return default empty values).
 */
internal object MinimalJsonParser {

    /**
     * Parses a JSON document into Kotlin primitives (`Map<String, Any?>`, `List<Any?>`, `String`,
     * `Boolean`, `Long`, `Double`, or `null`). Returns `null` for blank or malformed input.
     */
    fun parse(jsonText: String): Any? {
        val text = jsonText.trim()
        if (text.isEmpty()) return null
        return Reader(text).readValue()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(jsonText: String): Map<String, Any?>? = parse(jsonText) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun parseArray(jsonText: String): List<Any?>? = parse(jsonText) as? List<Any?>

    fun Map<*, *>.optString(key: String, fallback: String = ""): String =
        (this[key] as? String) ?: fallback

    fun Map<*, *>.optBoolean(key: String, fallback: Boolean = false): Boolean =
        when (val v = this[key]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> fallback
        }

    fun Map<*, *>.optLong(key: String, fallback: Long = 0L): Long =
        when (val v = this[key]) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: fallback
            else -> fallback
        }

    @Suppress("UNCHECKED_CAST")
    fun Map<*, *>.optArray(key: String): List<Any?>? = this[key] as? List<Any?>

    private class Reader(private val src: String) {
        private var pos = 0
        private val len = src.length

        private fun skipWs() {
            while (pos < len && src[pos] <= ' ') pos++
        }

        private fun readString(): String {
            if (pos >= len || src[pos] != '"') return ""
            pos++
            val sb = StringBuilder()
            while (pos < len) {
                val ch = src[pos++]
                if (ch == '"') break
                if (ch == '\\' && pos < len) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (pos + 4 <= len) {
                            val code = src.substring(pos, pos + 4).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun readValue(): Any? {
            skipWs()
            if (pos >= len) return null
            return when (src[pos]) {
                '"' -> readString()
                '[' -> readArray()
                '{' -> readObject()
                else -> readLiteral()
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < len && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < len) {
                list.add(readValue())
                skipWs()
                if (pos < len && src[pos] == ',') {
                    pos++
                } else {
                    if (pos < len && src[pos] == ']') pos++
                    break
                }
            }
            return list
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < len && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < len) {
                skipWs()
                if (pos >= len || src[pos] != '"') break
                val key = readString()
                skipWs()
                if (pos < len && src[pos] == ':') pos++
                map[key] = readValue()
                skipWs()
                if (pos < len && src[pos] == ',') {
                    pos++
                } else {
                    if (pos < len && src[pos] == '}') pos++
                    break
                }
            }
            return map
        }

        private fun readLiteral(): Any? {
            val start = pos
            while (pos < len && src[pos] != ',' && src[pos] != '}' && src[pos] != ']' && src[pos] > ' ') {
                pos++
            }
            val token = src.substring(start, pos)
            return when {
                token == "null" -> null
                token == "true" -> true
                token == "false" -> false
                token.toLongOrNull() != null -> token.toLong()
                token.toDoubleOrNull() != null -> token.toDouble()
                else -> token
            }
        }
    }
}
