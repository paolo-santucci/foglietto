package com.paolosantucci.foglietto

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paolosantucci.foglietto.shared.editor.ListContinuation
import com.paolosantucci.foglietto.shared.recovery.RecoveryNote
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository
import com.paolosantucci.foglietto.shared.recovery.SaveNoteToRecovery
import com.paolosantucci.foglietto.state.EditorHistory
import com.paolosantucci.foglietto.state.EditorStateHolder
import com.paolosantucci.foglietto.state.LifecycleSaveCoordinator
import com.paolosantucci.foglietto.state.RecoveryListStateHolder
import com.paolosantucci.foglietto.ui.share.ShareLauncher
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tier B (Robolectric — `buildAppContainer` needs a real `Context` for `ShareLauncher`) contract
 * suite for the inbound-share ordering of the process graph — plan TASK-07, spec §5.1.8, §7.1
 * lines 787-799.
 *
 * The invariant under test is the *ordering* around the pending slot:
 *
 *  - a payload accepted while the process is backgrounded is **stashed**, never applied early
 *    (FR-23, EC-21);
 *  - the foreground return resets the buffer **first** and seeds the payload **after** (FR-23);
 *  - a foreground share is save-then-populate, so the text being replaced is recoverable (FR-24,
 *    EC-22);
 *  - a failed collision save keeps both the buffer and the payload (FR-07, EC-10);
 *  - a payload never survives process death (EC-24), and a seed is a normal undoable edit (FR-08,
 *    EC-25).
 *
 * One freshly built graph per case is one process lifetime: nothing is shared between tests, and
 * the save executor is the injected direct executor, so every seam resolves synchronously (no
 * sleeps, no real I/O). Counting is done on *observable* boundaries only: recovery writes on
 * [RecordingRecoveryRepository] and populate calls on `EditorStateHolder.scrollSignal` (bumped
 * exactly once per `populate`, FR-29) — never on holder internals.
 *
 * Spec refs: FR-05, FR-06, FR-07, FR-08, FR-09, FR-10, FR-23, FR-24, NFR-05; §5.1.8, §7.1,
 * D5, C-1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppContainerShareTest {

    @Test
    fun `a cold-start share is stashed until ON_START and then seeded once`() {
        val recovery = RecordingRecoveryRepository()
        val container = container(recovery = recovery)

        container.acceptInboundText("a\r\nb")

        // EC-20: nothing may be applied before the process reports itself foregrounded.
        assertEquals("the buffer must stay empty while stashed", "", container.editor.value.text)
        assertTrue("a stashed payload must not save", recovery.attempts.isEmpty())
        assertEquals("a stashed payload must not populate", 0, container.editor.scrollSignal)

        container.onProcessStarted()

        // The shared rule normalises CRLF; the container adds nothing (NFR-05).
        assertEquals("a\nb", container.editor.value.text)
        assertTrue("the pre-seed buffer was empty, so the trim guard suppresses the save", recovery.attempts.isEmpty())
        assertEquals(
            "exactly two populates: the ON_START reset, then the seed",
            2,
            container.editor.scrollSignal,
        )

        // The slot is consumed by the seed: a later foreground return seeds nothing.
        container.onProcessStarted()
        assertEquals("", container.editor.value.text)
        assertEquals("only the reset populate runs", 3, container.editor.scrollSignal)
    }

    @Test
    fun `a stashed payload never reaches the buffer before ON_START`() {
        val recovery = RecordingRecoveryRepository()
        val container = container(recovery = recovery)

        container.acceptInboundText("stashed")

        assertEquals("", container.editor.value.text)
        assertTrue("no save while backgrounded", recovery.attempts.isEmpty())
        assertEquals("no populate while backgrounded", 0, container.editor.scrollSignal)
        assertEquals("the stash must not become an undoable edit", false, container.editor.canUndo)
    }

    @Test
    fun `a warm share into a backgrounded process seeds only after the reset`() {
        val recovery = RecordingRecoveryRepository()
        val container = container(recovery = recovery)
        container.onProcessStarted()
        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        container.onProcessStopped()
        assertEquals("the draft is recovered on the way out", listOf("draft"), recovery.savedTexts)

        container.acceptInboundText("new")
        assertEquals("still stashed while backgrounded", "draft", container.editor.value.text)

        val populatesBeforeSeed = container.editor.scrollSignal
        container.onProcessStarted()

        // EC-21: reset -> seed, and the reset made the collision save a trim-guard no-op.
        assertEquals("new", container.editor.value.text)
        assertEquals("exactly one save in total", listOf("draft"), recovery.attempts)
        assertEquals(
            "reset + seed = exactly two populates after the stash (one of them the seed)",
            populatesBeforeSeed + 2,
            container.editor.scrollSignal,
        )
    }

    @Test
    fun `a foreground share saves the pre-seed buffer and then seeds it`() {
        // The effect trace needs the *programmatic write* to be observable, so this case runs on
        // the recording harness (the production graph's editor writes through an internal handle).
        val effects = mutableListOf<String>()
        val recovery = RecordingRecoveryRepository(effects = effects)
        val container = effectsContainer(recovery, effects)
        container.onProcessStarted()
        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        effects.clear() // the share's effects only
        container.acceptInboundText("new")

        // EC-22 / FR-24: save first (so the replaced text is recoverable), then seed.
        // A `buffer:` event is one programmatic `TextFieldValue` write; the apply path's only
        // writer is `EditorStateHolder.populate`, identified by the caret-zero write asserted
        // below plus the single scroll-signal bump.
        assertEquals(listOf("save:draft", "buffer:new@0"), effects)
        assertEquals("new", container.editor.value.text)
        assertEquals("the seed parks the caret at zero", TextRange.Zero, container.editor.value.selection)
        assertEquals("exactly one populate for the share", 2, container.editor.scrollSignal)

        // The pending slot was cleared: the next foreground return only resets.
        effects.clear()
        container.onProcessStarted()
        assertEquals(listOf("buffer:@0"), effects)
    }

    @Test
    fun `a foreground share over an empty buffer makes no repository write`() {
        val recovery = RecordingRecoveryRepository()
        val container = container(recovery = recovery)
        container.onProcessStarted()

        container.acceptInboundText("new")

        // EC-12: the shared trim guard suppresses the save of the empty pre-seed buffer.
        assertTrue("no repository write for an empty pre-seed buffer", recovery.attempts.isEmpty())
        assertEquals("the seed itself still happens", "new", container.editor.value.text)
        assertEquals("reset + seed populates", 2, container.editor.scrollSignal)
    }

    @Test
    fun `a blank payload is stashed as nothing`() {
        val recovery = RecordingRecoveryRepository()
        val container = container(recovery = recovery)

        container.acceptInboundText(" \r\n ")

        assertEquals("", container.editor.value.text)
        assertTrue("a blank payload never saves", recovery.attempts.isEmpty())
        assertEquals("a blank payload never populates", 0, container.editor.scrollSignal)

        container.onProcessStarted()

        // EC-02: the slot collapsed to null, so only the reset populate runs.
        assertEquals("", container.editor.value.text)
        assertEquals(1, container.editor.scrollSignal)
        assertTrue(recovery.attempts.isEmpty())
    }

    @Test
    fun `only the latest payload wins`() {
        // EC-03: A then B while backgrounded — B is the one that lands.
        val firstRecovery = RecordingRecoveryRepository()
        val first = container(recovery = firstRecovery)
        first.acceptInboundText("A")
        first.acceptInboundText("B")
        first.onProcessStarted()
        assertEquals("B", first.editor.value.text)

        // EC-04: A then a blank — latest wins, so nothing is seeded at all.
        val secondRecovery = RecordingRecoveryRepository()
        val second = container(recovery = secondRecovery)
        second.acceptInboundText("A")
        second.acceptInboundText("  ")
        second.onProcessStarted()
        assertEquals("", second.editor.value.text)
        assertEquals("only the reset populate runs", 1, second.editor.scrollSignal)
        assertTrue("nothing was saved", secondRecovery.attempts.isEmpty())
    }

    @Test
    fun `a failing collision save keeps the buffer and the payload`() {
        val failure = IOException("recovery store unavailable")
        val recovery = RecordingRecoveryRepository(failure = failure)
        val container = container(recovery = recovery)
        container.onProcessStarted()
        container.editor.onInput(TextFieldValue("draft", TextRange(5)))

        // FR-07 / EC-10: the share never crashes, never loses the buffer and never loses the text.
        container.acceptInboundText("new")

        assertEquals("the buffer must stay intact", "draft", container.editor.value.text)
        assertEquals("the failed write was attempted exactly once", listOf("draft"), recovery.attempts)
        assertTrue("the write did not land", recovery.savedTexts.isEmpty())
        assertEquals("nothing was populated", 1, container.editor.scrollSignal)

        // The payload was retained: the next foreground return seeds it (its reset leaves the
        // buffer empty, so the collision save is a trim-guard no-op and cannot fail again).
        container.onProcessStarted()
        assertEquals("new", container.editor.value.text)
        assertEquals("no second repository write", listOf("draft"), recovery.attempts)
    }

    @Test
    fun `a share arriving during the background transition stays stashed`() {
        // FR-23: the foreground flag flips BEFORE the save, so a payload accepted from inside the
        // ON_STOP seam (here: from the store's `save`, which the direct executor runs on the
        // caller) must be stashed rather than applied over the text being written.
        val once = AtomicBoolean(false)
        lateinit var graph: AppContainer
        val recovery = RecordingRecoveryRepository(
            beforeSave = {
                if (once.compareAndSet(false, true)) graph.acceptInboundText("late")
            },
        )
        graph = container(recovery = recovery)
        graph.onProcessStarted()
        graph.editor.onInput(TextFieldValue("draft", TextRange(5)))

        graph.onProcessStopped()

        assertEquals("the ON_STOP save still writes the draft", listOf("draft"), recovery.savedTexts)
        assertEquals("the mid-transition share must not be applied", "draft", graph.editor.value.text)

        graph.onProcessStarted()
        assertEquals("late", graph.editor.value.text)
    }

    @Test
    fun `a pending payload does not survive process death`() {
        val first = container()
        first.acceptInboundText("lost with the process")

        // A second graph is a second process: the slot is memory-only (EC-24).
        val secondRecovery = RecordingRecoveryRepository()
        val second = container(recovery = secondRecovery)
        second.onProcessStarted()

        assertEquals("", second.editor.value.text)
        assertEquals("only the reset populate runs", 1, second.editor.scrollSignal)
        assertTrue(secondRecovery.attempts.isEmpty())
    }

    @Test
    fun `a seed is an ordinary undoable edit`() {
        // EC-25: a foreground seed over a draft restores the draft on Undo (nothing is lost).
        val foreground = container()
        foreground.onProcessStarted()
        foreground.editor.onInput(TextFieldValue("draft", TextRange(5)))
        foreground.acceptInboundText("new")
        assertEquals("new", foreground.editor.value.text)

        foreground.editor.undo()
        assertEquals("draft", foreground.editor.value.text)

        // A cold-start seed over an empty buffer undoes back to empty.
        val coldStart = container()
        coldStart.acceptInboundText("seeded")
        coldStart.onProcessStarted()
        assertEquals("seeded", coldStart.editor.value.text)

        coldStart.editor.undo()
        assertEquals("", coldStart.editor.value.text)
    }
}

