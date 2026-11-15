// iosAppApp.swift
// iosApp
//
// Application entry point and composition root.
//
// Spec refs: FR-03, FR-14, FR-19, FR-13 (wiring), FR-09; §4.1; §4.3 Cold start
//
// Composition invariant (§4.1 / FR-03 / CM-1):
//   EXACTLY ONE SettingsRepository instance is constructed here via the Kotlin
//   top-level factory IosSettingsFactoryKt.createIosSettingsRepository().
//   EXACTLY ONE RecoveryRepository instance is constructed here via the Kotlin
//   top-level factory IosRecoveryFactoryKt.createIosRecoveryRepository() (TASK-02).
//   Both handles are wired in init() so they can be shared across:
//     - NoteViewModel(settings:)  — the settings construction contract
//       (the former fontSizeIndex cold-start read is retired: the editor always
//       renders the system default font — user direction)
//     - @State recovery  → chrome injection: MenuViewModel / RecoveryListViewModel
//                          (consumed in TASK-10 / TASK-11)
//     - @State lifecycle → LifecycleSaveCoordinator (M5 FR-14)
//   (The CM-1 single-handle invariant is structural now: the settings store has
//   exactly one consumer and one construction site — m4 gate check 4.)
//
// ScenePhase save wiring (M5 / FR-14):
//   LifecycleSaveCoordinator is constructed here from the single `recoveryRepo`
//   and the already-constructed `viewModel`.  The scene-phase `.onChange` lives
//   in ContentView; `applicationWillTerminate` is forwarded via AppDelegate (R-11,
//   best-effort).  No new factory call is introduced (m4_gate check 4).
//
// Inbound cold-start seed (§4.3 cold start / FR-16 / FR-18 / FR-20 / NFR-01):
//   After the coordinator local exists, `init()` runs ONE synchronous drain
//   through `ColdStartSeeder` and stores the resulting `ShareInboxDrainer` in
//   `drainer`.  Because `init()` completes before `body` is evaluated, the first
//   `NoteEditor.updateUIView` already sees the seeded `viewModel.text` and a
//   `populationRevision` of 1 — there is no empty first frame (D4 / EC-19).
//   The same instance is what the warm `.active` path reuses (§5.1.5), so the
//   ordering can never diverge between cold start and warm activation.

import SwiftUI
import shared

// MARK: - AppDelegate (applicationWillTerminate — best-effort R-11)

/// Minimal UIApplicationDelegate that forwards `applicationWillTerminate` to
/// the lifecycle coordinator.
///
/// `onTerminate` is a var closure set once at construction (in `iosAppApp.body`)
/// and called by the OS when the app is about to terminate.  R-11: this callback
/// is unreliable (the OS does not guarantee it fires before the process dies), so
/// it is a best-effort supplement to the `.background` save — not the primary path.
///
/// Why set in `body` with `if appDelegate.onTerminate == nil`:
///   `body` is the earliest point where `@UIApplicationDelegateAdaptor` is
///   guaranteed to be initialised AND where the `@State lifecycle` wrapper value
///   is accessible (SwiftUI resolves @State before evaluating `body`, but the
///   wrapped value is NOT available in `init()` — only the `_lifecycle` backing
///   store is seeded there).  Guarding with `== nil` ensures the closure is
///   assigned exactly once regardless of how many times SwiftUI re-evaluates body.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Called by `iosAppApp.body` once, immediately after the coordinator is known.
    var onTerminate: (() -> Void)?

    func applicationWillTerminate(_ application: UIApplication) {
        onTerminate?()
    }
}

@main
struct iosAppApp: App {

    // MARK: - AppDelegate adaptor (applicationWillTerminate — R-11 best-effort)

    /// Bridges `UIApplicationDelegate.applicationWillTerminate` into the SwiftUI
    /// lifecycle.  The `onTerminate` closure is set once in `body` and calls
    /// `lifecycle.onTerminate()` (R-11: best-effort, unreliable — documented).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    // MARK: - Composition root (§4.1 / §4.3 cold-start path)

    /// The single SettingsRepository for the session (FR-03 / CM-1).
    ///
    /// Constructed once in init() via the Kotlin top-level factory.  Injected into
    /// NoteViewModel(settings:) (the editor's cold-start read path) — the chrome
    /// no longer consumes it (theme/font pickers removed per user direction).
    @State private var settings: SettingsRepository

