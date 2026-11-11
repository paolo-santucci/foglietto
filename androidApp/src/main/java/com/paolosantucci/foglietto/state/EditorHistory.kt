package com.paolosantucci.foglietto.state

import androidx.compose.runtime.mutableStateOf

/**
 * Bounded undo/redo snapshot history for the ephemeral editor buffer (FR-31, EC-20, EC-21, OQ-01).
 *
 * Steps store the state *before* a mutation, so [undo] returns the previous state and [redo]
 * returns the state that was live when [undo] ran. Restoring is a single value hand-off — the
 * caller writes text and selection atomically.
 *
 * Run model — one snapshot per user action:
 *  - Callers record the pre-mutation state. A contiguous IME typing run therefore passes the *same*
 *    run-start snapshot on every delta; recording a snapshot equal to the currently open run is a
 *    no-op, so the whole run contributes exactly one undo step.
 *  - Any programmatic mutation whose pre-state differs (list continuation, indent/outdent, restore,
 *    Clear) closes the run and records a new step. A caret/selection change also yields a different
 *    snapshot and therefore a new step.
 *  - [undo], [redo] and [clear] close the open run: a later [record] always pushes a new step even
 *    when its pre-state equals the snapshot that was just restored (EC-21).
 *
 * Any recorded step empties the redo stack. The cap evicts oldest-first. An empty stack returns
 * `null` and mutates nothing. Not thread-safe: main-thread confined, like the editor state it
 * mirrors.
 *
 * ## Observability
 *
 * [canUndo]/[canRedo] are Compose snapshot state refreshed by every mutation, not plain reads over
 * the deques. The top bar reads them from composition, and an `ArrayDeque.isNotEmpty()` read is
 * invisible to the snapshot system — a pure history change (Undo after a programmatic write, when
 * no text changed in between) would then leave the buttons stale. The deques stay the single source
 * of truth; [syncEnablement] only mirrors them.
 */
class EditorHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "EditorHistory capacity must be positive, was $capacity" }
    }

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    // Snapshot-backed so composition observes a pure history change (see "Observability" above).
    private val canUndoState = mutableStateOf(false)
    private val canRedoState = mutableStateOf(false)

    /** The pre-state of the typing run currently open, or `null` when no run is open. */
    private var openRun: EditorSnapshot? = null

    val canUndo: Boolean
        get() = canUndoState.value

    val canRedo: Boolean
        get() = canRedoState.value

    /** Records the pre-mutation state of a new user action, or continues the open typing run. */
    fun record(snapshot: EditorSnapshot) {
        if (snapshot == openRun) return

        undoStack.addLast(snapshot)
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
        openRun = snapshot
        syncEnablement()
    }

    /** Pops the previous state, parks [current] for [redo], and returns the restored snapshot. */
    fun undo(current: EditorSnapshot): EditorSnapshot? {
        val restored = undoStack.removeLastOrNull() ?: return null

        redoStack.addLast(current)
        openRun = null
        syncEnablement()
        return restored
    }

    /** Pops the state [undo] parked, re-parks [current], and returns the restored snapshot. */
    fun redo(current: EditorSnapshot): EditorSnapshot? {
        val restored = redoStack.removeLastOrNull() ?: return null

        // The undo stack must receive the state live *before* the redo (what the next Undo has to
        // restore), not `restored` — the state the redo is about to put on screen. Pushing
        // `restored` makes the following Undo a no-op that silently consumes a step.
        undoStack.addLast(current)
        openRun = null
        syncEnablement()
        return restored
    }

    /** Drops both stacks — the foreground-return reset (EC-23). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        openRun = null
        syncEnablement()
    }

    /** Mirrors the deques into the snapshot-backed enablement flags. */
    private fun syncEnablement() {
        canUndoState.value = undoStack.isNotEmpty()
        canRedoState.value = redoStack.isNotEmpty()
    }

    companion object {
        /** OQ-01 assumed bound: the stack holds at most this many snapshots. */
        const val DEFAULT_CAPACITY = 100
    }
}
