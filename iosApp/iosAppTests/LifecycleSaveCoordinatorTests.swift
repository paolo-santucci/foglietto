// LifecycleSaveCoordinatorTests.swift
// iosAppTests
//
// XCTest coverage for LifecycleSaveCoordinator — burst-guarded synchronous save
// core and the four ScenePhase / app-lifecycle seam methods (FR-14, NFR-01).
//
// Test-first mandate (QP T-01): these tests FAIL before the four method bodies are
// filled; they PASS once the implementation is correct. They do not depend on any
// SwiftUI ScenePhase machinery — the coordinator's zero-arg seam methods are driven
// directly.
//
// Spy convention:
//   SpyRecoveryRepository records EVERY call to save(text:) and trim(keep:) in
//   declaration order so tests can assert both the COUNT and the ARGS from a single
//   ordered array (call-order contract from SaveNoteToRecovery: save then trim).
//
// Synchrony assertion strategy (NFR-01):
//   IMMEDIATELY after coordinator.onBackground() returns (no await, no RunLoop pump),
//   spy.saveCalls is checked. If save were deferred (Task / DispatchQueue.async),
//   the call would not appear yet and the test would fail.
//
// M3 CI conventions (MANDATORY):
//   @MainActor at class level — LifecycleSaveCoordinator is @MainActor.
//   File-private stubs — never hit real NSUserDefaults/disk.
//   No async/await, no Task, no wall-clock sleeps.
//   import shared + @testable import iosApp.
//
// Spec refs: FR-14, NFR-01; QP §3.1 / T-01 "Failing test first" block.
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import shared
@testable import iosApp

// ---------------------------------------------------------------------------
// MARK: - File-private spy
// ---------------------------------------------------------------------------

/// Spy RecoveryRepository that records every save(text:) and trim(keep:) call
/// in order. Used to verify the `SaveNoteToRecovery` use-case call sequence
/// (save raw text → trim(10)) and that empty/whitespace input produces zero calls.
///
/// `final` + `@MainActor`-safe: the coordinator is @MainActor so all method calls
/// arrive on the main actor; no concurrency hazard.
private final class SpyRecoveryRepository: RecoveryRepository {

    // Ordered record of every save/trim call with its arg.
    private(set) var saveCalls: [String] = []
    private(set) var trimCalls: [Int32] = []

    // MARK: RecoveryRepository conformance

    func save(text: String) -> String {
        saveCalls.append(text)
        return "/recovery/\(saveCalls.count).txt"
    }

    func trim(keep: Int32) {
        trimCalls.append(keep)
    }

    // Remaining protocol members — inert; the save core never calls them.
    func list() -> [RecoveryNote] { [] }
    func read(path: String) -> String? { nil }
    func delete(path: String) {}
    func deleteAll() {}
}

// ---------------------------------------------------------------------------
// MARK: - LifecycleSaveCoordinatorTests
// ---------------------------------------------------------------------------

/// Tests for LifecycleSaveCoordinator — burst-guarded synchronous save and
/// the four lifecycle seam methods.
///
/// `@MainActor`: LifecycleSaveCoordinator is @MainActor; constructing or calling
/// it from a non-@MainActor context requires async machinery. Annotating the test
/// class is the established M3 convention for @Observable / @MainActor targets.
@MainActor
final class LifecycleSaveCoordinatorTests: XCTestCase {

    // MARK: - Helpers

