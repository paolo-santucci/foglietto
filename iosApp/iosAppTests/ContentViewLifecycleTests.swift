// ContentViewLifecycleTests.swift
// iosAppTests
//
// Integration tests for the ContentView scene-phase dispatch seam (FR-14, T-02).
//
// Strategy:
//   `ContentView.dispatchScenePhase(_:to:drainer:)` is a @MainActor static method
//   that the `.onChange(of: scenePhase)` modifier calls at runtime.  Because
//   SwiftUI's @Environment(\.scenePhase) is not drivable from a unit test, the
//   dispatch logic is extracted into this testable static seam.  These tests drive
//   it directly, verifying that each ScenePhase maps to the correct coordinator
//   call — and that `.active` alone adds the warm inbound drain, AFTER the reset
//   (FR-17 / EC-17) — while the core method behaviours are fully covered by
//   LifecycleSaveCoordinatorTests.
//
// Spy naming:
//   `LifecycleWiringSpyRepository` — a distinct top-level name (not `SpyRecoveryRepository`)
//   to avoid symbol collision with the T-01 file-private spy in LifecycleSaveCoordinatorTests.
//   Both are file-private, which avoids link-time name collision, but using a unique name
//   is an additional safety belt following the M3 duplicate-stub CI-red lesson.
//
// M3 CI conventions (MANDATORY):
//   @MainActor at class level — LifecycleSaveCoordinator is @MainActor.
//   No async/await, no Task, no wall-clock sleeps.
//   import shared + @testable import iosApp.
//
// Spec refs: FR-14; QP §3.1 / T-02 "Failing test first" block; C-07.
//             FR-17, FR-18, FR-20, EC-17/EC-18; §5.1.5 warm drain ordering.
//
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import SwiftUI
import UIKit
import shared
@testable import iosApp

// ---------------------------------------------------------------------------
// MARK: - File-private spy
// ---------------------------------------------------------------------------

/// Spy `RecoveryRepository` for lifecycle-wiring tests.
///
/// Records every `save(text:)` and `trim(keep:)` call in ordered arrays so
/// assertions can inspect both the count and the argument values.
///
/// Named `LifecycleWiringSpyRepository` (not `SpyRecoveryRepository`) to avoid
/// symbol collision with the homonymous spy in `LifecycleSaveCoordinatorTests.swift`.
/// Both are `private` (file-private scope), but a unique name is the safe belt.
private final class LifecycleWiringSpyRepository: RecoveryRepository {

    private(set) var saveCalls: [String] = []
    private(set) var trimCalls: [Int32] = []

    // MARK: RecoveryRepository conformance

    func save(text: String) -> String {
        saveCalls.append(text)
        return "/recovery/wiring/\(saveCalls.count).txt"
    }

    func trim(keep: Int32) {
        trimCalls.append(keep)
    }

    func list() -> [RecoveryNote] { [] }
    func read(path: String) -> String? { nil }
    func delete(path: String) {}
    func deleteAll() {}
}

// ---------------------------------------------------------------------------
// MARK: - Stub SettingsRepository
// ---------------------------------------------------------------------------

/// File-private stub so the warm-drain cases can construct the REAL `NoteViewModel`
/// (`populate` is the seed target) without touching UserDefaults.
///
/// Same-named stubs live in neighbouring test files; `private` keeps each file's
/// copy isolated (M3 duplicate-stub lesson).
private final class StubSettingsRepository: SettingsRepository {
    var storedSettings = AppSettings(colorScheme: AppColorScheme.follow, fontSizeIndex: 8)

    func load() -> AppSettings { storedSettings }
    func save(settings: AppSettings) { storedSettings = settings }
}

// ---------------------------------------------------------------------------
// MARK: - ContentViewLifecycleTests
// ---------------------------------------------------------------------------

/// Tests for `ContentView.dispatchScenePhase(_:to:drainer:)` — the static seam that
/// routes ScenePhase transitions to the correct `LifecycleSaveCoordinator` method,
/// plus the `.active`-only warm inbound drain (FR-17, EC-17/EC-18).
///
/// `@MainActor`: `LifecycleSaveCoordinator` and `ShareInboxDrainer` are `@MainActor` and
/// `ContentView.dispatchScenePhase` is `@MainActor static`; all construction and
/// calls must occur on the main actor.
@MainActor
final class ContentViewLifecycleTests: XCTestCase {

