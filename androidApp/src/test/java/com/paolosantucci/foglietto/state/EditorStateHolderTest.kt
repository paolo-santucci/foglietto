package com.paolosantucci.foglietto.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paolosantucci.foglietto.shared.editor.ListContinuation
import com.paolosantucci.foglietto.shared.editor.canShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A unit suite for [EditorStateHolder] — plain JVM, no Robolectric, no UI host.
 *
 * Pins plan TASK-13 / spec §5.1.c, FR-10, FR-15, FR-16, FR-29, FR-31, FR-32, EC-01, EC-03,
 * EC-16, EC-17, EC-18, EC-22, EC-30:
 *  - the holder is the editor's single source of truth (`TextFieldValue`: text + selection);
 *  - Enter interception fires for a line break that lands at a collapsed caret — a plain `"\n"`,
 *    a CRLF pair, a Unicode separator, or an IME that commits an auto-correction with the break —
 *    consults the shared [ListContinuation] rule, and applies a non-null `ContinuationResult` as
 *    exactly ONE `TextFieldValue` write with the newline suppressed;
 *  - every other delta — a selection-spanning replacement, a commit that grows the line by more
 *    than a correction — passes through without consulting the shared rule at all;
 *  - indent/outdent apply the shared `LineIndent` result as exactly one write;
 *  - `populate` resets the caret to zero, bumps the scroll-to-top signal and records a snapshot;
 *  - undo/redo restore text and selection as a single write;
 *  - `shareEnabled` is the shared [canShare] predicate, never a local trim;
 *  - programmatic writes never re-enter [EditorStateHolder.onInput].
 *
 * "Exactly one write" is asserted at the state boundary: the test injects a
 * [MutableState] decorator that counts assignments to `value`, so the assertion observes real
 * snapshot writes instead of inspecting holder internals.
 */
class EditorStateHolderTest {

    /**
     * A [MutableState] that counts assignments to [value].
     *
     * The holder writes through this handle exactly like it writes through the `mutableStateOf`
     * state it builds by default, so the counter is the observable "number of `TextFieldValue`
     * writes" the acceptance criteria ask for.
     */
    private class CountingState(private val delegate: MutableState<TextFieldValue>) :
        MutableState<TextFieldValue> by delegate {

        var writes: Int = 0
            private set

        override var value: TextFieldValue
            get() = delegate.value
            set(newValue) {
                writes++
                delegate.value = newValue
            }
    }

    private class Harness {
        val history = EditorHistory()
        val state = CountingState(mutableStateOf(TextFieldValue("", TextRange.Zero)))

        /** Counts how many times the holder consulted the shared list-continuation rule. */
        var continuationInvocations: Int = 0
            private set

        val holder = EditorStateHolder(history, state) { text, caret ->
            continuationInvocations++
            ListContinuation.process(text, caret)
        }

        val writes: Int get() = state.writes

        /** Puts a buffer/selection in place the way the IME does: through `onInput`. */
        fun seed(text: String, caret: Int = text.length) {
            holder.onInput(TextFieldValue(text, TextRange(caret)))
        }
    }

    // ── EC-01, EC-03 ─────────────────────────────────────────────────────────

    @Test
    fun `initial state is empty and fully disabled`() {
        val harness = Harness()

        assertEquals(TextFieldValue("", TextRange.Zero), harness.holder.value)
        assertFalse(harness.holder.canUndo)
        assertFalse(harness.holder.canRedo)
        assertFalse(harness.holder.canClear)
        assertFalse(harness.holder.shareEnabled)
        assertEquals(0, harness.holder.scrollSignal)
        assertEquals(0, harness.writes)

        // An empty stack returns null and mutates nothing — not even a redundant write.
        harness.holder.undo()
        harness.holder.redo()
        assertEquals(TextFieldValue("", TextRange.Zero), harness.holder.value)
        assertEquals(0, harness.writes)
    }

    // ── FR-15 ────────────────────────────────────────────────────────────────

    @Test
    fun `a single newline at a collapsed caret on a list line continues the list`() {
        val harness = Harness()
        harness.seed("- item", caret = 6)
        assertEquals(0, harness.continuationInvocations)

        val writesBefore = harness.writes
        harness.holder.onInput(TextFieldValue("- item\n", TextRange(7)))

        assertEquals("- item\n- ", harness.holder.value.text)
        assertEquals(TextRange(9), harness.holder.value.selection)
        assertTrue(harness.holder.value.selection.collapsed)
        // ONE write: text + caret land in a single state assignment, newline suppressed.
        assertEquals(1, harness.writes - writesBefore)
        assertEquals(1, harness.continuationInvocations)
    }

    @Test
    fun `an ordered list line continues with the next index`() {
        val harness = Harness()
        harness.seed("1. item\n2. item", caret = 15)

        harness.holder.onInput(TextFieldValue("1. item\n2. item\n", TextRange(16)))

        assertEquals("1. item\n2. item\n3. ", harness.holder.value.text)
        assertEquals(TextRange(19), harness.holder.value.selection)
        assertEquals(1, harness.continuationInvocations)
    }

