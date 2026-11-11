package com.paolosantucci.foglietto.state

import com.paolosantucci.foglietto.shared.recovery.RecoveryNote
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository
import com.paolosantucci.foglietto.shared.recovery.SaveNoteToRecovery
import java.io.IOException
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A unit suite for [LifecycleSaveCoordinator] — plain JVM, **no** Robolectric and no
 * `android.*` types (the executor and both lambdas are injected).
 *
 * Pins plan TASK-09 / spec §7.1:
 *  - [theSaveRunsOnTheExecutorThreadNeverTheCaller] — the NFR-01 executing-thread proof: the
 *    okio write happens on the injected single-thread executor, never on the caller (main) thread.
 *  - [oneSavePerBackgroundEpisode] + [foregroundReturnDisarmsTheGuard] — the burst guard (FR-12,
 *    EC-08, EC-09): one save per background episode, a fresh episode saves again.
 *  - [anIOExceptionIsRethrownUnwrappedAndTheGuardStaysArmed] — shared no-swallow contract (EC-05):
 *    the `ExecutionException` is unwrapped, the cause rethrown, no retry storm.
 *  - [aStuckExecutorReturnsAtTheCeilingWithoutThrowing] — bounded await (EC-10): the seam returns
 *    at `awaitCeilingMillis` and the write continues best-effort on the executor.
 *  - [saveNowSerialisesOnTheSameExecutor] — EC-11: Clear-path saves and lifecycle saves run on the
 *    same executor, strictly sequential.
 *  - [whitespaceOnlyTextMakesNoRepositoryCallButStillArmsTheGuard] — the shared trim-guard
 *    (EC-25) suppresses the repository call while the guard still arms for the episode.
 *
 * Determinism: no test sleeps for a wall-clock window; the only real wait is the 50 ms injected
 * ceiling, and the "stuck" executor task is released explicitly at the end of the test.
 */
class LifecycleSaveCoordinatorTest {

    @Test
    fun `the save runs on the executor thread, never the caller`() {
        Harness().use { harness ->
            harness.coordinator.onProcessStop()

            assertEquals(1, harness.repo.saveInvocations.get())
            assertEquals(listOf(SAVE_THREAD_NAME), harness.repo.saveThreadNames.toList())
            assertNotEquals(Thread.currentThread().name, harness.repo.saveThreadNames.single())
        }
    }

    @Test
    fun `one save per background episode`() {
        Harness().use { harness ->
            harness.coordinator.onProcessStop()
            harness.coordinator.onProcessStop()

            assertEquals(1, harness.repo.saveInvocations.get())
            assertEquals(listOf(TEXT), harness.repo.savedTexts.toList())
        }
    }

    @Test
    fun `foreground return disarms the guard`() {
        Harness().use { harness ->
            harness.coordinator.onProcessStop()
            harness.coordinator.onProcessStart()
            harness.coordinator.onProcessStop()

            assertEquals(2, harness.repo.saveInvocations.get())
            assertEquals(listOf(""), harness.resetCalls.toList())
        }

        // Cold start: ON_START on a never-backgrounded process resets once and saves nothing.
        Harness().use { harness ->
            harness.coordinator.onProcessStart()

            assertEquals(0, harness.repo.saveInvocations.get())
            assertEquals(listOf(""), harness.resetCalls.toList())
        }
    }

    @Test
    fun `an IOException is rethrown unwrapped and the guard stays armed`() {
        val ioe = IOException("recovery store is read-only")
        Harness(repo = FakeRecoveryRepository().apply { saveThrowable = ioe }).use { harness ->
            val thrown = assertThrows(IOException::class.java) { harness.coordinator.onProcessStop() }
            assertSame(ioe, thrown)

            // Guard still armed: the next stop within the same episode makes zero further calls.
            harness.coordinator.onProcessStop()
            assertEquals(1, harness.repo.saveInvocations.get())
        }
    }

    @Test
    fun `a stuck executor returns at the ceiling without throwing`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repo = FakeRecoveryRepository().apply { blockSaveUntil(started, release) }