    // -----------------------------------------------------------------------
    // MARK: - .active — dispatchScenePhase calls onActive (FR-14)
    // -----------------------------------------------------------------------

    /// Dispatching `.active` must call `coordinator.onActive()`, which calls
    /// `resetNote("")` exactly once.
    ///
    /// This verifies the `.active → onActive()` branch of the dispatch switch
    /// (C-07: cold-start `.active` does not fire via `.onChange(of:)`, so the
    /// reset does not run on launch — tested here only via the seam).
    func test_active_dispatchesOnActive() {
        // Arrange: a coordinator whose resetNote closure records calls.
        var resetCapture: [String] = []
        let spy = LifecycleWiringSpyRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { "" },
            resetNote: { value in resetCapture.append(value) }
        )

        // Act: drive the dispatch seam with .active.
        ContentView.dispatchScenePhase(.active, to: coordinator)

        // Assert: onActive() ran → resetNote("") was recorded exactly once.
        XCTAssertEqual(resetCapture, [""],
            "dispatchScenePhase(.active) must call onActive() → resetNote(\"\") once (FR-14)")
        // No save must have occurred on .active.
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "dispatchScenePhase(.active) must not trigger a save (FR-14)")
    }

    // -----------------------------------------------------------------------
    // MARK: - .background — dispatchScenePhase calls onBackground (FR-14)
    // -----------------------------------------------------------------------

    /// Dispatching `.background` with buffer text "hello" must call
    /// `coordinator.onBackground()`, which (synchronously, before the call returns)
    /// invokes `SaveNoteToRecovery.invoke(text: "hello")` → spy records the save.
    ///
    /// This verifies the `.background → onBackground()` branch and that the
    /// text is pulled from the injected `textProvider`, not passed as an argument.
    func test_background_dispatchesOnBackground_withVMText() {
        // Arrange: coordinator with textProvider returning "hello".
        let spy = LifecycleWiringSpyRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { "hello" },
            resetNote: { _ in }
        )

        // Act: drive the dispatch seam with .background.
        ContentView.dispatchScenePhase(.background, to: coordinator)

        // Assert: onBackground() ran synchronously → save("hello") recorded.
        XCTAssertEqual(spy.saveCalls, ["hello"],
            "dispatchScenePhase(.background) must call onBackground() which saves " +
            "the textProvider's text synchronously before returning (FR-14 / NFR-01)")
    }

    // -----------------------------------------------------------------------
    // MARK: - .inactive — dispatchScenePhase is a no-op (FR-14)
    // -----------------------------------------------------------------------

    /// Dispatching `.inactive` must call `coordinator.onInactive()`, which is a
    /// deliberate no-op: zero saves, zero trims, zero resets.
    func test_inactive_isNoOp() {
        // Arrange.
        var resetCapture: [String] = []
        let spy = LifecycleWiringSpyRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { "irrelevant" },
            resetNote: { value in resetCapture.append(value) }
        )

        // Act: drive the dispatch seam with .inactive.
        ContentView.dispatchScenePhase(.inactive, to: coordinator)

        // Assert: onInactive() ran → zero side effects.
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "dispatchScenePhase(.inactive) must not trigger a save (FR-14 no-op)")
        XCTAssertTrue(spy.trimCalls.isEmpty,
            "dispatchScenePhase(.inactive) must not trigger a trim (FR-14 no-op)")
        XCTAssertTrue(resetCapture.isEmpty,
            "dispatchScenePhase(.inactive) must not trigger a buffer reset (FR-14 no-op)")
    }

    // -----------------------------------------------------------------------
    // MARK: - .active — reset BEFORE the warm drain (FR-17, EC-17, C-2)
    // -----------------------------------------------------------------------

    /// `.active` must run `coordinator.onActive()` FIRST and `drainer.drain()` SECOND.
    ///
    /// The order is the contract, not an implementation detail: the reset empties the
    /// buffer, so a seed that landed first would be wiped (assessment C-2). The journal
    /// records the two observable steps; the repository spy proves the drain's collision
    /// save is trim-guarded against the now-empty buffer (OQ-10 — zero repository calls).
    func test_active_resetsBeforeDraining_thenSeedsOnce() {
        // Arrange: one real view model behind both the reset and the seed, plus a
        // journal shared by the coordinator's reset and the drainer's normalize.
        var journal: [String] = []
        let spy = LifecycleWiringSpyRepository()
        let viewModel = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { viewModel.text },
            resetNote: { value in
                journal.append("reset")
                viewModel.populate(value)
            }
        )
        let drainer = ShareInboxDrainer(
            read: { "new" },
            clear: {},
            normalize: { raw in
                journal.append("normalize")
                return raw
            },
            saveCurrentBuffer: { _ = coordinator.saveCurrentBuffer() },
            populate: { viewModel.populate($0) }
        )
        let revisionsBefore = viewModel.populationRevision

        // Act.
        ContentView.dispatchScenePhase(.active, to: coordinator, drainer: drainer)

        // Assert: the reset landed before the drain; both mutations, one turn.
        XCTAssertEqual(journal, ["reset", "normalize"],
            "dispatchScenePhase(.active) must reset BEFORE draining — a seed before " +
            "the reset is wiped (FR-17 / C-2)")
        XCTAssertEqual(viewModel.text, "new",
            "the drain's populate must leave the inbound text in the buffer (FR-17)")
        XCTAssertEqual(viewModel.populationRevision, revisionsBefore + 2,
            "reset and seed must both land in the same synchronous turn (FR-17 / NFR-01)")
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "the drain's collision save is trim-guarded after the reset emptied the " +
            "buffer, so it makes ZERO repository calls (OQ-10)")
    }

    /// The editor's first `updateUIView` tick after `.active` must show the seed (EC-19).
    ///
    /// `NoteEditor.updateUIView` reconciles buffer text through
    /// `Coordinator.reconcileText` (NoteEditor.swift:245); the update leg has no
    /// context-free seam that runs the whole tick, so this drives that same call on the
    /// coordinator the editor itself builds.
    func test_active_firstUpdateTickRendersTheSeed() {
        // Arrange.
        let spy = LifecycleWiringSpyRepository()
        let viewModel = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { viewModel.text },
            resetNote: { value in viewModel.populate(value) }
        )
        let drainer = ShareInboxDrainer(
            read: { "new" },
            clear: {},
            normalize: { $0 },
            saveCurrentBuffer: { _ = coordinator.saveCurrentBuffer() },
            populate: { viewModel.populate($0) }
        )
        let editor = NoteEditor(viewModel: viewModel)
        let editorCoordinator = editor.makeCoordinator()
        let textView = UITextView()

        // Act: warm activation, then the first update tick.
        ContentView.dispatchScenePhase(.active, to: coordinator, drainer: drainer)
        editorCoordinator.reconcileText(in: textView)

        // Assert.
        XCTAssertEqual(textView.text, "new",
            "the first post-activation updateUIView must render the inbound seed, " +
            "not the reset buffer (FR-17 / EC-19)")
    }

    /// The two-argument call site must keep its pre-drain behaviour (FR-14 regression).
    ///
    /// `drainer` is defaulted, so any caller that has not been migrated still compiles
    /// and still performs the `.active` reset — with no save and no seed.
    func test_active_twoArgumentForm_stillResetsOnly() {
        // Arrange: a reset that records but does NOT populate, so "no seed" is provable.
        var resetCapture: [String] = []
        let spy = LifecycleWiringSpyRepository()
        let viewModel = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { viewModel.text },
            resetNote: { value in resetCapture.append(value) }
        )

        // Act: two-argument form — no drainer.
        ContentView.dispatchScenePhase(.active, to: coordinator)

        // Assert.
        XCTAssertEqual(resetCapture, [""],
            "the defaulted-drainer form must still call onActive() exactly once (FR-14)")
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "the defaulted-drainer form must not save (FR-14)")
        XCTAssertEqual(viewModel.populationRevision, 0,
            "no drainer → no seed/populate on .active (FR-14 regression)")
        XCTAssertEqual(viewModel.text, "",
            "no drainer → the buffer stays as the reset left it (FR-14 regression)")
    }

    // -----------------------------------------------------------------------
    // MARK: - .background / .inactive — never drain (EC-18)
    // -----------------------------------------------------------------------

    /// `.background` must save the pending payload and NEVER touch the drainer (EC-18).
    func test_background_neverDrains() {
        // Arrange: every drainer effect records, so "never invoked" is one assertion.
        let spy = LifecycleWiringSpyRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { "pending" },
            resetNote: { _ in }
        )
        var drainSteps: [String] = []
        let drainer = ShareInboxDrainer(
            read: { drainSteps.append("read"); return "new" },
            clear: { drainSteps.append("clear") },
            normalize: { drainSteps.append("normalize"); return $0 },
            saveCurrentBuffer: { drainSteps.append("save") },
            populate: { _ in drainSteps.append("populate") }
        )

        // Act.
        ContentView.dispatchScenePhase(.background, to: coordinator, drainer: drainer)

        // Assert.
        XCTAssertEqual(spy.saveCalls, ["pending"],
            "dispatchScenePhase(.background) must still save the buffer exactly once (FR-14)")
        XCTAssertTrue(drainSteps.isEmpty,
            "the inbound drain is .active-only — .background must never seed (EC-18)")
    }

    /// `.inactive` with a pending payload must do nothing at all (EC-18).
    func test_inactive_neverDrains() {
        // Arrange.
        var resetCapture: [String] = []
        let spy = LifecycleWiringSpyRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { "pending" },
            resetNote: { value in resetCapture.append(value) }
        )
        var drainSteps: [String] = []
        let drainer = ShareInboxDrainer(
            read: { drainSteps.append("read"); return "new" },
            clear: { drainSteps.append("clear") },
            normalize: { drainSteps.append("normalize"); return $0 },
            saveCurrentBuffer: { drainSteps.append("save") },
            populate: { _ in drainSteps.append("populate") }
        )

        // Act.
        ContentView.dispatchScenePhase(.inactive, to: coordinator, drainer: drainer)

        // Assert: the FR-14 no-op survives, and the inbox is not consumed.
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "dispatchScenePhase(.inactive) must not trigger a save (FR-14 no-op)")
        XCTAssertTrue(spy.trimCalls.isEmpty,
            "dispatchScenePhase(.inactive) must not trigger a trim (FR-14 no-op)")
        XCTAssertTrue(resetCapture.isEmpty,
            "dispatchScenePhase(.inactive) must not reset the buffer (FR-14 no-op)")
        XCTAssertTrue(drainSteps.isEmpty,
            "the inbound drain is .active-only — .inactive must never seed (EC-18)")
    }

    // -----------------------------------------------------------------------
    // MARK: - empty inbox — reset only (FR-20)
    // -----------------------------------------------------------------------

    /// `.active` with nothing in the inbox: reset runs, nothing is saved or seeded,
    /// the absent entry is consumed, and no throw escapes (FR-20).
    func test_active_emptyInbox_resetsOnly() {
        // Arrange.
        var resetCapture: [String] = []
        var clearCount = 0
        let spy = LifecycleWiringSpyRepository()
        let viewModel = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { viewModel.text },
            resetNote: { value in
                resetCapture.append(value)
                viewModel.populate(value)
            }
        )
        let drainer = ShareInboxDrainer(
            read: { nil },
            clear: { clearCount += 1 },
            normalize: { $0 },
            saveCurrentBuffer: { _ = coordinator.saveCurrentBuffer() },
            populate: { viewModel.populate($0) }
        )

        // Act: must return normally (no throw, no crash) with an empty inbox.
        ContentView.dispatchScenePhase(.active, to: coordinator, drainer: drainer)

        // Assert.
        XCTAssertEqual(resetCapture, [""],
            "the .active reset still runs when the inbox is empty (FR-14)")
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "an empty inbox seeds nothing, so nothing is saved (FR-20)")
        XCTAssertEqual(viewModel.text, "",
            "an empty inbox leaves the buffer empty (FR-20)")
        XCTAssertEqual(viewModel.populationRevision, 1,
            "only the reset's populate ran — the drain seeded nothing (FR-20)")
        XCTAssertEqual(clearCount, 1,
            "the absent entry is consumed exactly once (FR-20)")
    }
}
