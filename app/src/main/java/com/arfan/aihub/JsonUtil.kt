package com.arfan.aihub

/**
 * Tiny JSON helpers used to talk to injected JavaScript.
 *
 * `WebView.evaluateJavascript` answers with a JSON-encoded string, so every
 * reply is (a) unwrapped from one JSON string layer and then (b) read
 * field-by-field. Only the flat objects built by [SiteAdapters] need to be
 * handled, which is why this is hand-written instead of a JSON dependency —
 * the app is not allowed any new dependencies.
 *
 * These live outside [SiteAdapters] so they can be unit-tested directly.
 */
object JsonUtil {

    fun unwrap(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value == "null") return null
        if (!value.startsWith("\"")) return value
        return unescape(value)
    }

    /** Minimal JSON-string unescape, enough for the objects we build above. */
    fun unescape(quoted: String): String {
        val sb = StringBuilder(quoted.length)
        var i = 1
        val end = quoted.length - 1
        while (i < end) {
            val c = quoted[i]
            if (c == '\\' && i + 1 < end) {
                when (val n = quoted[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        val hex = quoted.substring(i + 2, (i + 6).coerceAtMost(end))
                        if (hex.length == 4) {
                            sb.append(hex.toInt(16).toChar())
                            i += 6
                        } else {
                            i += 2
                        }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Reads a flat `"key": value` field out of the object text (flat objects only). */
    fun findValue(json: String, key: String): String? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val at = json.indexOf(needle, from)
            if (at < 0) return null
            var i = at + needle.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != ':') {
                from = at + needle.length
                continue
            }
            i++
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) return null
            if (json[i] == '"') {
                val value = StringBuilder()
                var j = i + 1
                while (j < json.length) {
                    val c = json[j]
                    if (c == '\\' && j + 1 < json.length) {
                        value.append(c).append(json[j + 1])
                        j += 2
                        continue
                    }
                    if (c == '"') break
                    value.append(c)
                    j++
                }
                return unescape("\"" + value.toString() + "\"")
            }
            val start = i
            while (i < json.length && json[i] != ',' && json[i] != '}') i++
            return json.substring(start, i).trim()
        }
    }

    fun str(json: String, key: String): String = findValue(json, key) ?: ""
    fun int(json: String, key: String): Int =
        findValue(json, key)?.toDoubleOrNull()?.toInt() ?: 0
    fun bool(json: String, key: String): Boolean =
        findValue(json, key)?.equals("true", ignoreCase = true) == true

    /**
     * Quotes [text] as a JavaScript string literal. Used to build the scripts
     * that type into a page, so quotes, backslashes, newlines and the two
     * Unicode line separators must all be escaped — a raw newline would end the
     * statement and throw inside the page.
     */
    fun jsonString(text: String): String {
        val sb = StringBuilder(text.length + 16)
        sb.append('"')
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\u2028' -> sb.append("\\u2028")
                c == '\u2029' -> sb.append("\\u2029")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                // Non-ASCII stays readable but cannot break out of the literal.
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
