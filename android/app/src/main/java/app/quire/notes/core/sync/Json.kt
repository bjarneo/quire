package app.quire.notes.core.sync

/**
 * A small JSON reader and writer for the sync files. The engine uses it instead of org.json, so
 * the engine and its tests run on a plain JVM.
 *
 * Objects become [Map] in key order, arrays become [List], whole numbers become [Long] and other
 * numbers become [Double].
 */
internal object Json {
    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != s.length) throw IllegalArgumentException("Text after the JSON value")
        return v
    }

    /** Writes [v]. With [pretty], nested values go on their own lines with two spaces of indent. */
    fun write(v: Any?, pretty: Boolean = false): String = StringBuilder().also { put(it, v, pretty, 0) }.toString()

    private fun put(b: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> b.append("null")
            is String -> str(b, v)
            is Boolean -> b.append(v)
            is Int, is Long -> b.append(v)
            is Number -> b.append(v.toDouble())
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    b.append("{}")
                    return
                }
                b.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(',')
                    first = false
                    if (pretty) indent(b, depth + 1)
                    str(b, k.toString())
                    b.append(if (pretty) ": " else ":")
                    put(b, x, pretty, depth + 1)
                }
                if (pretty) indent(b, depth)
                b.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) {
                    b.append("[]")
                    return
                }
                b.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) b.append(',')
                    if (pretty) indent(b, depth + 1)
                    put(b, x, pretty, depth + 1)
                }
                if (pretty) indent(b, depth)
                b.append(']')
            }
            else -> str(b, v.toString())
        }
    }

    private fun indent(b: StringBuilder, depth: Int) {
        b.append('\n')
        repeat(depth) { b.append("  ") }
    }

    private fun str(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                '\b' -> b.append("\\b")
                '\u000c' -> b.append("\\f")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        b.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun fail(): Nothing = throw IllegalArgumentException("Bad JSON at $i")

        fun value(): Any? {
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail()
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) fail()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail()
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) fail()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail()
                }
            }
        }

        fun string(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail()
                                b.append(code.toChar())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") fail()
            return if (t.any { it in ".eE" }) t.toDoubleOrNull() ?: fail() else t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail()
        }
    }
}
