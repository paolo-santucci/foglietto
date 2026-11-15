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
import com.paolosantucci.foglietto.shared.editor.normalizeLineEndings

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
 * [outdent], [pasteAtCaret], [populate], [undo], [redo]) writes [value] directly and therefore never
 * re-enters it.
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
 * a no-op. A continuation, indent/outdent, paste, populate, undo or redo closes the run.
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

        // A change that leaves the text identical is a caret move — including the selection sync a
        // programmatic write (paste, indent, restore) provokes in the bound field. It mutates no
        // content, so it must NOT open an undo step: recording one parks a snapshot whose text is
        // the current text, and the next Undo then restores the same text and reads as a dead
        // button (the paste-then-undo case). It does end the coalescing run, so typing after a
        // deliberate caret move still records its own step.
        if (newValue.text == oldValue.text) {
            typingRunStart = null
            write(newValue)
            return
        }

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

    // ── Clipboard origin ─────────────────────────────────────────────────────

    /**
     * Inserts clipboard text at the caret, replacing the current selection — the Android half of
     * iOS's `pasteAtCaret()`.
     *
     * ## Rules, in order
     *
     *  - **Empty is a no-op.** Empty (or normalization-empty) text writes nothing: no state write,
     *    no undo step, no history flag change (EC-03).
     *  - **Line endings are normalized first** through the shared `normalizeLineEndings`, never a
     *    local `replace("\r\n", "\n")`. The continuation and indent rules only understand `"\n"`,
     *    so a pasted CRLF note would otherwise produce empty numbered items and outdent drift —
     *    the same rule an inbound share applies, minus its blank rejection (pasting spaces pastes
     *    spaces).
     *  - **ONE undo step.** The pre-paste snapshot is recorded, the typing run is closed, then the
     *    buffer is written in a single [TextFieldValue]: one Undo reverts the paste and nothing
     *    else.
     *  - **The caret lands after the pasted text.** Offsets are UTF-16 code units in both Compose
     *    `TextRange` and Kotlin `String`, so the mapping is the identity — a surrogate pair counts
     *    as the two units it occupies.
     *  - **Never re-enters [onInput].** A pasted `"\n"` must not trigger list continuation: a paste
     *    is a programmatic write, exactly like indent or a restore.
     *
     * The selection is clamped to the buffer: an IME can hand over a range that no longer matches
     * the text it describes, and a paste must never be the thing that throws an index exception.
     *
     * @param raw the clipboard text, verbatim; `null` clipboard contents never reach this method.
     */
    fun pasteAtCaret(raw: String) {
        val pasted = normalizeLineEndings(raw)
        if (pasted.isEmpty()) return

        val current = value
        val text = current.text
        val selStart = minOf(current.selection.start, current.selection.end).coerceIn(0, text.length)
        val selEnd = maxOf(current.selection.start, current.selection.end).coerceIn(selStart, text.length)

        val newText = buildString(text.length - (selEnd - selStart) + pasted.length) {
            append(text, 0, selStart)
            append(pasted)
            append(text, selEnd, text.length)
        }

        history.record(snapshotOf(current))
        typingRunStart = null
        write(TextFieldValue(newText, TextRange(selStart + pasted.length)))
    }

    // ── Restore / clear origin ───────────────────────────────────────────────

    /**
     * Replaces the buffer with [text], caret at zero, and bumps [scrollSignal] (FR-29, EC-22).
     *
     * Used by Recent-notes restore, inbound-share seeding and the Snackbar's one-shot restore of a
     * Clear: the pre-mutation snapshot is recorded, so the change is undoable.
     *
     * @param selection where the caret (or selection) lands; [TextRange.Zero] for a restore, which
     *   is FR-29's caret reset. The Clear restore passes the selection the buffer had before it was
     *   emptied, so the user's caret comes back with the text.
     */
    fun populate(text: String, selection: TextRange = TextRange.Zero) {
        history.record(snapshotOf(value))
        typingRunStart = null
        write(TextFieldValue(text, selection))
        scrollSignalState.intValue += 1
    }

    /**
     * Clear's second half (FR-32): empties the buffer **and drops both history stacks**, so the
     * note that was just cleared can never be resurrected through Undo — Clear starts a new note,
     * and the way back is the recovery store (or the Snackbar's one-shot restore).
     *
     * This is the same "empty + drop history" shape as the foreground-return reset in
     * [com.paolosantucci.foglietto.AppContainer.onProcessStarted]: a buffer the app replaced on
     * purpose must not sit at the bottom of the Undo stack, waiting to resurface hours later.
     *
     * [scrollSignal] is deliberately not bumped: the buffer is empty, so there is nothing to
     * scroll to.
     */
    fun reset() {
        history.clear()
        typingRunStart = null
        write(TextFieldValue("", TextRange.Zero))
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
        if (!oldValue.selection.collapsed || !newValue.selection.collapsed) return null
        val oldCaret = oldValue.selection.start
        if (oldCaret !in 0..oldValue.text.length) return null
        val newCaret = newValue.selection.start
        if (newCaret !in 1..newValue.text.length) return null

        // Where the line break landed. After an IME commit it sits immediately before the caret;
        // a keyboard that leaves the caret on the break is covered by the old caret.
        val breakEnd = when {
            newValue.text[newCaret - 1].isLineBreak() -> newCaret
            oldCaret < newValue.text.length && newValue.text[oldCaret].isLineBreak() -> oldCaret + 1
            else -> return null
        }
        // A CRLF pair is one break, not a break preceded by a stray carriage return.
        val breakStart = if (breakEnd >= 2 && newValue.text[breakEnd - 1] == '\n' &&
            newValue.text[breakEnd - 2] == '\r'
        ) breakEnd - 2 else breakEnd - 1

        // Everything after the break must be untouched: an Enter inserts, it never rewrites the
        // tail of the buffer.
        if (newValue.text.substring(breakEnd) != oldValue.text.substring(oldCaret)) return null

        val candidateText = newValue.text.removeRange(breakStart, breakEnd)
        // The IME may also commit an auto-correction with the Enter (the "- mill" → "- milk"
        // case); that is at most a character or two. Anything bigger is a paste or a sentence
        // completion, not an Enter, and passes through untouched.
        val growth = candidateText.length - oldValue.text.length
        if (growth !in 0..MAXIMUM_COMMIT_GROWTH) return null

        return continuation(candidateText, breakStart)
    }

    /** `\n`, a CRLF's `\r`, or the Unicode line separator some IMEs send for Enter. */
    private fun Char.isLineBreak(): Boolean = this == '\n' || this == '\r' || this == '\u2028'

    private companion object {
        /** Characters an IME may auto-correct while it commits the Enter. */
        const val MAXIMUM_COMMIT_GROWTH = 2
    }
}
