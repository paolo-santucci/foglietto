package com.paolosantucci.foglietto.shared.editor

/**
 * Result of a [LineIndent.indent] or [LineIndent.outdent] operation.
 *
 * Verbatim port of IndentResult from lib/domain/editor/line_indent.dart (Dart oracle, `main`).
 */
data class IndentResult(val text: String, val selStart: Int, val selExtent: Int)

/**
 * Pure, total indent/outdent helper.
 *
 * Verbatim port of LineIndent from lib/domain/editor/line_indent.dart (Dart oracle, `main`).
 *
 * Indent unit per line:
 *   - "  " (two spaces) for lines matching the bullet OR ordered regex.
 *   - "\t" (one tab)    for all other lines.
 *
 * Kotlin dialect pins applied throughout:
 *   - String is UTF-16 (Char == code unit); length/substring/indexOf/lastIndexOf port 1:1.
 *   - split("\n") ONLY — never lines()/lineSequence() (they split on \r\n too).
 *   - .clamp() → .coerceIn().
 *   - Regex as raw strings; containsMatchIn (never matches).
 */
object LineIndent {

    // ── Canon-verbatim regex constants ────────────────────────────────────────
    // Verbatim from the Dart oracle's _bulletRegex / _orderedRegex.
    // bullet: longer patterns first so "- [ ] " and "- [x] " match before "- ".
    private val bulletRegex = Regex("""^\s*(- \[ \] |- \[x\] |- |\+ |\* )""")
    private val orderedRegex = Regex("""^(\s*)([a-zA-Z]{1}|[0-9]+)([\.\)]){1}[ ]+""")

    private const val LIST_UNIT = "  "  // two spaces
    private const val NON_LIST_UNIT = "\t"

    // ── Private helpers matching the Dart oracle structure ─────────────────────

    /** A line entry: its UTF-16 code-unit start offset in the full text + its content. */
    private data class Line(val start: Int, val content: String)

    /** Three-value shift tuple returned by the per-line mutation helpers. */
    private data class IndentShift(val startDelta: Int, val extentDelta: Int)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Adds one indent unit at the start of each affected line.
     *
     * Collapsed / single-line selection → caret line only.
     * Multi-line selection → every non-blank line; blank lines skipped.
     * Selection re-anchored via position-stable offsets (FR-26).
     */
    fun indent(text: String, selStart: Int, selExtent: Int): IndentResult =
        modify(text, selStart, selExtent, increase = true)

    /**
     * Removes exactly one leading indent unit IFF the line starts with it.
     * Column-0 / no leading unit → no-op for that line (FR-27).
     * indent → outdent round-trips text and both offsets byte-for-byte (FR-28).
     */
    fun outdent(text: String, selStart: Int, selExtent: Int): IndentResult =
        modify(text, selStart, selExtent, increase = false)

    // ── Core ──────────────────────────────────────────────────────────────────

    private fun modify(
        text: String,
        selStart: Int,
        selExtent: Int,
        increase: Boolean,
    ): IndentResult {
        if (text.isEmpty()) return IndentResult(text, selStart, selExtent)

        val lines = splitLines(text)
        val isMultiLine = spansMultipleLines(text, selStart, selExtent)
        val affected = affectedLineIndices(lines, selStart, selExtent, isMultiLine)

        return applyToLines(
            lines = lines,
            affected = affected,
            selStart = selStart,
            selExtent = selExtent,
            increase = increase,
        )
    }

    // ── Line splitting ─────────────────────────────────────────────────────────

    /**
     * Splits [text] on "\n" (never lines()/lineSequence()) preserving trailing empties.
     * Returns a list of Lines with their UTF-16 start offsets.
     */
    private fun splitLines(text: String): List<Line> {
        val result = mutableListOf<Line>()
        var offset = 0
        for (line in text.split("\n")) {
            result.add(Line(start = offset, content = line))
            offset += line.length + 1
        }
        return result
    }

    // ── Selection helpers ──────────────────────────────────────────────────────

    private fun spansMultipleLines(text: String, selStart: Int, selExtent: Int): Boolean {
        val lo = minOf(selStart, selExtent).coerceIn(0, text.length)
        val hi = maxOf(selStart, selExtent).coerceIn(0, text.length)
        return text.substring(lo, hi).contains("\n")
    }