        Harness(repo = repo, awaitCeilingMillis = 50).use { harness ->
            try {
                harness.coordinator.onProcessStop() // must return at the ceiling, not hang

                // The write is still in flight on the executor (best-effort continuation).
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertEquals(1, repo.saveInvocations.get())

                // Guard armed during and after the timeout: no second dispatch.
                harness.coordinator.onProcessStop()
                assertEquals(1, repo.saveInvocations.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `saveNow serialises on the same executor`() {
        val recording = RecordingExecutorService(
            Executors.newSingleThreadExecutor(namedThreadFactory(SAVE_THREAD_NAME)),
        )
        Harness(executor = recording).use { harness ->
            val cleared = harness.coordinator.saveNow()
            harness.coordinator.onProcessStop() // a lifecycle save after Clear still runs

            assertEquals("/recovery/$TEXT.txt", cleared)
            assertEquals(2, harness.repo.saveInvocations.get())
            // No two tasks ever overlapped: both dispatched through the one injected executor.
            assertEquals(1, recording.maxConcurrent.get())
            assertEquals(
                listOf(
                    "start:$SAVE_THREAD_NAME",
                    "end:$SAVE_THREAD_NAME",
                    "start:$SAVE_THREAD_NAME",
                    "end:$SAVE_THREAD_NAME",
                ),
                recording.events.toList(),
            )
        }
    }

    @Test
    fun `whitespace-only text makes no repository call but still arms the guard`() {
        Harness(text = "   \n\t").use { harness ->
            harness.coordinator.onProcessStop()

            assertEquals(0, harness.repo.saveInvocations.get())
            assertTrue(harness.repo.savedTexts.isEmpty())

            // Still armed for the episode: a second stop makes zero calls too.
            harness.coordinator.onProcessStop()
            assertEquals(0, harness.repo.saveInvocations.get())
        }
    }

    // ── harness ────────────────────────────────────────────────────────────────────────────

    private class Harness(
        val repo: FakeRecoveryRepository = FakeRecoveryRepository(),
        text: String = TEXT,
        awaitCeilingMillis: Long = 2_000,
        executor: ExecutorService = Executors.newSingleThreadExecutor(
            namedThreadFactory(SAVE_THREAD_NAME),
        ),
    ) : AutoCloseable {

        val resetCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val executor: ExecutorService = executor

        val coordinator = LifecycleSaveCoordinator(
            save = SaveNoteToRecovery(repo),
            snapshotText = { text },
            resetNote = { resetCalls += it },
            executor = executor,
            awaitCeilingMillis = awaitCeilingMillis,
        )

        override fun close() {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    /**
     * Domain-port fake. Every [save] invocation is counted (including throwing ones), so
     * "zero repository calls" is asserted on the use-case boundary, not on side effects.
     */
    private class FakeRecoveryRepository : RecoveryRepository {

        val saveInvocations = AtomicInteger()
        val savedTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val saveThreadNames: MutableList<String> = Collections.synchronizedList(mutableListOf())

        var saveThrowable: Throwable? = null
        private var started: CountDownLatch? = null
        private var release: CountDownLatch? = null

        fun blockSaveUntil(started: CountDownLatch, release: CountDownLatch) {
            this.started = started
            this.release = release
        }

        override fun save(text: String): String {
            saveInvocations.incrementAndGet()
            started?.countDown()
            release?.await()
            saveThrowable?.let { throw it }
            savedTexts += text
            saveThreadNames += Thread.currentThread().name
            return "/recovery/$text.txt"
        }

        override fun list(): List<RecoveryNote> = emptyList()

        override fun read(path: String): String? = null

        override fun delete(path: String) = Unit

        override fun deleteAll() = Unit

        override fun trim(keep: Int) = Unit
    }

    /**
     * Executor decorator that records task start/end events and the concurrency high-water mark,
     * so "serialised on the same single-thread executor" is observable rather than assumed.
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

private const val TEXT = "hello world"

/** The injected executor's thread name — the NFR-01 assertion compares against it directly. */
private const val SAVE_THREAD_NAME = "foglietto-save-test"

private fun namedThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
    Thread(runnable, name).apply { isDaemon = true }
}
