package dev.nekuz.localmind

object ForeignText {
    const val MAX_LABEL = 128
    const val MAX_VALUE = 1024
    const val MAX_LONG = 65_536
    const val UNPRINTABLE = "(unprintable)"

    private const val MAX_SCAN = 65_536
    private const val MAX_MARK_RUN = 4
    private const val ELLIPSIS = "…"
    private val BLANK_LETTERS = setOf(0x115F, 0x1160, 0x17B4, 0x17B5, 0x2800, 0x3164, 0xFFA0)

    fun label(raw: CharSequence?, fallback: String): String = clean(raw, MAX_LABEL).ifEmpty { fallback }

    fun optional(raw: CharSequence?, maxLength: Int = MAX_VALUE): String? {
        if (raw.isNullOrEmpty()) return null
        return clean(raw, maxLength).ifEmpty { UNPRINTABLE }
    }

    fun clean(raw: CharSequence?, maxLength: Int = MAX_VALUE): String {
        if (raw == null) return ""
        val text = StringBuilder()
        val end = minOf(raw.length, MAX_SCAN)
        var truncated = raw.length > MAX_SCAN
        var index = 0
        var markRun = 0
        var pendingSpace = false
        var visible = false
        while (index < end) {
            val codePoint = Character.codePointAt(raw, index)
            index += Character.charCount(codePoint)
            if (isHidden(codePoint)) continue
            if (isSpace(codePoint)) {
                pendingSpace = text.isNotEmpty()
                markRun = 0
                continue
            }
            val mark = isCombiningMark(codePoint)
            if (mark && markRun >= MAX_MARK_RUN) continue
            markRun = if (mark) markRun + 1 else 0
            val needed = Character.charCount(codePoint) + if (pendingSpace) 1 else 0
            if (text.length + needed > maxLength) {
                truncated = true
                break
            }
            if (pendingSpace) text.append(' ')
            pendingSpace = false
            text.appendCodePoint(codePoint)
            if (!mark) visible = true
        }
        if (!visible) return ""
        if (truncated) text.append(ELLIPSIS)
        return text.toString()
    }

    fun multiline(raw: CharSequence?, maxLength: Int = MAX_LONG): String {
        if (raw == null) return ""
        val cap = maxLength.coerceAtLeast(1)
        val text = StringBuilder(minOf(raw.length, cap))
        val end = raw.length
        var truncated = false
        var index = 0
        var markRun = 0
        while (index < end) {
            val codePoint = Character.codePointAt(raw, index)
            index += Character.charCount(codePoint)
            val keep = codePoint == '\n'.code || codePoint == '\t'.code
            if (!keep && isHidden(codePoint)) continue
            val mark = isCombiningMark(codePoint)
            if (mark && markRun >= MAX_MARK_RUN) continue
            markRun = if (mark) markRun + 1 else 0
            if (text.length + Character.charCount(codePoint) > cap) {
                truncated = true
                break
            }
            text.appendCodePoint(codePoint)
        }
        if (truncated) text.append(ELLIPSIS)
        return text.toString()
    }

    private fun isHidden(codePoint: Int): Boolean {
        if (codePoint in BLANK_LETTERS) return true
        return when (Character.getType(codePoint).toByte()) {
            Character.CONTROL,
            Character.FORMAT,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE,
            Character.PRIVATE_USE,
            Character.UNASSIGNED -> true
            else -> false
        }
    }

    private fun isSpace(codePoint: Int) = Character.getType(codePoint).toByte() == Character.SPACE_SEPARATOR

    private fun isCombiningMark(codePoint: Int) = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
