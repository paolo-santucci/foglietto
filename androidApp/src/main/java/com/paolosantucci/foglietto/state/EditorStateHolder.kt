package com.paolosantucci.foglietto.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paolosantucci.foglietto.shared.editor.ContinuationResult
import com.paolosantucci.foglietto.shared.editor.IndentResult
import com.paolosantucci.foglietto.shared.editor.LineIndent
import com.paolosantucci.foglietto.shared.editor.ListContinuation
import com.paolosantucci.foglietto.shared.editor.canShare

/**
 * The ephemeral editor's single source of truth: text plus selection as a Compose
 * [TextFieldValue], with the shared editor rules applied at exactly one boundary (FR-02).
 *
 * ## Ownership
 *
 * The holder owns the buffer, the selection and the undo/redo stack ([history]); it owns no
 * rendering. [value] is Compose snapshot state, so a `BasicTextField` bound to it recomposes on
 * every write. Offsets are UTF-16 code units in both Compose `TextRange` and Kotlin `String`, so
 * the mapping is the identity — surrogate pairs round-trip verbatim.
 *
 * ## Input origin
 *
 * [onInput] is the **IME-origin** entry point only; every programmatic mutation ([indent],
 * [outdent], [populate], [undo], [redo]) writes [value] directly and therefore never re-enters it.
 *
 * ## Enter interception (FR-15)
 *
 * Only a delta that inserts exactly one `"\n"` at a collapsed caret is a continuation candidate.
 * The candidate is resolved by the shared [ListContinuation] rule; a non-null result is applied
 * as ONE [TextFieldValue] write (text + collapsed caret) and the default newline is suppressed.
 * Every other delta passes through untouched, without consulting the shared rule.
 *
 * ## Undo granularity (FR-31)
 *
 * One snapshot per user action: a contiguous typing run records its pre-run state on the first
 * delta and re-records that *same* snapshot on subsequent deltas, which [EditorHistory] treats as
 * a no-op. A continuation, indent/outdent, populate, undo or redo closes the run.
 *
 * ## Enablement
 *
 * Enablement is never a second source of truth: [canUndo]/[canRedo] forward [EditorHistory]'s
 * snapshot-backed flags (so a pure history change invalidates composition), [canClear] follows a
 * non-empty raw buffer (FR-32), and [shareEnabled] is the shared [canShare] predicate (FR-08,
 * EC-30) — never a local trim.
 *
 * Not thread-safe: main-thread confined, like the state it mirrors. Zero `android.app` imports —
 * plain-JUnit Tier A.
 *
 * @param history the bounded undo/redo stack this holder drives.
 * @param state the snapshot-state handle [value] writes through (injected only by tests, which
 *   count assignments at this boundary).
 * @param continuation the shared list-continuation rule; production always passes
 *   [ListContinuation.process].
 */
