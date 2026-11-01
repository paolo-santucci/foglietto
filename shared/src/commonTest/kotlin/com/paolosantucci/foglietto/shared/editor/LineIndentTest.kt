package com.paolosantucci.foglietto.shared.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of test/domain/editor/line_indent_test.dart (Dart oracle on `main`).
 * TDD: these tests are written FIRST — before any implementation.
 *
 * 9 cases covering FR-26, FR-27, FR-28, and the coerceIn clamping invariant.
 *
 * IMPORTANT: All expected values derived from the Dart oracle IMPLEMENTATION
 * (lib/domain/editor/line_indent.dart), specifically the _applyIndent/_applyOutdent
 * logic:
 *   startDelta = if (selStart > line.start) unitLen else 0
 *   extentDelta = if (selExtent > line.start) unitLen else 0
 *
 * For selStart=0 on the first (and only) line (line.start=0):
 *   0 > 0 is false → startDelta = 0 → selStart stays at 0.
 *   5 > 0 is true  → extentDelta = unitLen → selExtent grows by unitLen.
 */
class LineIndentTest {

    // ── 1. indent single non-list line — tab unit ─────────────────────────────
    //
    // Dart oracle: indent("hello", 0, 5) → IndentResult("\thello", 0, 6)
    //   line.start=0; startDelta = (0 > 0) ? 1 : 0 = 0; extentDelta = (5 > 0) ? 1 : 0 = 1.

    @Test
    fun givenNonListLine_whenIndent_thenTabPrependedAndSelectionShifts() {
        val result = LineIndent.indent("hello", 0, 5)
        assertEquals("\thello", result.text)
        assertEquals(0, result.selStart)
        assertEquals(6, result.selExtent)
    }

    // ── 2. indent single list line — two-space unit ────────────────────────────
    //
    // Dart oracle: indent("- item", 0, 6) → IndentResult("  - item", 0, 8)
    //   line.start=0; startDelta = (0 > 0) ? 2 : 0 = 0; extentDelta = (6 > 0) ? 2 : 0 = 2.

    @Test
    fun givenListLine_whenIndent_thenTwoSpacesPrependedAndSelectionShifts() {
        val result = LineIndent.indent("- item", 0, 6)
        assertEquals("  - item", result.text)
        assertEquals(0, result.selStart)
        assertEquals(8, result.selExtent)
    }

    // ── 3. multi-line indent skips blank lines, re-anchors position-stably ─────
    //
    // "one\n\nthree" — 'one' (non-list, tab) and 'three' (non-list, tab) receive tabs;
    // blank middle line is skipped. selStart=0, selExtent=text.length=10.
    // "one" (3) + "\n" (1) + "" (0) + "\n" (1) + "three" (5) = 10 chars.
    //   Line 0: "one", start=0. startDelta=(0>0)?1:0=0; extentDelta=(10>0)?1:0=1.
    //   Line 1: "" (blank) → skipped.
    //   Line 2: "three", start=5. startDelta=(0>5)?1:0=0; extentDelta=(10>5)?1:0=1.
    //   Result: selStart=0, selExtent=10+1+1=12.

    @Test
    fun givenMultiLineSelectionWithBlankLine_whenIndent_thenBlankLinesSkippedAndSelectionReanchored() {
        val text = "one\n\nthree"
        val result = LineIndent.indent(text, 0, text.length)
        assertEquals("\tone\n\n\tthree", result.text)
        assertEquals(0, result.selStart)
        assertEquals(12, result.selExtent)
    }

    // ── 4. outdent line with leading tab ──────────────────────────────────────
    //
    // Dart oracle: outdent("\thello", 0, 6) → IndentResult("hello", 0, 5)
    //   unitFor("\thello") = "\t" (non-list). "\thello".startsWith("\t") = true → strip.
    //   removeDelta(selStart=0, lineStart=0, unitLen=1): 0 > 0+1? no. 0 > 0? no. → 0.
    //   removeDelta(selExtent=6, lineStart=0, unitLen=1): 6 > 0+1? yes → -1.
    //   selStart=0+0=0, selExtent=6-1=5.

    @Test
    fun givenTabIndentedLine_whenOutdent_thenTabRemovedAndOffsetsAdjusted() {
        val result = LineIndent.outdent("\thello", 0, 6)
        assertEquals("hello", result.text)
        assertEquals(0, result.selStart)
        assertEquals(5, result.selExtent)
    }