    /// The single RecoveryRepository for the session (FR-05 / CM-3).
    ///
    /// Constructed once in init() via IosRecoveryFactoryKt.createIosRecoveryRepository()
    /// (TASK-02 iosMain factory).  Held here for injection into the chrome layer:
    /// MenuViewModel and RecoveryListViewModel consume it in TASK-10 / TASK-11.
    @State private var recovery: RecoveryRepository

    /// The single `NoteViewModel` for the session.
    ///
    /// `@State` is the correct ownership wrapper for an `@Observable` object on iOS 17+/26:
    /// `@StateObject` is for `ObservableObject`; `@Observable` classes should be stored in
    /// `@State` (or as `let` constants injected from outside).  Using `@State` here ensures
    /// SwiftUI owns the lifetime and the reference is stable across scene restarts.
    ///
    /// Constructed in init() with the single `settings` instance (FR-03 / CM-1
    /// construction contract; the former font-slot read is retired — the editor
    /// always renders the system default font).
    @State private var viewModel: NoteViewModel

    /// The lifecycle save coordinator (FR-14 / M5).
    ///
    /// Constructed once in init() from the already-constructed `recoveryRepo` and
    /// `viewModel` — NO new `createIosRecoveryRepository()` call here (m4_gate check 4).
    /// Capturing `viewModel` in the closures is safe: SwiftUI owns the same reference
    /// via `@State private var viewModel`, so both the coordinator closure and SwiftUI
    /// read and write the identical object.
    @State private var lifecycle: LifecycleSaveCoordinator

    /// The inbound drain seam (FR-16 cold start / FR-17 warm) — EXACTLY ONE
    /// instance per launch, built and drained by `ColdStartSeeder` inside `init()`.
    ///
    /// `@State`, not `let`, so the value has a single SwiftUI-owned identity for the
    /// whole session and is never rebuilt by a re-evaluated `body`.  It is written
    /// once (in `init()`) and read twice: the cold-start drain already ran before
    /// the store, and `body` hands this SAME instance to `ContentView` for the warm
    /// `.active` path.  Never mutated after launch — one drainer, two call sites.
    ///
    /// `init(viewModel:recovery:lifecycle:)` of `ContentView` is not yet extended
    /// here: the warm call site is added when `ContentView` accepts the drainer.
    @State private var drainer: ShareInboxDrainer

    // MARK: - Composition-root init (CM-1 single-instance DI)

    /// Constructs exactly one SettingsRepository and exactly one RecoveryRepository,
    /// then threads them through to the view model, lifecycle coordinator, and state
    /// properties (§4.1 / FR-03).
    ///
    /// Swift default-value property initialisers cannot reference sibling stored
    /// properties, so a custom `init()` is the canonical SwiftUI pattern for seeding
    /// multiple `@State` properties from shared instances constructed at the root.
    init() {
        let settingsRepo = IosSettingsFactoryKt.createIosSettingsRepository()
        let recoveryRepo = IosRecoveryFactoryKt.createIosRecoveryRepository()
        let vm = NoteViewModel(settings: settingsRepo)

        // Wire the single settings handle into both @State settings and NoteViewModel.
        // Using _settings = State(wrappedValue:) is the correct SwiftUI initializer
        // pattern for @State properties — do not assign to settings directly in init().
        _settings = State(wrappedValue: settingsRepo)
        _recovery = State(wrappedValue: recoveryRepo)
        _viewModel = State(wrappedValue: vm)

        // Build the lifecycle coordinator from the single recovery handle and the
        // already-constructed vm reference.  The coordinator pulls text via the
        // textProvider closure and resets via resetNote — NO save logic in the VM
        // (FR-12 / m4_gate check 5).  No new factory call (m4_gate check 4).
        let lifecycleCoordinator = LifecycleSaveCoordinator(
            recovery: recoveryRepo,
            textProvider: { vm.text },
            resetNote: { vm.populate($0) }
        )
        _lifecycle = State(wrappedValue: lifecycleCoordinator)

        // §4.3 cold start: ONE synchronous drain, here in init(), so the buffer is
        // already seeded when `body` is first evaluated.  Extracted rather than
        // inlined: this initialiser is at its size limit, and the seed ordering is
        // a contract under test (ColdStartSeederTests).  The drainer it returns is
        // the same instance the warm `.active` path must reuse (§5.1.5).
        _drainer = State(wrappedValue: ColdStartSeeder.seed(
            inbox: ShareInbox(),
            lifecycle: lifecycleCoordinator,
            viewModel: vm
        ))
    }

    // MARK: - Scene

