package com.paolosantucci.foglietto.state

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.paolosantucci.foglietto.shared.recovery.RecoveryNote
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository
import com.paolosantucci.foglietto.shared.recovery.SaveNoteToRecovery
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A unit suite for [ClearController] — plain JVM, no Robolectric, no Compose UI host.
 *
 * Pins plan TASK-19 / spec FR-32, FR-30, EC-11, EC-22 (revised 2026-11-15):
 *  - [clear saves the raw text, empties the buffer and signals the snackbar] — the order save →
 *    empty → signal, and that the written payload is the RAW (un-trimmed) buffer;
 *  - [clearing resets undo and redo] — Clear drops both history stacks, so the cleared note cannot
 *    be resurrected from the top bar (this is the behaviour the product owner asked for: the Undo
 *    button resets when Clear is pressed);
 *  - [the snackbar restore brings back the cleared text and its selection] — the one-shot restore
 *    the signal hands over is the only way back, and it re-arms the history as a normal note;
 *  - [clear on an empty buffer is a no-op] — `canClear` gates the whole flow: no save, no signal;
 *  - [the clear save and a lifecycle save never interleave] — EC-11: both saves dispatch through
 *    the one injected single-thread executor, so they can never overlap;
 *  - [a second clear while the signal is pending does not double-save or signal] and
 *    [a re-entrant clear from the signal does not double-save or signal] — one clear produces
 *    exactly one save and one signal.
 *
 * There is no confirmation dialog anywhere in this flow (FR-30): the controller has no
 * "confirm" entry point to test, and the suite asserts the single-tap contract instead.
 */
class ClearControllerTest {

    @Test
    fun `clear saves the raw text, empties the buffer and signals the snackbar`() {
        Harness(text = "  hello  ").use { harness ->
            harness.controller.clear()

            assertEquals(1, harness.repo.saveInvocations.get())
            assertEquals(listOf("  hello  "), harness.repo.savedTexts.toList())
            assertEquals("", harness.holder.value.text)
            assertEquals(1, harness.signals)
        }
    }

    @Test
    fun `clearing resets undo and redo`() {
        Harness(text = "  hello  ").use { harness ->
            assertTrue("typing armed Undo.", harness.holder.canUndo)

            harness.controller.clear()

            assertFalse("Clear must leave the top bar's Undo disabled.", harness.holder.canUndo)
            assertFalse("and Redo too — the note is new.", harness.holder.canRedo)
            harness.holder.undo()
            assertEquals("Undo is a no-op after a reset.", "", harness.holder.value.text)
            harness.holder.redo()
            assertEquals("Redo is a no-op after a reset.", "", harness.holder.value.text)
        }
    }

    @Test
    fun `the snackbar restore brings back the cleared text and its selection`() {
        Harness(text = "  hello  ", initialCaret = 3).use { harness ->
            harness.controller.clear()

            harness.restore?.invoke()

            assertEquals("  hello  ", harness.holder.value.text)
            assertEquals(TextRange(3), harness.holder.value.selection)
            // The restore is a normal programmatic edit: it re-arms the history, so the restored
            // note behaves like any other buffer from here on.
            assertTrue(harness.holder.canUndo)
        }
    }

    @Test
    fun `clear on an empty buffer is a no-op`() {
        Harness().use { harness ->
            assertFalse(harness.holder.canClear)

            harness.controller.clear()

            assertEquals(0, harness.repo.saveInvocations.get())
            // `saveNow` was never even asked to snapshot the buffer: the guard is the entry point.
            assertEquals(0, harness.snapshots)
            assertEquals(0, harness.signals)
            assertEquals("", harness.holder.value.text)
        }
    }

