package com.paolosantucci.foglietto

import android.content.Context
import com.paolosantucci.foglietto.shared.AndroidContext
import com.paolosantucci.foglietto.shared.editor.acceptIncomingText
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository
import com.paolosantucci.foglietto.shared.recovery.SaveNoteToRecovery
import com.paolosantucci.foglietto.shared.recovery.createAndroidRecoveryRepository
import com.paolosantucci.foglietto.shared.settings.SettingsRepository
import com.paolosantucci.foglietto.shared.settings.createAndroidSettingsRepository
import com.paolosantucci.foglietto.state.EditorHistory
import com.paolosantucci.foglietto.state.EditorStateHolder
import com.paolosantucci.foglietto.state.LifecycleSaveCoordinator
import com.paolosantucci.foglietto.state.RecoveryListStateHolder
import com.paolosantucci.foglietto.ui.share.ShareLauncher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The process-scoped object graph: every singleton the Android front-end needs, built once per
 * process by the composition root ([FogliettoApplication.onCreate]).
 *
 * ## Ownership
 *
 * The container is the only place that constructs repositories, state holders and the save
 * coordinator (§4.3): `MainActivity` and the Compose shell consume instances from here and never
 * build their own, so the buffer, its history and the recovery store are exactly one instance per
 * process (FR-06). The two shared factories behind [recovery] and [settings] stay platform
 * details — okio and multiplatform-settings never cross this API (FR-06, m8).
 *
 * ## Process seams
 *
 * [onProcessStopped] and [onProcessStarted] are what the `ProcessLifecycleOwner` observer calls.
 * Routing them through the container (rather than through the Activity) is what makes a
 * configuration change inert: rotation destroys only the Activity, the process lifecycle never
 * emits, so neither seam runs (FR-14).
 *
 *  - [onProcessStopped] — background episode: flip [foreground] and save the raw buffer text once
 *    (FR-11, FR-12, FR-23).
 *  - [onProcessStarted] — foreground return: empty the buffer **and** clear both undo/redo stacks,
 *    so Undo cannot resurrect backgrounded text (EC-23), then seed a stashed inbound payload.
 *    The text is recoverable through Recent notes instead.
 *
 * ## Inbound shares (FR-23, FR-24)
 *
 * [acceptInboundText] is the single entry point for a payload arriving from the share sheet or
 * `ACTION_PROCESS_TEXT`. It never touches the buffer directly: the payload waits in a memory-only
 * slot until the process is foregrounded, and is then applied as save-then-populate so the text it
 * replaces is recoverable (EC-22). A share that arrives before the process reports ON_START —
 * the cold-start case — therefore never races the reset (EC-20, EC-21).
 *
 * Nothing here touches `savedInstanceState`, disk or any store for buffer/caret/selection/history:
 * the buffer is ephemeral by product contract, and the only persistence is the ON_STOP save
 * (FR-10).
 *
 * @property recovery the recovery store — the single instance the save and recent-notes paths share.
 *   Module-internal: the shell reads and restores notes through [recoveryList], never by bypassing
 *   its state holder.
 * @property settings the persisted app settings.
 * @property editor the ephemeral buffer, its selection and its undo/redo history.
 * @property recoveryList the Recent-notes sheet state holder, wired to restore into [editor].
 * @property coordinator the process-lifecycle save adapter (also used by the Clear path, TASK-19).
 * @property share the outbound share seam.
 * @property history the undo/redo stack [editor] drives; cleared on the foreground return.
 */