    @Test
    fun `an autocorrect committed with the newline still continues the list`() {
        // Samsung's keyboard commits its auto-correction and the Enter in ONE delta, so the
        // buffer grows by the newline plus the corrected characters instead of exactly one.
        val harness = Harness()
        harness.seed("- mill", caret = 6)

        harness.holder.onInput(TextFieldValue("- milk\n", TextRange(7)))

        assertEquals("- milk\n- ", harness.holder.value.text)
        assertEquals(TextRange(9), harness.holder.value.selection)
        assertEquals(1, harness.continuationInvocations)
    }

    @Test
    fun `a CRLF pair or a unicode separator is a single line break`() {
        val crlf = Harness()
        crlf.seed("- item", caret = 6)
        crlf.holder.onInput(TextFieldValue("- item\r\n", TextRange(8)))
        assertEquals("- item\n- ", crlf.holder.value.text)
        assertEquals(TextRange(9), crlf.holder.value.selection)

        val separator = Harness()
        separator.seed("- item", caret = 6)
        separator.holder.onInput(TextFieldValue("- item\u2028", TextRange(7)))
        assertEquals("- item\n- ", separator.holder.value.text)
        assertEquals(TextRange(9), separator.holder.value.selection)
    }

    @Test
    fun `a commit that grows the line beyond a correction is not an Enter`() {
        val harness = Harness()
        harness.seed("- milk", caret = 6)

        harness.holder.onInput(TextFieldValue("- milk and more\n", TextRange(15)))

        assertEquals("- milk and more\n", harness.holder.value.text)
        assertEquals(0, harness.continuationInvocations)
    }

    // ── EC-16, EC-17 ─────────────────────────────────────────────────────────

    @Test
    fun `the newline passes through otherwise`() {
        // (a) a selection spanning text: the IME replaces it, no single-insertion shape.
        val spanning = Harness()
        spanning.holder.onInput(TextFieldValue("abc", TextRange(0, 3)))
        val spanningWrites = spanning.writes

        spanning.holder.onInput(TextFieldValue("\n", TextRange(1)))

        assertEquals(TextFieldValue("\n", TextRange(1)), spanning.holder.value)
        assertEquals(1, spanning.writes - spanningWrites)
        assertEquals(0, spanning.continuationInvocations)

        // (b) a single newline at a collapsed caret on a NON-list line: the shape matches, so the
        // shared rule is consulted once, returns null, and the default newline passes through.
        val nonList = Harness()
        nonList.seed("hello", caret = 5)

        nonList.holder.onInput(TextFieldValue("hello\n", TextRange(6)))

        assertEquals(TextFieldValue("hello\n", TextRange(6)), nonList.holder.value)
        assertEquals(1, nonList.continuationInvocations)

        // (c) a multi-character commit that happens to contain "\n": never an Enter interception,
        // so the shared rule is not consulted at all.
        val multiChar = Harness()
        multiChar.seed("- item", caret = 6)

        multiChar.holder.onInput(TextFieldValue("- item\nand more\n", TextRange(17)))

        assertEquals("- item\nand more\n", multiChar.holder.value.text)
        assertEquals(0, multiChar.continuationInvocations)
    }

    // ── FR-16, EC-18 ─────────────────────────────────────────────────────────

    @Test
    fun `indent and outdent move exactly one unit and keep the selection`() {
        // List line: two spaces, selection shifted by the insertion.
        val list = Harness()
        list.seed("- item", caret = 4)
        val listWritesBefore = list.writes

        list.holder.indent()

        assertEquals("  - item", list.holder.value.text)
        assertEquals(TextRange(6), list.holder.value.selection)
        assertEquals(1, list.writes - listWritesBefore)

        val outdentWritesBefore = list.writes
        list.holder.outdent()

        assertEquals("- item", list.holder.value.text)
        assertEquals(TextRange(4), list.holder.value.selection)
        assertEquals(1, list.writes - outdentWritesBefore)

        // Plain line: one tab.
        val plain = Harness()
        plain.seed("hello", caret = 2)
        val plainWritesBefore = plain.writes

        plain.holder.indent()

        assertEquals("\thello", plain.holder.value.text)
        assertEquals(TextRange(3), plain.holder.value.selection)
        assertEquals(1, plain.writes - plainWritesBefore)

        // Outdent on an unindented line: text unchanged, selection round-trips exactly, and no
        // phantom state write / undo step is produced for a mutation that did not happen.
        val unindented = Harness()
        unindented.seed("hello", caret = 2)
        val unindentedWritesBefore = unindented.writes

        unindented.holder.outdent()

        assertEquals("hello", unindented.holder.value.text)
        assertEquals(TextRange(2), unindented.holder.value.selection)
        assertEquals(0, unindented.writes - unindentedWritesBefore)
        assertEquals(0, unindented.continuationInvocations)
    }