    /// Builds a fresh SpyRecoveryRepository + coordinator with the given text provider.
    ///
    /// - Parameters:
    ///   - textProvider:  closure returning the simulated buffer text at save time.
    ///   - resetCapture:  inout array appended to by the resetNote closure.
    /// - Returns: (spy, coordinator) — spy is held by the caller for assertions.
    private func makeCoordinator(
        textProvider: @escaping () -> String,
        resetCapture: UnsafeMutablePointer<[String]>? = nil
    ) -> (spy: SpyRecoveryRepository, coordinator: LifecycleSaveCoordinator) {
        let spy = SpyRecoveryRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: textProvider,
            resetNote: { text in resetCapture?.pointee.append(text) }
        )
        return (spy, coordinator)
    }

    // -----------------------------------------------------------------------
    // MARK: - onBackground() — synchronous save + trim10 (NFR-01 / FR-14)

    /// onBackground() with non-empty text → spy.saveCalls == ["hello"] and
    /// spy.trimCalls == [10] IMMEDIATELY after the call returns (no await / pump).
    /// Proves the save is synchronous and that SaveNoteToRecovery's call order
    /// (save raw → trim(10)) is respected.
    func test_onBackground_savesExactlyOnce_synchronously_withTrim10() {
        var currentText = "hello"
        let (spy, coordinator) = makeCoordinator(textProvider: { currentText })
        _ = currentText  // suppress unused-variable warning

        coordinator.onBackground()

        XCTAssertEqual(spy.saveCalls, ["hello"],
            "onBackground() must call save(text:) exactly once with the provider text " +
            "BEFORE the method returns (NFR-01 synchrony / FR-14)")
        XCTAssertEqual(spy.trimCalls, [Int32(10)],
            "onBackground() must call trim(keep:10) exactly once after save (FR-14 / SaveNoteToRecovery call order)")
    }

    // -----------------------------------------------------------------------
    // MARK: - onBackground() — empty/whitespace trim-guard (FR-14 / EC-06)

    /// When the buffer is empty or whitespace-only, onBackground() makes zero
    /// repository calls — the SaveNoteToRecovery trim-guard fires and returns nil.
    func test_onBackground_emptyOrWhitespace_zeroRepoCalls() {
        let currentText = "   \n "
        let (spy, coordinator) = makeCoordinator(textProvider: { currentText })

        coordinator.onBackground()

        XCTAssertTrue(spy.saveCalls.isEmpty,
            "onBackground() with whitespace-only text must make zero save calls (EC-06 trim-guard)")
        XCTAssertTrue(spy.trimCalls.isEmpty,
            "onBackground() with whitespace-only text must make zero trim calls (EC-06 trim-guard)")
    }

    // -----------------------------------------------------------------------
    // MARK: - onBackground() — burst guard (FR-14 / QP §Integration constraints)

    /// A second onBackground() without an intervening onActive() must NOT trigger
    /// a second save — the burst guard prevents a double-write.
    func test_onBackground_burstGuard_secondBackgroundNoSecondSave() {
        var currentText = "hello"
        let (spy, coordinator) = makeCoordinator(textProvider: { currentText })
        _ = currentText

        coordinator.onBackground()   // arms the guard
        currentText = "world"
        coordinator.onBackground()   // guard is still set → no second save

        XCTAssertEqual(spy.saveCalls.count, 1,
            "burst guard must prevent a second save when .background fires again without .active (FR-14)")
    }

    // -----------------------------------------------------------------------
    // MARK: - onActive() — resets buffer + clears burst guard (FR-14)

    /// onActive() must call resetNote("") AND clear the burst guard so that
    /// the next onBackground() triggers a fresh save.
    func test_onActive_resetsNote_andClearsGuard() {
        var currentText = "hello"
        var resetCalls: [String] = []
        let spy = SpyRecoveryRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { currentText },
            resetNote: { resetCalls.append($0) }
        )

        coordinator.onBackground()             // saves "hello", arms guard
        coordinator.onActive()                 // resets buffer, clears guard
        currentText = "b"
        coordinator.onBackground()             // guard is clear → saves "b"

        XCTAssertEqual(resetCalls, [""],
            "onActive() must call resetNote(\"\") exactly once (FR-14 .active reset)")
        XCTAssertEqual(spy.saveCalls, ["hello", "b"],
            "onActive() must clear the burst guard so the next onBackground() saves again (FR-14)")
    }

    // -----------------------------------------------------------------------
    // MARK: - onInactive() — no-op (FR-14)

    /// onInactive() must produce zero save calls, zero trim calls, and zero
    /// resetNote calls — it is a deliberate no-op per FR-14.
    func test_onInactive_isNoOp() {
        var resetCalls: [String] = []
        let spy = SpyRecoveryRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { "some text" },
            resetNote: { resetCalls.append($0) }
        )

        coordinator.onInactive()

        XCTAssertTrue(spy.saveCalls.isEmpty,
            "onInactive() must make zero save calls — it is a no-op (FR-14)")
        XCTAssertTrue(spy.trimCalls.isEmpty,
            "onInactive() must make zero trim calls — it is a no-op (FR-14)")
        XCTAssertTrue(resetCalls.isEmpty,
            "onInactive() must make zero resetNote calls — it is a no-op (FR-14)")
    }

    // -----------------------------------------------------------------------
    // MARK: - onTerminate() — unguarded best-effort save (R-11)

    /// onTerminate() must save even when the burst guard is already armed
    /// (i.e. a prior onBackground() ran without an intervening onActive()).
    /// R-11: terminate save is best-effort and UNGUARDED.
    func test_onTerminate_invokesSave_unguarded() {
        var currentText = "hello"
        let (spy, coordinator) = makeCoordinator(textProvider: { currentText })

        coordinator.onBackground()             // saves "hello", arms guard
        currentText = "b"
        coordinator.onTerminate()              // unguarded → saves "b" despite guard

        XCTAssertEqual(spy.saveCalls, ["hello", "b"],
            "onTerminate() must save unguarded — guard from prior onBackground() must not block it (R-11)")
    }
}
