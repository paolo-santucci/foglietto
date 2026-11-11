package com.paolosantucci.foglietto

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paolosantucci.foglietto.shared.recovery.RecoveryNote
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository
import com.paolosantucci.foglietto.shared.settings.AppSettings
import com.paolosantucci.foglietto.shared.settings.SettingsRepository
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tier B (Robolectric — a real `Context` is required for [ShareLauncher]) contract suite for the
 * composition root's object graph ([AppContainer] + `buildAppContainer`).
 *
 * Pins plan TASK-18 / spec §7.1 rows 734-735 at the container seam (the process-lifecycle
 * *observer* is covered by [ProcessLifecycleWiringTest]):
 *
 *  - [contextInitPrecedesEverySharedFactoryConstruction] — FR-04, EC-06: `AndroidContext.init` runs
 *    before either shared factory, and each factory runs exactly once per graph (one recovery +
 *    one settings instance per process, §4.3).
 *  - [theGraphKeepsOneRecoveryRepositoryBehindEveryConsumer] — FR-06: the same `RecoveryRepository`
 *    instance is the one the save path writes through; no per-caller copies.
 *  - [foregroundReturnEmptiesTheBufferAndClearsBothHistoryStacks] — EC-23: ON_START is a reset, not
 *    an undoable edit; Undo cannot resurrect backgrounded text.
 *  - [aColdStartForegroundReturnOnAnEmptyBufferIsANoOp] — EC-15.
 *  - [backgroundingSavesTheRawTextOncePerEpisode] — EC-09 at the graph seam.
 *
 * Determinism: the save executor is injected ([DirectExecutorService]) so every seam is
 * synchronous; no test sleeps and no real thread is created by this suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppContainerTest {

    @Test
    fun `context init precedes every shared factory construction`() {
        val events = mutableListOf<String>()
        val recovery = FakeRecoveryRepository()
        val settings = FakeSettingsRepository()

        val container = buildAppContainer(
            context = RuntimeEnvironment.getApplication(),
            factories = CompositionFactories(
                initAndroidContext = { events += INIT },
                recovery = {
                    events += RECOVERY
                    recovery
                },
                settings = {
                    events += SETTINGS
                    settings
                },
                executor = DirectExecutorService(),
            ),
        )

        // FR-04 / EC-06: the platform seam is initialised before any shared factory resolves
        // `filesDir` or the default SharedPreferences file.
        assertEquals("AndroidContext.init must run first", INIT, events.first())
        assertTrue(
            "init must precede the recovery factory, was $events",
            events.indexOf(INIT) < events.indexOf(RECOVERY),
        )
        assertTrue(
            "init must precede the settings factory, was $events",
            events.indexOf(INIT) < events.indexOf(SETTINGS),
        )
        // One instance of each per graph — the composition root is the only construction site.
        assertEquals("exactly one recovery repository per graph", 1, events.count { it == RECOVERY })
        assertEquals("exactly one settings repository per graph", 1, events.count { it == SETTINGS })
        assertSame(recovery, container.recovery)
        assertSame(settings, container.settings)
    }

    @Test
    fun `the graph keeps one recovery repository behind every consumer`() {
        val recovery = FakeRecoveryRepository()
        val container = container(recovery = recovery)

        container.editor.onInput(TextFieldValue("  draft  ", TextRange(9)))
        container.onProcessStopped()

        // The save path writes through the very instance exposed on the graph — FR-06.
        assertEquals(listOf("  draft  "), recovery.savedTexts)
    }

    @Test
    fun `foreground return empties the buffer and clears both history stacks`() {
        val container = container()
        container.editor.onInput(TextFieldValue("typed", TextRange(5)))
        container.editor.populate("restored from recent notes")
        container.editor.undo() // leaves the restored text on the redo stack

        assertTrue("precondition: the buffer must be undoable", container.editor.canUndo)
        assertTrue("precondition: the buffer must be redoable", container.editor.canRedo)

        container.onProcessStarted()

        assertEquals("", container.editor.value.text)
        assertFalse("the undo stack must be cleared (EC-23)", container.editor.canUndo)
        assertFalse("the redo stack must be cleared (EC-23)", container.editor.canRedo)

        // EC-23: Undo must not resurrect backgrounded text — it is reachable via Recent notes.
        container.editor.undo()
        assertEquals("", container.editor.value.text)
    }

    @Test
    fun `a cold-start foreground return on an empty buffer is a no-op`() {
        val recovery = FakeRecoveryRepository()
        val container = container(recovery = recovery)

        container.onProcessStarted()

        assertEquals("", container.editor.value.text)
        assertFalse(container.editor.canUndo)
        assertFalse(container.editor.canRedo)
        assertEquals(0, recovery.saveInvocations)
    }

    @Test
    fun `backgrounding saves the raw text once per episode`() {
        val recovery = FakeRecoveryRepository()
        val container = container(recovery = recovery)
        container.editor.onInput(TextFieldValue("  hello  ", TextRange(9)))

        container.onProcessStopped()
        container.onProcessStopped() // burst: the episode is already spent

        assertEquals(listOf("  hello  "), recovery.savedTexts)

        container.onProcessStarted()
        container.editor.onInput(TextFieldValue("second episode", TextRange(0)))
        container.onProcessStopped()

        assertEquals(listOf("  hello  ", "second episode"), recovery.savedTexts)
    }

    private companion object {
        const val INIT = "init"
        const val RECOVERY = "recovery"
        const val SETTINGS = "settings"
    }
}

// ── Test doubles shared with ProcessLifecycleWiringTest (same package) ──────────────────────────

/**
 * Runs every submitted task on the caller thread, so the coordinator's `submit` + bounded `get`
 * resolves synchronously and deterministically (no sleeps, no extra thread).
 */
internal class DirectExecutorService : AbstractExecutorService() {

    private var stopped = false

    override fun execute(command: Runnable) = command.run()

    override fun shutdown() {
        stopped = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        stopped = true
        return mutableListOf()
    }

    override fun isShutdown(): Boolean = stopped

    override fun isTerminated(): Boolean = stopped

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
}

/** Records every save, newest last; `list`/`read` stay empty so no restore path intervenes. */
internal class FakeRecoveryRepository : RecoveryRepository {

    val savedTexts = mutableListOf<String>()

    val saveInvocations: Int get() = savedTexts.size

    override fun save(text: String): String {
        savedTexts += text
        return "/recovery/${savedTexts.size}.txt"
    }

    override fun list(): List<RecoveryNote> = emptyList()

    override fun read(path: String): String? = null

    override fun delete(path: String) = Unit

    override fun deleteAll() = Unit

    override fun trim(keep: Int) = Unit
}

/** In-memory settings store; the graph only needs the shared interface. */
internal class FakeSettingsRepository(
    private var current: AppSettings = AppSettings(),
) : SettingsRepository {

    override fun load(): AppSettings = current

    override fun save(settings: AppSettings) {
        current = settings
    }
}

/** The graph under test, always with the deterministic executor. */
internal fun container(
    context: Context = RuntimeEnvironment.getApplication(),
    recovery: RecoveryRepository = FakeRecoveryRepository(),
    settings: SettingsRepository = FakeSettingsRepository(),
    executor: ExecutorService = DirectExecutorService(),
): AppContainer = buildAppContainer(
    context = context,
    factories = CompositionFactories(
        initAndroidContext = { },
        recovery = { recovery },
        settings = { settings },
        executor = executor,
    ),
)
