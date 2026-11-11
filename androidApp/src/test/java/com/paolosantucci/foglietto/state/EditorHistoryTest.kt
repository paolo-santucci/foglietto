package com.paolosantucci.foglietto.state

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — plain JVM, no Robolectric, no Compose (FR-31, EC-20, EC-21, EC-22, OQ-01 assumed).
 *
 * Pins the bounded undo/redo snapshot stack:
 *  - one snapshot per user action: a contiguous typing run records its pre-run state once, while
 *    a programmatic mutation with a different pre-state opens a new snapshot;
 *  - `undo`/`redo` move the current state across the two stacks and restore it verbatim;
 *  - any new record empties the redo stack;
 *  - the cap evicts oldest-first, at an injected capacity (3) and at the default (100);
 *  - empty stacks return `null` and mutate nothing;
 *  - unordered selection anchors (start > extent) are preserved verbatim.
 */
class EditorHistoryTest {

    private fun snap(text: String, start: Int = text.length, extent: Int = text.length) =
        EditorSnapshot(text, selStart = start, selExtent = extent)

    private fun historyOf(vararg snapshots: EditorSnapshot): EditorHistory =
        EditorHistory().apply { snapshots.forEach { record(it) } }

    @Test
    fun `the first record makes undo available`() {
        val history = EditorHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)

        history.record(snap("a"))

        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `cap evicts the oldest snapshot`() {
        // Inject 3 so the eviction is observable; the default is 100.
        val history = EditorHistory(capacity = 3).apply {
            listOf(snap("a"), snap("b"), snap("c"), snap("d")).forEach { record(it) }
        }

        assertEquals(snap("d"), history.undo(snap("e")))
        assertEquals(snap("c"), history.undo(snap("d")))
        assertEquals(snap("b"), history.undo(snap("c")))
        // "a" was evicted by the cap, so the oldest survivor is "b".
        assertNull(history.undo(snap("b")))
    }

    @Test
    fun `cap of one hundred keeps the newest hundred snapshots`() {
        val history = EditorHistory()
        val recorded = (0..100).map { snap("s$it") }
        recorded.forEach(history::record)

        var current = recorded.last()
        for (index in 100 downTo 1) {
            assertEquals(recorded[index], history.undo(current))
            current = recorded[index]
        }
        // s0 is the 101st-oldest and must have been evicted.
        assertNull(history.undo(current))
        assertFalse(history.canUndo)
    }