    @Test
    fun `indent is undoable as one step`() {
        val harness = Harness()
        harness.seed("hello", caret = 5)
        harness.holder.indent()
        assertEquals("\thello", harness.holder.value.text)

        harness.holder.undo()

        assertEquals("hello", harness.holder.value.text)
        assertEquals(TextRange(5), harness.holder.value.selection)
    }

    // ── FR-29, EC-22 ─────────────────────────────────────────────────────────

    @Test
    fun `populate resets the caret, signals scroll-to-top and is undoable`() {
        val harness = Harness()
        harness.seed("previous", caret = 8)
        assertEquals(0, harness.holder.scrollSignal)

        harness.holder.populate("hello")

        assertEquals(TextFieldValue("hello", TextRange.Zero), harness.holder.value)
        assertEquals(1, harness.holder.scrollSignal)

        harness.holder.populate("hello again")

        assertEquals(TextFieldValue("hello again", TextRange.Zero), harness.holder.value)
        assertEquals(2, harness.holder.scrollSignal)

        // The pre-populate state was recorded: undo restores it with its selection.
        harness.holder.undo()
        assertEquals(TextFieldValue("hello", TextRange.Zero), harness.holder.value)
        harness.holder.undo()
        assertEquals(TextFieldValue("previous", TextRange(8)), harness.holder.value)

        // A restore is a programmatic write: it must never re-enter the input pipeline.
        assertEquals(0, harness.continuationInvocations)
    }

    @Test
    fun `populating an empty buffer after text is undoable`() {
        val harness = Harness()
        harness.seed("hello", caret = 5)

        harness.holder.populate("")

        assertEquals(TextFieldValue("", TextRange.Zero), harness.holder.value)
        assertFalse(harness.holder.canClear)
        assertFalse(harness.holder.shareEnabled)

        harness.holder.undo()

        assertEquals("hello", harness.holder.value.text)
        assertEquals(TextRange(5), harness.holder.value.selection)
    }

    @Test
    fun `reset empties the buffer and drops both stacks`() {
        val harness = Harness()
        harness.seed("hello", caret = 5)
        harness.holder.undo()
        harness.holder.redo()
        assertTrue("history is populated before the reset.", harness.holder.canUndo || harness.holder.canRedo)

        harness.holder.reset()

        assertEquals(TextFieldValue("", TextRange.Zero), harness.holder.value)
        assertFalse("Clear must reset Undo.", harness.holder.canUndo)
        assertFalse("Clear must reset Redo.", harness.holder.canRedo)
        // A no-op undo is what the disabled button promises: nothing comes back.
        harness.holder.undo()
        assertEquals("", harness.holder.value.text)
        // Nothing to scroll to in an empty buffer — unlike `populate`, which signals it.
        assertEquals(0, harness.holder.scrollSignal)
    }

    // ── FR-31 ────────────────────────────────────────────────────────────────

    @Test
    fun `undo and redo restore text and selection atomically`() {
        val harness = Harness()
        harness.seed("a", caret = 1)
        harness.holder.onInput(TextFieldValue("ab", TextRange(2)))
        assertTrue(harness.holder.canUndo)

        // One contiguous typing run collapses to a single undo step.
        val undoWritesBefore = harness.writes
        harness.holder.undo()

        assertEquals(TextFieldValue("", TextRange.Zero), harness.holder.value)
        assertEquals(1, harness.writes - undoWritesBefore)
        assertFalse(harness.holder.canUndo)
        assertTrue(harness.holder.canRedo)

        val redoWritesBefore = harness.writes
        harness.holder.redo()

        assertEquals(TextFieldValue("ab", TextRange(2)), harness.holder.value)
        assertEquals(1, harness.writes - redoWritesBefore)
        assertTrue(harness.holder.canUndo)
        assertFalse(harness.holder.canRedo)
        assertEquals(0, harness.continuationInvocations)

        // A new edit after an undo empties the redo stack (EC-21).
        harness.holder.undo()
        harness.holder.onInput(TextFieldValue("z", TextRange(1)))

        assertFalse(harness.holder.canRedo)
    }

    // ── EC-30 ────────────────────────────────────────────────────────────────

    @Test
    fun `enablement derives from the shared rules`() {
        val harness = Harness()

        harness.holder.onInput(TextFieldValue("  ", TextRange(2)))
        assertFalse(harness.holder.shareEnabled)
        assertEquals(canShare("  "), harness.holder.shareEnabled)

        harness.holder.onInput(TextFieldValue("  x", TextRange(3)))
        assertTrue(harness.holder.shareEnabled)
        assertEquals(canShare("  x"), harness.holder.shareEnabled)

        // Newlines are trimmed by the shared predicate; the buffer is still non-empty, so Clear
        // stays enabled while Share does not (FR-32 enablement is raw non-emptiness).
        harness.holder.onInput(TextFieldValue("  \n\t ", TextRange(5)))
        assertFalse(harness.holder.shareEnabled)
        assertEquals(canShare("  \n\t "), harness.holder.shareEnabled)
        assertTrue(harness.holder.canClear)
    }
}