// ── Recording doubles shared with ProcessLifecycleWiringTest (same package) ─────────────────────

/**
 * Recovery store double that records every write as `save:<raw>` into [effects], so one ordered
 * list can hold a whole seam's effect sequence (the ordering assertions).
 *
 * @param effects the shared effect trace; the same list is handed to [RecordingState], so recovery
 *   writes and programmatic buffer writes interleave in call order.
 * @param failure thrown from every [save] — the collision-save failure path (FR-07, EC-10).
 * @param beforeSave runs *inside* the save, i.e. inside the ON_STOP seam while the direct executor
 *   is still on the caller: the re-entrancy hook (FR-23).
 */
internal class RecordingRecoveryRepository(
    val effects: MutableList<String> = mutableListOf(),
    private val failure: Exception? = null,
    private val beforeSave: () -> Unit = {},
) : RecoveryRepository {

    /** Writes that landed, newest last. */
    val savedTexts = mutableListOf<String>()

    /** Every `save` entry, including the ones [failure] aborted. */
    val attempts = mutableListOf<String>()

    override fun save(text: String): String {
        attempts += text
        failure?.let { throw it }
        savedTexts += text
        effects += "save:$text"
        beforeSave()
        return "/recovery/${savedTexts.size}.txt"
    }

    override fun list(): List<RecoveryNote> = emptyList()

    override fun read(path: String): String? = null

    override fun delete(path: String) = Unit

    override fun deleteAll() = Unit

    override fun trim(keep: Int) = Unit
}