class AppContainer internal constructor(
    internal val recovery: RecoveryRepository,
    val settings: SettingsRepository,
    val editor: EditorStateHolder,
    val recoveryList: RecoveryListStateHolder,
    val coordinator: LifecycleSaveCoordinator,
    val share: ShareLauncher,
    private val history: EditorHistory,
) {

    /**
     * The inbound payload waiting for the next foreground return, already normalized by the shared
     * acceptance rule; `null` when nothing is waiting. Memory-only: it never survives process death
     * (EC-24).
     */
    private var pending: String? = null

    /**
     * Whether the process is currently foregrounded. Only a foreground process may seed a payload,
     * and the flag flips *before* the ON_STOP save so a share arriving mid-transition is stashed
     * (FR-23).
     */
    private var foreground: Boolean = false

    /**
     * Hands a raw inbound payload (share sheet / `ACTION_PROCESS_TEXT`) to the graph (FR-23, FR-24).
     *
     * The shared acceptance rule owns normalization and blank rejection — never this class
     * (NFR-05). The *latest* payload wins, including a blank one, which clears the slot. A
     * foregrounded process applies it immediately; a backgrounded one only stashes it until the
     * next [onProcessStarted], so a cold-start share cannot race the reset (EC-20, EC-21).
     */
    fun acceptInboundText(raw: String?) {
        pending = acceptIncomingText(raw)
        if (foreground) applyPendingInbound()
    }

    /** Process left the foreground: one burst-guarded save of the raw buffer text (FR-11, FR-12). */
    fun onProcessStopped() {
        // Flip first: a share arriving during the background transition must be stashed, not
        // applied over the text this save is writing (FR-23).
        foreground = false
        coordinator.onProcessStop()
    }

    /**
     * Process returned to the foreground: empty the buffer and drop both history stacks (FR-13,
     * EC-23). The reset itself records a snapshot in [history]; clearing afterwards makes it
     * unreachable, so a later Undo cannot resurrect backgrounded text.
     *
     * The pending payload is applied *after* that reset — the only legal order (§5.1.8).
     */
    fun onProcessStarted() {
        coordinator.onProcessStart()
        history.clear()
        foreground = true
        applyPendingInbound()
    }

    /**
     * Seeds the stashed payload: save the text it replaces, then write it as one undoable
     * programmatic edit (FR-24, EC-22).
     *
     * A throwing save keeps both the buffer and the payload — the payload is retained for the next
     * foreground return and nothing is logged (FR-07, EC-10). A `null` return from
     * [LifecycleSaveCoordinator.saveNow] (trim-guard no-op or bounded-await timeout) is **not** a
     * failure: the write was already dispatched with the pre-seed snapshot, so seeding is safe.
     */
    private fun applyPendingInbound() {
        val text = pending ?: return
        try {
            coordinator.saveNow()
        } catch (_: Exception) {
            // FR-07: the payload stays pending and the buffer stays intact; no log, no crash.
            return
        }
        editor.populate(text)
        pending = null
    }
}

/**
 * The three shared-factory seams the composition root needs, plus the save executor.
 *
 * Production uses the defaults, which are exactly the shared Android factories (TASK-07) and a
 * dedicated single-thread executor. Tests inject recording/fake implementations to pin the
 * ordering contract (FR-04, EC-06) and to keep every seam synchronous.
 *
 * The executor is a graph input rather than a constant so the "blocking I/O never runs on the
 * caller thread" contract stays injectable (NFR-01); production hands the coordinator a
 * dedicated single-thread executor named [SAVE_THREAD_NAME].
 */
internal class CompositionFactories(
    val initAndroidContext: (Context) -> Unit = AndroidContext::init,
    val recovery: () -> RecoveryRepository = ::createAndroidRecoveryRepository,
    val settings: () -> SettingsRepository = ::createAndroidSettingsRepository,
    val executor: ExecutorService = newSaveExecutor(),
)

/**
 * Builds the process graph in the one order that is safe (§4.3):
 *
 *  1. `AndroidContext.init(context)` — the platform seam must exist before any shared factory
 *     resolves `<filesDir>` or the default `SharedPreferences` file; a factory called first throws
 *     its loud `IllegalStateException` (FR-04, EC-06).
 *  2. the two shared repositories — one instance each (FR-06).
 *  3. the use case, the state holders, then the coordinator, wired to the same instances.
 *
 * `resetNote` empties the buffer through the editor's own `populate` (one state write, caret reset,
 * scroll-to-top) and the container clears the history stacks right after.
 */
internal fun buildAppContainer(
    context: Context,
    factories: CompositionFactories = CompositionFactories(),
): AppContainer {
    factories.initAndroidContext(context)

    val recovery = factories.recovery()
    val settings = factories.settings()

    val history = EditorHistory()
    val editor = EditorStateHolder(history)
    val recoveryList = RecoveryListStateHolder(recovery) { text -> editor.populate(text) }
    val coordinator = LifecycleSaveCoordinator(
        save = SaveNoteToRecovery(recovery),
        snapshotText = { editor.value.text },
        resetNote = { text -> editor.populate(text) },
        executor = factories.executor,
    )

    return AppContainer(
        recovery = recovery,
        settings = settings,
        editor = editor,
        recoveryList = recoveryList,
        coordinator = coordinator,
        share = ShareLauncher(context),
        history = history,
    )
}

/** Thread name of the dedicated save executor — the NFR-01 executing-thread proof looks for it. */
private const val SAVE_THREAD_NAME = "foglietto-save"

/** Dedicated single-thread executor: lifecycle saves and Clear saves serialise on it (EC-11). */
private fun newSaveExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, SAVE_THREAD_NAME) }
