package com.paolosantucci.foglietto.state

/**
 * Clear flow adapter (FR-32, FR-30, EC-11, EC-22): **save → empty → signal**, in that order.
 *
 * ## Sequence
 *
 *  1. [LifecycleSaveCoordinator.saveNow] writes the current buffer to the recovery store. It
 *     dispatches on the **same** single-thread executor as lifecycle saves, so a Clear save and an
 *     `ON_STOP` save can never interleave (EC-11).
 *  2. [EditorStateHolder.populate] replaces the buffer with `""`. That call records the pre-clear
 *     snapshot, so the clear is undoable from **both** the Snackbar's Undo action and the top-bar
 *     Undo (EC-22) — this class adds no history of its own.
 *  3. [onCleared] fires **once**, so the shell can post the "Editor cleared" Snackbar and bind its
 *     Undo action to [EditorStateHolder.undo].
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
 * Clear is a single tap, undone by Undo.
 *
 * ## Presentation-free (Tier A)
 *
 * Zero `androidx.compose.*`, `android.*` or display-string imports. The Snackbar message is
 * resolved by the shell from `R.string.editor_cleared`; nothing here hard-codes user-visible copy,
 * and no `SnackbarHostState` is referenced (the shell owns presentation).
 *
 * Spec refs: FR-32, FR-30, EC-11, EC-22.
 *
 * @param editor the single source of truth for text, selection and the undo stack.
 * @param coordinator the save seam shared with the process-lifecycle observer.
 * @param onCleared one-shot signal: post the localized "Editor cleared" Snackbar with an Undo
 *   action bound to [EditorStateHolder.undo].
 */
class ClearController(
    private val editor: EditorStateHolder,
    private val coordinator: LifecycleSaveCoordinator,
    private val onCleared: () -> Unit,
) {

    /** Re-entrancy guard: a `clear()` issued from inside [onCleared] must not save twice. */
    private var clearing: Boolean = false

    /**
     * Saves the raw buffer, empties the editor and signals the Snackbar — or does nothing at all
     * when the buffer is empty.
     */
    fun clear() {
        if (clearing || !editor.canClear) return

        clearing = true
        try {
            // 1. Persist the RAW, un-trimmed buffer on the shared single-thread executor (EC-11).
            //    A throwing store propagates here with the buffer still intact.
            coordinator.saveNow()
            // 2. Empty the editor; `populate` records the pre-clear snapshot itself (EC-22).
            editor.populate("")
            // 3. One-shot signal — the shell resolves the localized message and the Undo action.
            onCleared()
        } finally {
            clearing = false
        }
    }
}