    var body: some Scene {
        WindowGroup {
            // Pass all session-scoped instances into ContentView (§4.1 / §4.3 DI).
            //
            // viewModel: presentation state for the editor + chrome.
            // recovery: forwarded to ContentView → MenuViewModel → RecoveryListViewModel.
            // lifecycle: the FR-14 coordinator; ContentView drives it via scenePhase.
            //
            // ContentView builds MenuViewModel from these injected instances (DIP).
            // No new factory calls here — gate check 4 invariant (single settings
            // factory call in iosAppApp.init() only) is preserved.
            ContentView(
                viewModel: viewModel,
                recovery: recovery,
                lifecycle: lifecycle,
                drainer: drainer
            )
            // Wire appDelegate.onTerminate exactly once (guard == nil).
            // This is the earliest reliable site: @State is resolved before body
            // evaluates, but the wrapped value is NOT accessible in init() — only
            // the _lifecycle backing store is seeded there.  body runs on the main
            // actor; the closure is set on first evaluation and ignored on subsequent
            // re-evaluations (SwiftUI may call body multiple times).
            // R-11: applicationWillTerminate is best-effort and unreliable; the
            // .background save via ContentView.dispatchScenePhase is the primary path.
            .onAppear {
                if appDelegate.onTerminate == nil {
                    appDelegate.onTerminate = { [lifecycle] in
                        lifecycle.onTerminate()
                    }
                }
            }
        }
    }
}

// MARK: - Cold-start seed (§4.3 cold start / FR-16, FR-18, FR-20, NFR-01)

/// Seeds the editor from the App Group inbox exactly once, inside `init()`.
///
/// Spec refs: FR-16, FR-05, FR-08, FR-18, FR-20, NFR-01; §4.3 cold start,
/// §5.1.3 call sites, D4, EC-19.
///
/// `seed(inbox:lifecycle:viewModel:)` builds the ONE `ShareInboxDrainer` from the
/// production closures only — the shared normalization rule, the single
/// trim-guarded save path, and `NoteViewModel.populate` — then calls `drain()`
/// once and returns that same instance. `iosAppApp` stores it in `drainer`, so the
/// warm `.active` path (`ContentView.dispatchScenePhase`) reuses it instead of
/// building a second one: one drainer, two call sites, no ordering divergence.
///
/// When the drain actually seeded text, the coordinator is told to keep it
/// (`adoptLaunchSeed()`), because the launch-time `.active` arrives AFTER this
/// call and would otherwise reset the buffer to `""` with the inbox already
/// consumed — the shared text would be lost before the first frame.
///
/// Why a helper and not four inline lines: `iosAppApp.init()` is already at its
/// size limit, and the five ordered steps (read → normalize → save → populate →
/// clear) are the contract the cold-start cases pin.
///
/// Sequencing (NFR-01): every effect completes before `seed` returns, so the seed
/// is complete before `body` can be evaluated and the first rendered frame already
/// shows the inbound text (EC-19). No concurrency hop of any kind.
///
/// `@MainActor`: `ShareInboxDrainer` is main-actor isolated and both call sites —
/// the composition root and `.onChange(of: scenePhase)` — are on the main actor.
@MainActor
enum ColdStartSeeder {

    /// Builds the drainer, drains once, and hands the instance back.
    ///
    /// - Parameters:
    ///   - inbox: the App Group inbox shared with the Share Extension. Tests inject
    ///     `ShareInbox(containerURL:)`, so no case touches the real App Group.
    ///   - lifecycle: the single save coordinator. Its collision save is a
    ///     trim-guarded no-op on the empty cold-start buffer (FR-06, D4).
    ///   - viewModel: the single editor model. `populate` carries the seed and bumps
    ///     `populationRevision`, which is what makes the first `reconcileText`
    ///     reveal the top of the document (EC-19).
    /// - Returns: the drainer the warm activation path must reuse.
    static func seed(
        inbox: ShareInbox,
        lifecycle: LifecycleSaveCoordinator,
        viewModel: NoteViewModel
    ) -> ShareInboxDrainer {
        let drainer = ShareInboxDrainer(
            inbox: inbox,
            normalize: { IncomingTextKt.acceptIncomingText(raw: $0) },
            saveCurrentBuffer: { _ = lifecycle.saveCurrentBuffer() },
            populate: { viewModel.populate($0) }
        )
        if drainer.drain() {
            lifecycle.adoptLaunchSeed()
        }
        return drainer
    }
}
