package it.telecast.core

import java.util.Locale

/** JSON minimo (i messaggi Cast sono piccoli): parse in Map/List/String/Number/Boolean/null e viceversa. */
object Json {
    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(s: String): Map<String, Any?>? = try { parse(s) as? Map<String, Any?> } catch (_: Exception) { null }

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite() && v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format(Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("JSON incompleto")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = str()
                ws()
                expect(':')
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                expect('}')
                return m
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l += value()
                ws()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                expect(']')
                return l
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("stringa non chiusa")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val t = s.substring(st, i)
            if (t.isEmpty()) throw IllegalArgumentException("carattere inatteso alla posizione $st")
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("valore non valido alla posizione $i")
            i += word.length
            return v
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw IllegalArgumentException("atteso '$c' alla posizione $i")
            i++
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>

fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
