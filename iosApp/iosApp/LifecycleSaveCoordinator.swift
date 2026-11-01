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
//     save(raw) then trim(10). Synchronous; returns String?.
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

    /// `true` once this background episode has already saved. Set by a
    /// `.background` save; cleared by `.active`. Prevents a double-write when
    /// `.background` fires again without an intervening `.active`.
    private var savedThisEpisode: Bool = false

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

    /// ScenePhase `.background`. Performs the burst-guarded SYNCHRONOUS save,
    /// wrapped in beginBackgroundTask / endBackgroundTask. No async / Task.
    /// Empty/whitespace text makes zero repo calls (trim-guarded by the use
    /// case) but still arms the guard for the episode.
    func onBackground() {
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

        // Synchronous save via the shared use case.
        // SaveNoteToRecovery.invoke(text:) trim-guards empty/whitespace (zero
        // repo calls) and on non-empty calls save(raw) then trim(10). No Task,
        // no DispatchQueue, no async, no await anywhere in this path (C-02).
        _ = saveUseCase.invoke(text: textProvider())

        // Release the background time budget.
        UIApplication.shared.endBackgroundTask(bgTask)

        // Arm the burst guard for this background episode.
        savedThisEpisode = true
    }

    /// ScenePhase `.active`. Resets the ephemeral note (`resetNote("")`) and
    /// clears the per-episode burst guard. Cold-start `.active` does not fire via
    /// `.onChange(of:)`, so there is no spurious reset on launch.
    func onActive() {
        resetNote("")
        savedThisEpisode = false
    }

    /// ScenePhase `.inactive`. No-op (FR-14).
    func onInactive() {
        // Intentional no-op. FR-14 specifies no action on .inactive.
    }

    /// `applicationWillTerminate` best-effort save (R-11: unreliable, documented).
    /// Same synchronous save core; no test asserts it fires under real termination.
    func onTerminate() {
        // Unguarded: savedThisEpisode is deliberately NOT checked here (R-11).
        // The OS may have already called .background before termination; this is
        // a best-effort duplicate-safe write (SaveNoteToRecovery is idempotent
        // for the same text, and trim(10) is a no-op when already at capacity).
        _ = saveUseCase.invoke(text: textProvider())
    }
}
