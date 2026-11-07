package dev.nekuz.localmind

sealed class JsonValue {
    data object JNull : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    data class JNum(val value: Double, val raw: String) : JsonValue() {
        val exactLong: Long?
            get() {
                val plain = raw.toLongOrNull()
                if (plain != null) return plain
                if (value != Math.rint(value) || Math.abs(value) > MAX_SAFE) return null
                return value.toLong()
            }
    }
    data class JStr(val value: String) : JsonValue()
    data class JArr(val items: List<JsonValue>) : JsonValue()
    data class JObj(val fields: Map<String, JsonValue>) : JsonValue() {
        fun str(key: String): String? = (fields[key] as? JStr)?.value
        fun long(key: String): Long? = (fields[key] as? JNum)?.exactLong
        fun double(key: String): Double? = (fields[key] as? JNum)?.value
        fun bool(key: String): Boolean? = (fields[key] as? JBool)?.value
        fun arr(key: String): List<JsonValue>? = (fields[key] as? JArr)?.items
        fun obj(key: String): JObj? = fields[key] as? JObj
    }

    private companion object {
        const val MAX_SAFE = 9_007_199_254_740_992.0
    }
}

object StrictJson {
    const val DEFAULT_MAX_CHARS = 4 * 1024 * 1024
    const val DEFAULT_MAX_DEPTH = 32
    private const val MAX_NODES = 200_000

    fun parse(text: String?, maxChars: Int = DEFAULT_MAX_CHARS, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonValue? {
        if (text == null || text.length > maxChars) return null
        return try {
            val parser = Parser(text, maxDepth)
            parser.skipSpace()
            val value = parser.value(0)
            parser.skipSpace()
            if (parser.atEnd()) value else null
        } catch (e: Bad) {
            null
        }
    }

    fun parseObject(text: String?, maxChars: Int = DEFAULT_MAX_CHARS, maxDepth: Int = DEFAULT_MAX_DEPTH): JsonValue.JObj? =
        parse(text, maxChars, maxDepth) as? JsonValue.JObj

    private class Bad : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Parser(private val text: String, private val maxDepth: Int) {
        private var position = 0
        private var nodes = 0

        fun atEnd() = position >= text.length

        fun skipSpace() {
            while (position < text.length) {
                val c = text[position]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') position++ else return
            }
        }

        fun value(depth: Int): JsonValue {
            if (depth >= maxDepth) throw Bad()
            if (++nodes > MAX_NODES) throw Bad()
            if (atEnd()) throw Bad()
            return when (text[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonValue.JStr(stringValue())
                't' -> literal("true", JsonValue.JBool(true))
                'f' -> literal("false", JsonValue.JBool(false))
                'n' -> literal("null", JsonValue.JNull)
                else -> numberValue()
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) throw Bad()
            position += word.length
            return value
        }

        private fun objectValue(depth: Int): JsonValue {
            position++
            val fields = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (peek() == '}') {
                position++
                return JsonValue.JObj(fields)
            }
            while (true) {
                skipSpace()
                if (peek() != '"') throw Bad()
                val key = stringValue()
                skipSpace()
                if (peek() != ':') throw Bad()
                position++
                skipSpace()
                val item = value(depth + 1)
                if (fields.containsKey(key)) throw Bad()
                fields[key] = item
                skipSpace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.JObj(fields)
                    }
                    else -> throw Bad()
                }
            }
        }

        private fun arrayValue(depth: Int): JsonValue {
            position++
            val items = ArrayList<JsonValue>()
            skipSpace()
            if (peek() == ']') {
                position++
                return JsonValue.JArr(items)
            }
            while (true) {
                skipSpace()
                items.add(value(depth + 1))
                skipSpace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.JArr(items)
                    }
                    else -> throw Bad()
                }
            }
        }

        private fun stringValue(): String {
            position++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw Bad()
                val c = text[position++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> throw Bad()
                    c == '\\' -> {
                        if (atEnd()) throw Bad()
                        when (text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> out.append(unicodeEscape())
                            else -> throw Bad()
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun unicodeEscape(): Char {
            if (position + 4 > text.length) throw Bad()
            var code = 0
            for (i in 0 until 4) {
                val digit = Character.digit(text[position + i], 16)
                if (digit < 0 || text[position + i].code > 127) throw Bad()
                code = code * 16 + digit
            }
            position += 4
            return code.toChar()
        }

        private fun numberValue(): JsonValue {
            val start = position
            if (peek() == '-') position++
            when (peek()) {
                '0' -> position++
                in '1'..'9' -> digits()
                else -> throw Bad()
            }
            if (peek() == '.') {
                position++
                if (peek() !in '0'..'9') throw Bad()
                digits()
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                if (peek() !in '0'..'9') throw Bad()
                digits()
            }
            val raw = text.substring(start, position)
            if (raw.length > 64) throw Bad()
            val number = raw.toDoubleOrNull() ?: throw Bad()
            if (number.isNaN() || number.isInfinite()) throw Bad()
            return JsonValue.JNum(number, raw)
        }

        private fun digits() {
            while (peek() in '0'..'9') position++
        }

        private fun peek(): Char = if (position < text.length) text[position] else '\u0000'
    }
}
