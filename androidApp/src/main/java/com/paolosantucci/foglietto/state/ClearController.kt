package com.paolosantucci.foglietto.state

/**
 * Clear flow adapter (FR-32, FR-30, EC-11): **save → empty + reset history → signal**, in that order.
 *
 * ## Sequence
 *
 *  1. [LifecycleSaveCoordinator.saveNow] writes the current buffer to the recovery store. It
 *     dispatches on the **same** single-thread executor as lifecycle saves, so a Clear save and an
 *     `ON_STOP` save can never interleave (EC-11).
 *  2. [EditorStateHolder.reset] replaces the buffer with `""` **and drops both history stacks**, so
 *     the cleared note cannot be reached through the top bar's Undo: Clear starts a new note, and
 *     the Undo/Redo buttons go back to their disabled resting state. This is the same shape as the
 *     foreground-return reset (EC-23) — a buffer the app replaced on purpose must not sit at the
 *     bottom of the Undo stack.
 *  3. [onCleared] fires **once**, handing the shell a one-shot `restore` action. The Snackbar's
 *     "Undo" calls it: it repopulates the cleared text *and its selection* through
 *     [EditorStateHolder.populate], which records a fresh snapshot (so the restored note has a
 *     normal history again). That one-shot action — not the history stack — is what makes a
 *     mis-tapped Clear recoverable (FR-32).
 *
 * The order matters: the text is saved before it is discarded, so a throwing store leaves the
 * buffer intact (the shared no-swallow contract rethrows from `saveNow` and nothing is lost).
 *
 * ## Gates and one-shot semantics
 *
 * - A buffer that is already empty ([EditorStateHolder.canClear] `== false`) is a complete no-op:
 *   the coordinator is not even asked to snapshot, and no signal fires.
 * - A second [clear] while a signal is still pending — or a re-entrant [clear] from inside
 *   [onCleared] — produces neither a second save nor a second signal. The buffer being empty is
 *   the primary guard; [clearing] additionally covers callbacks that re-enter before the write
 *   has returned.
 *
 * ## No confirmation, ever (FR-30)
 *
 * There is deliberately no "confirm" entry point, no dialog state and no pending-action token:
 * Clear is a single tap, undone by the Snackbar's Undo.
 *
 * ## Presentation-free (Tier A)
 *
 * Zero `androidx.compose.*`, `android.*` or display-string imports. The Snackbar message is
 * resolved by the shell from `R.string.editor_cleared`; nothing here hard-codes user-visible copy,
 * and no `SnackbarHostState` is referenced (the shell owns presentation).
 *
 * Spec refs: FR-32, FR-30, EC-11, EC-22 (revised 2026-11-15: Clear resets the history; the
 * Snackbar's Undo is a one-shot restore rather than a pop off the undo stack).
 *
 * @param editor the single source of truth for text, selection and the undo stack.
 * @param coordinator the save seam shared with the process-lifecycle observer.
 * @param onCleared one-shot signal carrying the restore action: the shell posts the localized
 *   "Editor cleared" Snackbar and binds its Undo to it.
 */
class ClearController(
    private val editor: EditorStateHolder,
    private val coordinator: LifecycleSaveCoordinator,
    private val onCleared: (restore: () -> Unit) -> Unit,
) {

    /** Re-entrancy guard: a `clear()` issued from inside [onCleared] must not save twice. */
    private var clearing: Boolean = false

    /**
     * Saves the raw buffer, empties the editor (dropping the history) and signals the Snackbar —
     * or does nothing at all when the buffer is empty.
     */
    fun clear() {
        if (clearing || !editor.canClear) return

        clearing = true
        try {
            // 1. Persist the RAW, un-trimmed buffer on the shared single-thread executor (EC-11).
            //    A throwing store propagates here with the buffer still intact.
            coordinator.saveNow()
            // 2. Capture what the user had, then empty the buffer and reset Undo/Redo (FR-32).
            val cleared = editor.value
            editor.reset()
            // 3. One-shot signal — the shell resolves the message and owns the Snackbar; its Undo
            //    calls this restore, which is the *only* way back to the cleared text.
            onCleared { editor.populate(cleared.text, cleared.selection) }
        } finally {
            clearing = false
        }
    }
}