class EditorStateHolder internal constructor(
    private val history: EditorHistory,
    private val state: MutableState<TextFieldValue>,
    private val continuation: (text: String, caret: Int) -> ContinuationResult?,
) {

    constructor(history: EditorHistory) : this(
        history = history,
        state = mutableStateOf(TextFieldValue("", TextRange.Zero)),
        continuation = ListContinuation::process,
    )

    /** Compose snapshot state: the buffer text and the selection, atomically. */
    var value: TextFieldValue
        get() = state.value
        private set(newValue) {
            state.value = newValue
        }

    /**
     * Restore/clear event counter (FR-29): monotonically increasing, bumped by [populate].
     *
     * A *bumped* value means "a programmatic restore just landed, the caret is at offset 0" — the
     * signal exists so a future editor surface can scroll its viewport to the top on exactly that
     * event, and never on first composition.
     *
     * **Status:** no UI consumer today (TASK-20's `BringIntoViewRequester` seam was removed — see
     * [com.paolosantucci.foglietto.ui.editor.NoteEditorField]). The value-based `BasicTextField`
     * on this Compose version exposes no scroll control, so FR-29's "scroll to top" half is
     * implemented only to the extent the field's own caret-follow does it. The counter stays
     * because it is the holder-side half of the FR-29 contract and is pinned by
     * `EditorStateHolderTest`; it must not be mistaken for a wired-up scroll.
     */
    private val scrollSignalState = mutableIntStateOf(0)

    val scrollSignal: Int
        get() = scrollSignalState.intValue

    val canUndo: Boolean
        get() = history.canUndo

    val canRedo: Boolean
        get() = history.canRedo

    /** Clear is available only for a non-empty raw buffer (FR-32, EC-03). */
    val canClear: Boolean
        get() = value.text.isNotEmpty()

    /** Share is available only when the shared predicate says so (FR-08, EC-30). */
    val shareEnabled: Boolean
        get() = canShare(value.text)

    /** The pre-state of the typing run currently open, or `null` when no run is open. */
    private var typingRunStart: EditorSnapshot? = null

    // ── IME-origin input ─────────────────────────────────────────────────────

    /**
     * Consumes one IME value change: typing-run coalescing plus Enter interception.
     *
     * Never called by the holder itself — programmatic writes go straight to [value].
     */
    fun onInput(newValue: TextFieldValue) {
        val oldValue = value
        if (newValue == oldValue) return

        val continuationResult = continuationCandidate(oldValue, newValue)

        if (continuationResult != null) {
            // A continuation is a user action of its own: close the typing run and record the
            // pre-Enter state, so Undo reverts the Enter before it reverts the typing.
            history.record(snapshotOf(oldValue))
            typingRunStart = null
            write(TextFieldValue(continuationResult.text, TextRange(continuationResult.caret)))
        } else {
            // Re-recording the run-start snapshot is a no-op for the rest of the run.
            val runStart = typingRunStart ?: snapshotOf(oldValue).also { typingRunStart = it }
            history.record(runStart)
            write(newValue)
        }
    }

    // ── Shared line rules ────────────────────────────────────────────────────

    /** Adds exactly one indent unit to every affected line (FR-16). */
    fun indent() {
        applyIndent(LineIndent.indent(value.text, value.selection.start, value.selection.end))
    }

    /** Removes exactly one indent unit where present; a no-op round-trips the selection (FR-16). */
    fun outdent() {
        applyIndent(LineIndent.outdent(value.text, value.selection.start, value.selection.end))
    }

    private fun applyIndent(result: IndentResult) {
        val current = value
        val unchanged = result.text == current.text &&
            result.selStart == current.selection.start &&
            result.selExtent == current.selection.end
        // A no-op result (e.g. outdent on an unindented line) must not produce a phantom undo
        // step or a redundant state write.
        if (unchanged) return

        history.record(snapshotOf(current))
        typingRunStart = null
        write(TextFieldValue(result.text, TextRange(result.selStart, result.selExtent)))
    }

    // ── Restore / clear origin ───────────────────────────────────────────────

    /**
     * Replaces the buffer with [text], caret at zero, and bumps [scrollSignal] (FR-29, EC-22).
     *
     * Used by Recent-notes restore and by Clear: the pre-mutation snapshot is recorded, so the
     * change is undoable.
     */
    fun populate(text: String) {
        history.record(snapshotOf(value))
        typingRunStart = null
        write(TextFieldValue(text, TextRange.Zero))
        scrollSignalState.intValue += 1
    }

    // ── History navigation ───────────────────────────────────────────────────

    /** Restores the previous snapshot in one write; a no-op when there is nothing to undo. */
    fun undo() {
        val restored = history.undo(snapshotOf(value)) ?: return
        typingRunStart = null
        write(restored.toTextFieldValue())
    }

    /** Restores the snapshot parked by [undo] in one write; a no-op when there is nothing. */
    fun redo() {
        val restored = history.redo(snapshotOf(value)) ?: return
        typingRunStart = null
        write(restored.toTextFieldValue())
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun write(newValue: TextFieldValue) {
        state.value = newValue
    }

    private fun snapshotOf(value: TextFieldValue): EditorSnapshot =
        EditorSnapshot(
            text = value.text,
            selStart = value.selection.start,
            selExtent = value.selection.end,
        )

    private fun EditorSnapshot.toTextFieldValue(): TextFieldValue =
        TextFieldValue(text, TextRange(selStart, selExtent))

    /**
     * Resolves the shared list-continuation rule IFF [newValue] inserts exactly one `"\n"` at the
     * collapsed caret of [oldValue]; returns `null` for every other delta shape without touching
     * the shared rule.
     */
    private fun continuationCandidate(
        oldValue: TextFieldValue,
        newValue: TextFieldValue,
    ): ContinuationResult? {
        if (!oldValue.selection.collapsed) return null
        if (newValue.text.length != oldValue.text.length + 1) return null

        val caret = oldValue.selection.start
        if (caret !in 0..oldValue.text.length) return null
        if (newValue.text[caret] != '\n') return null
        if (newValue.text.removeRange(caret, caret + 1) != oldValue.text) return null

        return continuation(oldValue.text, caret)
    }
}