    @Test
    fun `the clear save and a lifecycle save never interleave`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        Harness(text = "  hello  ").use { harness ->
            harness.repo.blockSaveUntil(started, release)

            val clearCaller = Thread({ harness.controller.clear() }, "clear-caller").apply {
                isDaemon = true
                start()
            }
            try {
                // The Clear write is now in flight and holding the only executor thread.
                assertTrue(started.await(2, TimeUnit.SECONDS))

                val stopCaller = Thread({ harness.coordinator.onProcessStop() }, "stop-caller").apply {
                    isDaemon = true
                    start()
                }
                release.countDown()
                clearCaller.join(2_000)
                stopCaller.join(2_000)
            } finally {
                release.countDown()
            }

            // Strictly sequential: no two saves ever overlapped on the shared executor.
            assertEquals(1, harness.recording.maxConcurrent.get())
            assertEquals(
                listOf(
                    "start:$SAVE_THREAD_NAME",
                    "end:$SAVE_THREAD_NAME",
                    "start:$SAVE_THREAD_NAME",
                    "end:$SAVE_THREAD_NAME",
                ),
                harness.recording.events.toList(),
            )
        }
    }

    @Test
    fun `a second clear while the signal is pending does not double-save or signal`() {
        Harness(text = "hello").use { harness ->
            harness.controller.clear()
            harness.controller.clear()

            assertEquals(1, harness.repo.saveInvocations.get())
            assertEquals(listOf("hello"), harness.repo.savedTexts.toList())
            assertEquals(1, harness.signals)
        }
    }

    @Test
    fun `a re-entrant clear from the signal does not double-save or signal`() {
        val repo = FakeRecoveryRepository()
        val holder = EditorStateHolder(EditorHistory())
        val executor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory(SAVE_THREAD_NAME))
        try {
            val coordinator = LifecycleSaveCoordinator(
                save = SaveNoteToRecovery(repo),
                snapshotText = { holder.value.text },
                resetNote = { holder.populate(it) },
                executor = executor,
            )
            var signals = 0
            lateinit var controller: ClearController
            // The shell posts the Snackbar from this callback; a pathological re-entry must not
            // produce a second save or a second signal.
            controller = ClearController(holder, coordinator) {
                signals++
                controller.clear()
            }
            holder.onInput(TextFieldValue("hello", TextRange(5)))

            controller.clear()

            assertEquals(1, repo.saveInvocations.get())
            assertEquals(1, signals)
            assertEquals("", holder.value.text)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    // ── harness ────────────────────────────────────────────────────────────────────────────

    private class Harness(
        text: String = "",
        initialCaret: Int = text.length,
    ) : AutoCloseable {

        val repo = FakeRecoveryRepository()
        val holder = EditorStateHolder(EditorHistory())
        val recording = RecordingExecutorService(
            Executors.newSingleThreadExecutor(namedThreadFactory(SAVE_THREAD_NAME)),
        )

        /** How many times the coordinator asked for the buffer text (i.e. was invoked at all). */
        var snapshots: Int = 0
            private set

        val coordinator = LifecycleSaveCoordinator(
            save = SaveNoteToRecovery(repo),
            snapshotText = {
                snapshots++
                holder.value.text
            },
            resetNote = { holder.populate(it) },
            executor = recording,
        )

        var signals: Int = 0
            private set

        /** The one-shot restore the controller hands to the Snackbar (null before any clear). */
        var restore: (() -> Unit)? = null
            private set

        val controller = ClearController(holder, coordinator) { restoreAction ->
            signals++
            restore = restoreAction
        }

        init {
            if (text.isNotEmpty()) holder.onInput(TextFieldValue(text, TextRange(initialCaret)))
        }

        override fun close() {
            recording.shutdownNow()
            recording.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    /**
     * Domain-port fake. Every [save] invocation is counted (including throwing ones), so "zero
     * repository calls" is asserted on the use-case boundary, not on side effects.
     */
    private class FakeRecoveryRepository : RecoveryRepository {

        val saveInvocations = AtomicInteger()
        val savedTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private var startedLatch: CountDownLatch? = null
        private var releaseLatch: CountDownLatch? = null

        fun blockSaveUntil(started: CountDownLatch, release: CountDownLatch) {
            startedLatch = started
            releaseLatch = release
        }

        override fun save(text: String): String {
            saveInvocations.incrementAndGet()
            startedLatch?.countDown()
            releaseLatch?.await()
            savedTexts += text
            return "/recovery/${text.hashCode()}.txt"
        }

        override fun list(): List<RecoveryNote> = emptyList()

        override fun read(path: String): String? = null

        override fun delete(path: String) = Unit

        override fun deleteAll() = Unit

        override fun trim(keep: Int) = Unit
    }

    /**
     * Executor decorator that records task start/end events and the concurrency high-water mark,
     * so "the Clear save and a lifecycle save serialise" is observable rather than assumed.
     */
    private class RecordingExecutorService(
        private val delegate: ExecutorService,
    ) : AbstractExecutorService() {

        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override fun execute(command: Runnable) {
            delegate.execute {
                val nowActive = active.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, nowActive) }
                val thread = Thread.currentThread().name
                events += "start:$thread"
                try {
                    command.run()
                } finally {
                    events += "end:$thread"
                    active.decrementAndGet()
                }
            }
        }

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            delegate.awaitTermination(timeout, unit)
    }
}

/** The injected executor's thread name — the serialisation assertion compares against it. */
private const val SAVE_THREAD_NAME = "foglietto-clear-test"

private fun namedThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
    Thread(runnable, name).apply { isDaemon = true }
}
