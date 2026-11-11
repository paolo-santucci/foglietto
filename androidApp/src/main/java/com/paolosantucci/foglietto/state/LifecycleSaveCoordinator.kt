package com.paolosantucci.foglietto.state

import com.paolosantucci.foglietto.shared.recovery.SaveNoteToRecovery
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android analogue of the iOS `@MainActor LifecycleSaveCoordinator`: bridges the process-lifecycle
 * seams to the shared, **blocking** [SaveNoteToRecovery] use case without doing okio I/O on the
 * caller (main) thread.
 *
 * Tier A by construction — zero `android.*` imports: the executor, the text snapshot and the reset
 * callback are all injected. The process-lifecycle observer itself is registered elsewhere.
 *
 * ## Threading (FR-11, NFR-01)
 *
 * [onProcessStop] snapshots the buffer on the **caller** thread (a `String` reference — cheap, and
 * the buffer may only be read from its owner thread), then submits
 * `SaveNoteToRecovery.invoke(text)` to the injected single-thread [executor] and waits for it with a
 * bounded [Future.get]. The I/O therefore runs on the executor thread, never on the caller thread,
 * while the seam still returns only after the write completed — the Android counterpart of the iOS
 * "save completes before the background callback returns" contract.
 *
 * ## Burst guard (FR-12, EC-08, EC-09, EC-25)
 *
 * The guard is armed with a single `compareAndSet` **before** the save is dispatched, so a second
 * signal arriving while the first write is still in flight is also suppressed. It is never disarmed
 * inside an episode — including after a failure or a timeout, so a failing store cannot produce a
 * retry storm. Only [onProcessStart] disarms it, which is what lets the next background episode save
 * again.
 *
 * ## Failure contract (EC-05)
 *
 * A throwing [SaveNoteToRecovery] surfaces as an [ExecutionException] on the caller thread; the
 * coordinator unwraps it and rethrows the **cause unchanged** (shared no-swallow contract — the
 * uncaught-exception handler is then free to end the process, parity with the tracked iOS path).
 *
 * ## Bounded await (EC-10)
 *
 * If the write does not complete within [awaitCeilingMillis], the seam returns without throwing and
 * **without cancelling** the task: the write continues best-effort on the executor, the documented
 * analogue of the iOS background-task expiry handler.
 *
 * ## Clear path (EC-11)
 *
 * [saveNow] dispatches to the **same** single-thread executor as lifecycle saves, so the two can
 * never interleave: a Clear save completes before a queued lifecycle save starts.
 *
 * Spec refs: §5.1.c, FR-11, FR-12, FR-13, NFR-01, EC-05, EC-08, EC-09, EC-10, EC-11, EC-25.
 */
class LifecycleSaveCoordinator(
    private val save: SaveNoteToRecovery,
    private val snapshotText: () -> String,
    private val resetNote: (String) -> Unit,
    private val executor: ExecutorService,
    private val awaitCeilingMillis: Long = DEFAULT_AWAIT_CEILING_MILLIS,
) {

    private val armed = AtomicBoolean(false)

    /**
     * Process left the foreground: save at most once per background episode (FR-11, FR-12).
     *
     * Arms the guard, snapshots the buffer on the caller thread, saves on [executor], and waits at
     * most [awaitCeilingMillis] for the write. Returns the written path, or `null` when the shared
     * trim-guard suppressed the save, the await timed out, the caller was interrupted, or the
     * episode was already armed.
     */
    fun onProcessStop(): String? {
        // Arm first: a concurrent signal must not dispatch a second write (EC-08).
        if (!armed.compareAndSet(false, true)) return null
        val text = snapshotText()
        return awaitOrNull(executor.submit(Callable { save.invoke(text) }))
    }

    /**
     * Process returned to the foreground: clear the ephemeral buffer and end the episode (FR-13).
     *
     * The guard is disarmed even if [resetNote] fails, so a broken reset cannot wedge the next
     * episode's save.
     */
    fun onProcessStart() {
        armed.set(false)
        resetNote("")
    }

    /**
     * Clear tap (EC-11): saves the current buffer on the same executor as lifecycle saves, so the
     * two serialise instead of interleaving. Returns the written path, or `null` when the
     * trim-guard suppressed the save (already empty) or the bounded await timed out.
     *
     * Does not touch the burst guard — Clear is not a background episode.
     */
    fun saveNow(): String? {
        val text = snapshotText()
        return awaitOrNull(executor.submit(Callable { save.invoke(text) }))
    }

    private fun awaitOrNull(future: Future<String?>): String? =
        try {
            future.get(awaitCeilingMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Best-effort continuation on the executor; the task is deliberately NOT cancelled.
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    private companion object {
        /** Upper bound on how long a lifecycle seam blocks the caller (spec §5.1.c, EC-10). */
        const val DEFAULT_AWAIT_CEILING_MILLIS = 2_000L
    }
}