    private fun affectedLineIndices(
        lines: List<Line>,
        selStart: Int,
        selExtent: Int,
        isMultiLine: Boolean,
    ): List<Int> {
        if (!isMultiLine) {
            val caretPos = minOf(selStart, selExtent)
            return listOf(lineIndexAt(lines, caretPos))
        }

        val lo = minOf(selStart, selExtent)
        val hi = maxOf(selStart, selExtent)
        val result = mutableListOf<Int>()
        for (i in lines.indices) {
            val lineStart = lines[i].start
            val lineEnd = lineStart + lines[i].content.length
            if (lineEnd < lo) continue
            if (lineStart > hi) break
            if (lines[i].content.isEmpty()) continue // skip blank lines
            result.add(i)
        }
        return result
    }

    private fun lineIndexAt(lines: List<Line>, offset: Int): Int {
        for (i in lines.indices.reversed()) {
            if (lines[i].start <= offset) return i
        }
        return 0
    }

    // ── Mutation with position-stable re-anchoring ────────────────────────────

    private fun applyToLines(
        lines: List<Line>,
        affected: List<Int>,
        selStart: Int,
        selExtent: Int,
        increase: Boolean,
    ): IndentResult {
        val buf = StringBuilder()
        var selStartShift = 0
        var selExtentShift = 0

        for (i in lines.indices) {
            if (i > 0) buf.append("\n")

            if (i !in affected) {
                buf.append(lines[i].content)
                continue
            }

            val shift = if (increase) {
                applyIndent(buf, lines[i], selStart, selExtent)
            } else {
                applyOutdent(buf, lines[i], selStart, selExtent)
            }

            selStartShift += shift.startDelta
            selExtentShift += shift.extentDelta
        }

        val newText = buf.toString()
        return IndentResult(
            text = newText,
            selStart = (selStart + selStartShift).coerceIn(0, newText.length),
            selExtent = (selExtent + selExtentShift).coerceIn(0, newText.length),
        )
    }

    /** Prepends the indent unit; returns (startDelta, extentDelta). */
    private fun applyIndent(
        buf: StringBuilder,
        line: Line,
        selStart: Int,
        selExtent: Int,
    ): IndentShift {
        val unit = unitFor(line.content)
        buf.append(unit)
        buf.append(line.content)

        // Position-stable anchoring: shift points STRICTLY after the line start.
        val unitLen = unit.length
        val startDelta = if (selStart > line.start) unitLen else 0
        val extentDelta = if (selExtent > line.start) unitLen else 0
        return IndentShift(startDelta, extentDelta)
    }

    /** Strips the indent unit if present; returns (startDelta, extentDelta). */
    private fun applyOutdent(
        buf: StringBuilder,
        line: Line,
        selStart: Int,
        selExtent: Int,
    ): IndentShift {
        val unit = unitFor(line.content)
        if (!line.content.startsWith(unit)) {
            buf.append(line.content) // no-op
            return IndentShift(0, 0)
        }

        buf.append(line.content.substring(unit.length))

        val unitLen = unit.length
        val lineStart = line.start
        val startDelta = removeDelta(selStart, lineStart, unitLen)
        val extentDelta = removeDelta(selExtent, lineStart, unitLen)
        return IndentShift(startDelta, extentDelta)
    }

    /**
     * Computes how much a selection point at [pos] should shift when [unitLen]
     * bytes are removed from [lineStart].
     *
     * Verbatim port of _removeDelta from the Dart oracle.
     */
    private fun removeDelta(pos: Int, lineStart: Int, unitLen: Int): Int {
        return when {
            pos > lineStart + unitLen -> -unitLen
            pos > lineStart -> -(pos - lineStart) // inside removed prefix
            else -> 0
        }
    }

    // ── Unit selector ──────────────────────────────────────────────────────────

    /** Returns the indent unit for [lineContent]: two spaces for list lines, tab otherwise. */
    private fun unitFor(lineContent: String): String {
        return if (bulletRegex.containsMatchIn(lineContent) ||
            orderedRegex.containsMatchIn(lineContent)
        ) {
            LIST_UNIT
        } else {
            NON_LIST_UNIT
        }
    }
}