/**
 * [MutableState] decorator that records every assignment to [value] as `buffer:<text>@<caret>`.
 *
 * Same idiom as `EditorStateHolderTest.CountingState`: the holder writes through this handle
 * exactly like it writes through the state it builds by default, so the trace observes real
 * snapshot writes instead of holder internals. It cannot tell *which* method wrote (IME input,
 * `populate`, reset), so the caret is part of the event and the reading test asserts the caller's
 * signature (`populate` writes caret zero) alongside it.
 */
internal class RecordingState(
    private val effects: MutableList<String>,
    private val delegate: MutableState<TextFieldValue> = mutableStateOf(
        TextFieldValue("", TextRange.Zero),
    ),
) : MutableState<TextFieldValue> by delegate {

    override var value: TextFieldValue
        get() = delegate.value
        set(newValue) {
            effects += "buffer:${newValue.text}@${newValue.selection.start}"
            delegate.value = newValue
        }
}

/**
 * The production graph with the editor's state handle injected, so programmatic writes land in
 * [effects]. Mirrors `buildAppContainer`'s wiring exactly (one repository, one use case, one
 * coordinator on the direct executor); only the `TextFieldValue` handle differs, which is the seam
 * `EditorStateHolderTest` already uses.
 */
internal fun effectsContainer(
    recovery: RecoveryRepository,
    effects: MutableList<String>,
): AppContainer {
    val history = EditorHistory()
    val editor = EditorStateHolder(history, RecordingState(effects), ListContinuation::process)
    return AppContainer(
        recovery = recovery,
        settings = FakeSettingsRepository(),
        editor = editor,
        recoveryList = RecoveryListStateHolder(recovery) { text -> editor.populate(text) },
        coordinator = LifecycleSaveCoordinator(
            save = SaveNoteToRecovery(recovery),
            snapshotText = { editor.value.text },
            resetNote = { text -> editor.populate(text) },
            executor = DirectExecutorService(),
        ),
        share = ShareLauncher(RuntimeEnvironment.getApplication()),
        history = history,
    )
}
