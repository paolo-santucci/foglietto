// LifecycleSaveCoordinator.swift
// iosApp
//
// M5 Share & Lifecycle (FR-14). Reference type that owns the synchronous,
// burst-guarded recovery-save core and the four ScenePhase / app-lifecycle
// seam methods. Hosted OUTSIDE NoteViewModel (FR-12 / m4_gate check 5: the
// VM must not gain a save/persist/write member nor a scenePhase reference).
//
// Hard constraints (§5.3 / R-01 / AR-01):
//   - The save at .background is SYNCHRONOUS. There is NO `Task { ... }`,
//     `DispatchQueue.*`, `async`, or `await` anywhere in this save path.
//     The save call must complete before onBackground() returns.
//   - The save is wrapped in beginBackgroundTask / endBackgroundTask so the
//     OS grants the foreground->background transition enough wall-time to let
//     the synchronous okio write finish.
//   - The single injected `recovery` RecoveryRepository instance from the
//     composition root is reused — NO createIosRecovery*Repository() call here
//     (m4_gate check 4). The repository is passed in via init.
//   - The save delegates to the shared `SaveNoteToRecovery` use case, which
//     trim-guards empty/whitespace (zero repo calls) and on non-empty calls
//     save(raw) then trim(10). Synchronous. Failures arrive as a value
//     (`attemptSave`), never as an exception: a Kotlin exception crossing into
//     Swift terminates the process, and a full disk must degrade instead.
//   - ONE save code path (D6 / sp-20261114): `saveCurrentBuffer()` is the only
//     place that calls the use case; `onBackground()` and `onTerminate()`
//     delegate to it, and the inbound drain calls it directly for the
//     collision save. A refused write returns `false` so the drain keeps the
//     payload pending instead of consuming it (FR-07).
//
// Burst guard (QP §Integration constraints):
//   A per-session `Bool` "saved this background episode" flag prevents a
//   double-write when .background fires repeatedly without an intervening
//   .active. Set on a .background save; cleared on .active.
//
// Spec refs: FR-14, NFR-01; R-01, R-11; §5.3.
//
// CONTRACT (QP §3.1) — this surface is the single source of truth that the
// lifecycle-wiring task (ContentView .onChange + iosAppApp AppDelegate adaptor)
// codes against. Parallel tasks import from this file, not from the plan doc.

import Foundation
import UIKit
import shared

/// Owns the synchronous burst-guarded recovery-save core and the lifecycle
/// seam methods. Constructed at the composition root from the single injected
/// `recovery` instance, a text provider, and a buffer-reset closure.
///
/// **Seam methods are callable directly from tests** (no SwiftUI ScenePhase
/// machinery required): a test drives `onBackground()` / `onActive()` /
/// `onTerminate()` against a spy `RecoveryRepository` and asserts the spy
/// recorded the `save` call *before* the method returns (NFR-01 — synchrony).
///
/// `@MainActor`: the coordinator is driven from SwiftUI `.onChange(of:scenePhase)`
/// (main actor) and from the UIApplicationDelegate (main thread). Annotated so
/// strict Swift 6 concurrency lets it touch the main-actor-isolated text-provider
/// closure without a hop. Test types constructing it must also be `@MainActor`.
@MainActor
final class LifecycleSaveCoordinator {

    // MARK: - Dependencies (injected at the composition root)

    /// The shared save use case, built once from the injected repository.
    /// The injected `recovery` is the SAME single instance from iosAppApp.init —
    /// NO new createIosRecovery*Repository() call here (m4_gate check 4).
    private let saveUseCase: SaveNoteToRecovery

    /// Supplies the current buffer text at save time. In production this reads
    /// `viewModel.text`; in tests it returns a controlled fixture.
    private let textProvider: () -> String

    /// Resets the ephemeral buffer on `.active`. In production this calls
    /// `viewModel.populate("")` (the existing reset entry point — no VM surface
    /// widening). FR-14 `.active` semantics.
    private let resetNote: (String) -> Void

    // MARK: - Burst guard

    /// `true` while this episode's save has already run. Set by a
    /// `.background` save; cleared by `.active`. Prevents a double-write when
    /// `.background` fires again without an intervening `.active`.
    private var savedThisEpisode: Bool = false

    /// `true` while the buffer holds an inbound seed and the app has not left the
    /// foreground since it was applied.
    ///
    /// Why it exists: whichever path applies the seed — `iosAppApp.init()` on a
    /// cold start, or `ContentView.dispatchScenePhase(.active)` on a warm one —
    /// the drain deletes the inbox entry immediately afterwards, so a reset cannot
    /// be repaired. `.active` is delivered more than once per launch (a share
    /// hand-off bounces `.active` → `.inactive` → `.active`), so `onActive()` must
    /// keep the seeded buffer for as long as this flag holds.
    ///
    /// Why it cannot weaken the ephemeral contract: only `.background` clears it,
    /// and `.background` is a real exit from the foreground. A seed the user has
    /// left the app from is reset on the next `.active` like every other buffer.
    private var keepsInboundSeed: Bool = false

    // MARK: - Init