    @Test
    fun `undo moves the current state onto the redo stack`() {
        val history = historyOf(snap("a"))
        val current = snap("ab")

        assertEquals(snap("a"), history.undo(current))
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        assertEquals(current, history.redo(snap("a")))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `several undos replay in order and redo replays forward`() {
        val history = historyOf(snap("a"), snap("ab"), snap("abc"))

        assertEquals(snap("abc"), history.undo(snap("abcd")))
        assertEquals(snap("ab"), history.undo(snap("abc")))
        assertEquals(snap("a"), history.undo(snap("ab")))
        assertFalse(history.canUndo)

        assertEquals(snap("ab"), history.redo(snap("a")))
        assertEquals(snap("abc"), history.redo(snap("ab")))
        assertEquals(snap("abcd"), history.redo(snap("abc")))
        assertFalse(history.canRedo)
    }

    @Test
    fun `redo re-parks the pre-redo state so the next undo steps back exactly once`() {
        // The classic regression: `redo` parked the snapshot it *restored* on the undo stack
        // instead of the state that was live before the redo. Every flag assertion stayed green —
        // canUndo/canRedo were correct — while the next Undo re-applied the state already on
        // screen and silently consumed a step. In Compose the no-op restore produced no write and
        // no recomposition, so nothing was logged either.
        val history = historyOf(snap("a"))

        assertEquals("undo parks the pre-undo state for redo", snap("a"), history.undo(snap("ab")))
        assertEquals("redo restores it", snap("ab"), history.redo(snap("a")))

        assertEquals(
            "Undo after a redo must return the state that was live before that redo, not the one " +
                "the redo just put on screen.",
            snap("a"),
            history.undo(snap("ab")),
        )
        assertFalse("Exactly one undo step was consumed.", history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun `a redo cycle never accumulates duplicate undo entries`() {
        // Three edits, undone twice and redone twice: the undo stack must still describe exactly
        // the three recorded states. Pushing `restored` instead of `current` leaves a duplicate
        // ("abcd" / "abc" in place of "ab") and one undo step that changes nothing on screen.
        val history = historyOf(snap("a"), snap("ab"), snap("abc"))

        assertEquals(snap("abc"), history.undo(snap("abcd")))
        assertEquals(snap("ab"), history.undo(snap("abc")))
        assertEquals(snap("abc"), history.redo(snap("ab")))
        assertEquals(snap("abcd"), history.redo(snap("abc")))

        // Draining the stack visits every recorded state exactly once, newest first.
        assertEquals(snap("abc"), history.undo(snap("abcd")))
        assertEquals(snap("ab"), history.undo(snap("abc")))
        assertEquals(snap("a"), history.undo(snap("ab")))
        assertNull("No duplicate step remains.", history.undo(snap("a")))
        assertFalse(history.canUndo)
    }

    @Test
    fun `the enablement flags are snapshot state, so a pure history change is observable`() {
        // The top bar reads canUndo/canRedo from composition. A plain `ArrayDeque.isNotEmpty()`
        // read registers no snapshot dependency, so an Undo that changed no text would leave the
        // buttons stale. The cheapest proof that the flags really live in Compose snapshot state is
        // that a *discarded* snapshot isolates a write to them: a plain Boolean would leak out.
        val history = EditorHistory()
        val isolated = Snapshot.takeMutableSnapshot()
        try {
            isolated.enter { history.record(snap("a")) }

            assertFalse(
                "A write inside a discarded snapshot must not be visible globally: canUndo has to " +
                    "be Compose state, not a plain Boolean.",
                history.canUndo,
            )
        } finally {
            isolated.dispose()
        }

        // The same mutation in the global snapshot is visible immediately.
        history.record(snap("b"))
        assertTrue(history.canUndo)
    }

    @Test
    fun `empty stacks return null and mutate nothing`() {
        val history = EditorHistory()
        val current = snap("x")

        assertNull(history.undo(current))
        assertNull(history.redo(current))

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo(current))
        assertNull(history.redo(current))
    }

    @Test
    fun `a new record empties the redo stack`() {
        val history = historyOf(snap("a"), snap("b"))
        assertEquals(snap("b"), history.undo(snap("c")))
        assertTrue(history.canRedo)

        // A new typing run whose pre-state is exactly the state undo just restored: this must be a
        // NEW snapshot, not a continuation of the run that produced "b" before the undo (EC-21).
        history.record(snap("b"))

        assertFalse(history.canRedo)
        assertTrue(history.canUndo)
    }

    @Test
    fun `contiguous typing coalesces, programmatic writes close the run`() {
        val history = EditorHistory()
        val beforeTyping = snap("", start = 0, extent = 0)

        // One user action: three IME deltas, all recording the run's pre-state.
        repeat(3) { history.record(beforeTyping) }

        // The programmatic indent that closes the run records a different pre-state.
        history.record(snap("abc"))

        assertEquals(snap("abc"), history.undo(snap("  abc")))
        assertEquals(beforeTyping, history.undo(snap("abc")))
        assertNull(history.undo(snap("")))
    }

    @Test
    fun `a caret move between deltas opens a new snapshot`() {
        val history = EditorHistory()
        history.record(snap("ab", start = 2, extent = 2))
        history.record(snap("ab", start = 0, extent = 0))

        assertEquals(snap("ab", start = 0, extent = 0), history.undo(snap("xab")))
        assertEquals(snap("ab", start = 2, extent = 2), history.undo(snap("ab", start = 0, extent = 0)))
        assertNull(history.undo(snap("ab", start = 2, extent = 2)))
    }

    @Test
    fun `unordered selection anchors are preserved verbatim`() {
        val history = EditorHistory()
        val reversed = EditorSnapshot("hello", selStart = 4, selExtent = 1)

        history.record(reversed)

        assertEquals(reversed, history.undo(EditorSnapshot("hello!", selStart = 6, selExtent = 6)))
    }

    @Test
    fun `clear empties both stacks`() {
        val history = historyOf(snap("a"), snap("b"))
        assertEquals(snap("b"), history.undo(snap("c")))
        assertTrue(history.canRedo)

        history.clear()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo(snap("c")))
        assertNull(history.redo(snap("b")))
    }
}
