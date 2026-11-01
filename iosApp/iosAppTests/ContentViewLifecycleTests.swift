// ContentViewLifecycleTests.swift
// iosAppTests
//
// Integration tests for the ContentView scene-phase dispatch seam (FR-14, T-02).
//
// Strategy:
//   `ContentView.dispatchScenePhase(_:to:)` is a @MainActor static method that
//   the `.onChange(of: scenePhase)` modifier calls at runtime.  Because SwiftUI's
//   @Environment(\.scenePhase) is not drivable from a unit test, the dispatch logic
//   is extracted into this testable static seam.  These tests drive it directly,
//   verifying that each ScenePhase maps to the correct zero-arg coordinator method
//   (the core method behaviours are fully covered by LifecycleSaveCoordinatorTests).
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
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import SwiftUI
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
// MARK: - ContentViewLifecycleTests
// ---------------------------------------------------------------------------

/// Tests for `ContentView.dispatchScenePhase(_:to:)` — the static seam that
/// routes ScenePhase transitions to the correct `LifecycleSaveCoordinator` method.
///
/// `@MainActor`: `LifecycleSaveCoordinator` is `@MainActor` and
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
}