    /// - Parameters:
    ///   - recovery: the single session RecoveryRepository (reused, never re-created).
    ///   - textProvider: returns the current buffer text at save time.
    ///   - resetNote: clears the ephemeral note on `.active` (prod: `populate("")`).
    init(
        recovery: RecoveryRepository,
        textProvider: @escaping () -> String,
        resetNote: @escaping (String) -> Void
    ) {
        self.saveUseCase = SaveNoteToRecovery(repository: recovery)
        self.textProvider = textProvider
        self.resetNote = resetNote
    }

    // MARK: - Lifecycle seam (callable directly from tests)

    /// The single, trim-guarded, synchronous save path (D6).
    ///
    /// Reads the buffer through `textProvider` and hands the RAW text to the shared
    /// `SaveNoteToRecovery` use case — through `attemptSave`, because a Kotlin
    /// exception crossing into Swift terminates the process and a full disk must
    /// degrade instead. One entry point, so the ordering can never diverge between
    /// `.background`, termination and the inbound collision save.
    ///
    /// Callers: `onBackground()`, `onTerminate()`, and the inbound drain's
    /// collision save. Deliberately burst-guard-FREE: the episode guard belongs to
    /// `onBackground()` alone, so a drain save neither arms nor consumes it.
    ///
    /// - Returns: `true` when the buffer is safe to replace — it was written, or it
    ///   was blank and there was nothing to write. `false` when the store refused the
    ///   write, which forbids the inbound seed from consuming the payload (FR-07).
    @discardableResult
    func saveCurrentBuffer() -> Bool {
        !saveUseCase.attemptSave(text: textProvider()).failed
    }

    /// Marks the buffer as holding an inbound seed the app has not left the
    /// foreground from (FR-16, EC-19).
    ///
    /// Called from BOTH seed paths: `ColdStartSeeder` right after a drain that
    /// seeded text, and `ContentView.dispatchScenePhase(.active)` right after the
    /// warm drain seeded. Only `.background` releases it.
    func adoptInboundSeed() {
        keepsInboundSeed = true
    }

    /// ScenePhase `.background`. Performs the burst-guarded SYNCHRONOUS save,
    /// wrapped in beginBackgroundTask / endBackgroundTask. No async / Task.
    /// Empty/whitespace text makes zero repo calls (trim-guarded by the use
    /// case) but still arms the guard for the episode.
    func onBackground() {
        // The app is leaving the foreground: the inbound seed no longer belongs to
        // the current session, so the next `.active` resets it like any buffer.
        // Cleared BEFORE the burst guard so a skipped save cannot keep it alive.
        keepsInboundSeed = false

        // Burst guard: skip if we already saved this background episode.
        guard !savedThisEpisode else { return }

        // Request additional background execution time so the OS does not
        // suspend the process before the synchronous okio write completes.
        // The task identifier is declared before the block so endBackgroundTask
        // can be called inside the synchronous body.
        var bgTask: UIBackgroundTaskIdentifier = .invalid
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "LifecycleSave") {
            // Expiry handler — called if the OS reclaims the time budget before
            // we finish. End the task to avoid a watchdog assertion; the in-flight
            // write is best-effort at this point (R-11).
            UIApplication.shared.endBackgroundTask(bgTask)
        }

        // Synchronous save through the ONE shared save path (D6).
        // saveCurrentBuffer() → SaveNoteToRecovery.attemptSave(text:) trim-guards
        // empty/whitespace (zero repo calls) and on non-empty calls save(raw)
        // then trim(10). No Task, no DispatchQueue, no async, no await anywhere
        // in this path (C-02). A refused write comes back as `false` instead of
        // terminating the process.
        let persisted = saveCurrentBuffer()

        // Release the background time budget.
        UIApplication.shared.endBackgroundTask(bgTask)

        // Arm the episode guard only when the write actually happened: a refused
        // save leaves the guard clear so the next `.background` retries instead of
        // silently dropping the draft for the rest of the foreground session.
        if persisted { savedThisEpisode = true }
    }

    /// ScenePhase `.active`. Resets the ephemeral note (`resetNote("")`) and
    /// clears the per-episode burst guard.
    ///
    /// The single exception is a buffer that holds an inbound seed the app has
    /// never left the foreground from: `adoptInboundSeed()` armed the guard, so
    /// this activation keeps the buffer instead of clearing it. The drain deletes
    /// the inbox entry as soon as it seeds, therefore a reset here could not be
    /// repaired by a re-drain — and `.active` arrives more than once per launch
    /// (a URL hand-off bounces `.active` → `.inactive` → `.active`), which is why
    /// the guard is NOT consumed here: only `.background` clears it.
    func onActive() {
        if !keepsInboundSeed {
            resetNote("")
        }
        savedThisEpisode = false
    }

    /// ScenePhase `.inactive`. No-op (FR-14).
    func onInactive() {
        // Intentional no-op. FR-14 specifies no action on .inactive.
    }

    /// `applicationWillTerminate` best-effort save (R-11: unreliable, documented).
    /// Same shared save path (`saveCurrentBuffer()`); no test asserts it fires
    /// under real termination.
    func onTerminate() {
        // Unguarded: savedThisEpisode is deliberately NOT checked here (R-11).
        // The OS may have already called .background before termination; this is
        // a best-effort duplicate-safe write (SaveNoteToRecovery is idempotent
        // for the same text, and trim(10) is a no-op when already at capacity).
        saveCurrentBuffer()
    }
}
