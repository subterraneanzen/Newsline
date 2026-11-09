package ca.newsarchive.core.text

/**
 * Tiny lenient JSON reader used for JSON-LD metadata blocks. Returns Map/List/String/Double/Boolean/null.
 * Kept dependency-free so the core module runs identically on the JVM and Android.
 */
object MiniJson {
    fun parse(s: String): Any? = Reader(s).run { skipWs(); val v = value(); v }

    private class Reader(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && (s[i].isWhitespace() || s[i] == '﻿')) i++ }
        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("eof")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("bad char '$c' at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++
            while (true) {
                skipWs()
                if (i < s.length && s[i] == '}') { i++; return m }
                if (i < s.length && s[i] == ',') { i++; continue }
                val k = str(); skipWs()
                if (i < s.length && s[i] == ':') i++
                m[k] = value()
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++
            while (true) {
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return l }
                if (i < s.length && s[i] == ',') { i++; continue }
                l += value()
            }
        }
        fun str(): String {
            skipWs()
            if (s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}