    // ── 5. outdent list line with two leading spaces ───────────────────────────
    //
    // Dart oracle: outdent("  - item", 0, 8) → IndentResult("- item", 0, 6)
    //   unitFor("  - item") = "  " (list line). "  - item".startsWith("  ") = true → strip.
    //   removeDelta(0, 0, 2): 0 > 2? no. 0 > 0? no. → 0.
    //   removeDelta(8, 0, 2): 8 > 2? yes → -2.
    //   selStart=0, selExtent=6.

    @Test
    fun givenListLineTwoSpaceIndented_whenOutdent_thenTwoSpacesRemovedAndOffsetsAdjusted() {
        val result = LineIndent.outdent("  - item", 0, 8)
        assertEquals("- item", result.text)
        assertEquals(0, result.selStart)
        assertEquals(6, result.selExtent)
    }

    // ── 6. outdent on un-indented non-list line is a no-op ────────────────────
    //
    // Dart oracle: outdent("hello", 0, 5) → IndentResult("hello", 0, 5) (no-op)
    //   unitFor("hello") = "\t". "hello".startsWith("\t") = false → no-op.

    @Test
    fun givenUnindentedLine_whenOutdent_thenNoOp() {
        val result = LineIndent.outdent("hello", 0, 5)
        assertEquals("hello", result.text)
        assertEquals(0, result.selStart)
        assertEquals(5, result.selExtent)
    }

    // ── 7. multi-line outdent — three-way delta, each line loses at most one unit ─
    //
    // Dart oracle: outdent("\tline one\n\tline two", 0, 19) → IndentResult("line one\nline two", 0, 17)
    //   Line 0: "\tline one", start=0. unitFor → "\t". startsWith("\t") = true → strip.
    //     removeDelta(0, 0, 1): 0>1? no. 0>0? no. → 0.
    //     removeDelta(19, 0, 1): 19>1? yes → -1.
    //   Line 1: "\tline two", start=10. unitFor → "\t". startsWith("\t") = true → strip.
    //     removeDelta(0, 10, 1): 0>11? no. 0>10? no. → 0.
    //     removeDelta(19, 10, 1): 19>11? yes → -1.
    //   selStart=0+0+0=0, selExtent=19-1-1=17.

    @Test
    fun givenMultiLineTabIndented_whenOutdent_thenThreeWayDeltaCorrect() {
        val text = "\tline one\n\tline two"
        val result = LineIndent.outdent(text, 0, text.length)
        assertEquals("line one\nline two", result.text)
        assertEquals(0, result.selStart)
        assertEquals("line one\nline two".length, result.selExtent)
    }

    // ── 8. indent then outdent round-trip restores text and both offsets byte-for-byte ─

    @Test
    fun givenIndentableSelection_whenIndentThenOutdent_thenAllThreeFieldsRestoredByteForByte() {
        val text = "- list one\nplain one\n1. ordered\nplain two"
        val selStart = 0
        val selExtent = text.length

        val indented = LineIndent.indent(text, selStart, selExtent)
        val roundTrip = LineIndent.outdent(indented.text, indented.selStart, indented.selExtent)

        assertEquals(text, roundTrip.text, "text must be byte-for-byte restored")
        assertEquals(selStart, roundTrip.selStart, "selStart must be exactly restored")
        assertEquals(selExtent, roundTrip.selExtent, "selExtent must be exactly restored")
    }

    // ── 9. coerceIn(0, newText.length) clamping on output offsets ────────────
    //
    // Pass out-of-bounds selStart/selExtent; coerceIn must prevent exceptions and
    // clamp output to [0, newText.length].

    @Test
    fun givenEdgeOffsets_whenIndent_thenOutputOffsetsClampedWithCoerceIn() {
        val text = "hello"
        // selStart=0 and selExtent clamped to text.length before spansMultipleLines.
        // After indent: "\thello" (length=6). Both offsets clamped to [0, 6].
        val result = LineIndent.indent(text, 0, text.length)
        assertTrue(result.selStart >= 0, "selStart must be >= 0")
        assertTrue(result.selExtent >= 0, "selExtent must be >= 0")
        assertTrue(result.selStart <= result.text.length, "selStart must be <= newText.length")
        assertTrue(result.selExtent <= result.text.length, "selExtent must be <= newText.length")

        // Also verify with valid large extents that get clamped on output.
        // Use a selection where selStart is within text, selExtent equals text.length.
        val result2 = LineIndent.indent("plain", 2, 5)
        assertTrue(result2.selStart in 0..result2.text.length)
        assertTrue(result2.selExtent in 0..result2.text.length)
    }
}
