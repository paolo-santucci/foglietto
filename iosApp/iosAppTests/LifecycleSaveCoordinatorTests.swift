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
//
// sp-20261114 TASK-04 additions: `saveCurrentBuffer()` is the ONE trim-guarded
// save path (the inbound drain's collision save). onBackground() / onTerminate()
// must delegate to it (D6), the path is burst-guard-free, and the failure contract
// (FR-07 / EC-11: no `catch`, no async hop) is asserted by reading the
// coordinator's own source through `#filePath` — no bundle, no device, no App Group.

import XCTest
import Foundation
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

    // -----------------------------------------------------------------------
    // MARK: - saveCurrentBuffer() — non-blank save + trim(10) (FR-06 / D6)
    // -----------------------------------------------------------------------

    /// The drain's one save call: the RAW provider text reaches SaveNoteToRecovery
    /// exactly once, trim(keep: 10) follows it, and the buffer is reported safe to
    /// replace.
    func test_saveCurrentBuffer_nonBlank_savesOnce_withTrim10_andReportsPersisted() {
        let (spy, coordinator) = makeCoordinator(textProvider: { "draft" })

        let persisted = coordinator.saveCurrentBuffer()

        XCTAssertEqual(spy.saveCalls, ["draft"],
            "saveCurrentBuffer() must pass the raw provider text to SaveNoteToRecovery exactly once (FR-06)")
        XCTAssertEqual(spy.trimCalls, [Int32(10)],
            "saveCurrentBuffer() must call trim(keep: 10) once after the save — the same guard " +
            "onBackground() uses (FR-06 / D6)")
        XCTAssertTrue(persisted,
            "a completed save must report the buffer safe to replace (FR-07)")
    }

    // -----------------------------------------------------------------------
    // MARK: - saveCurrentBuffer() — blank buffer, zero repository calls (EC-12)
    // -----------------------------------------------------------------------

    /// Whitespace-only buffer: the shared trim guard suppresses the save, so the
    /// repository records zero writes and the method returns nil.
    func test_saveCurrentBuffer_whitespaceOnly_reportsPersisted_zeroRepoCalls() {
        let (spy, coordinator) = makeCoordinator(textProvider: { "   " })

        let persisted = coordinator.saveCurrentBuffer()

        XCTAssertTrue(persisted,
            "a blank buffer is safe to replace — there was nothing to persist (EC-12)")
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "saveCurrentBuffer() must make zero save calls for a whitespace-only buffer — " +
            "the trim guard must not be bypassed (EC-12)")
        XCTAssertTrue(spy.trimCalls.isEmpty,
            "saveCurrentBuffer() must make zero trim calls for a whitespace-only buffer (EC-12)")
    }

    /// Empty buffer: same contract as the whitespace case.
    func test_saveCurrentBuffer_empty_reportsPersisted_zeroRepoCalls() {
        let (spy, coordinator) = makeCoordinator(textProvider: { "" })

        let persisted = coordinator.saveCurrentBuffer()

        XCTAssertTrue(persisted,
            "an empty buffer is safe to replace — there was nothing to persist (EC-12)")
        XCTAssertTrue(spy.saveCalls.isEmpty,
            "saveCurrentBuffer() must make zero save calls for an empty buffer (EC-12)")
        XCTAssertTrue(spy.trimCalls.isEmpty,
            "saveCurrentBuffer() must make zero trim calls for an empty buffer (EC-12)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Delegation: one save code path (D6)
    // -----------------------------------------------------------------------

    /// onBackground() must produce exactly one save through saveCurrentBuffer().
    /// The assertion is count-based: a second (duplicated) call site inside the
    /// seam would make saveCalls.count == 2 and fail here.
    func test_onBackground_savesOnceThroughTheSharedPath() {
        let (spy, coordinator) = makeCoordinator(textProvider: { "episode" })

        coordinator.onBackground()

        XCTAssertEqual(spy.saveCalls, ["episode"],
            "onBackground() must save exactly once through saveCurrentBuffer() (D6)")
        XCTAssertEqual(spy.trimCalls, [Int32(10)],
            "onBackground() must apply trim(keep: 10) exactly once through the shared path (D6)")
    }

    /// onTerminate() must produce exactly one save through saveCurrentBuffer(),
    /// and it stays unguarded (no prior onBackground() in this test, so a stray
    /// count of 0 or 2 would be caught).
    func test_onTerminate_savesOnceThroughTheSharedPath() {
        let (spy, coordinator) = makeCoordinator(textProvider: { "episode" })

        coordinator.onTerminate()

        XCTAssertEqual(spy.saveCalls, ["episode"],
            "onTerminate() must save exactly once through saveCurrentBuffer() (D6)")
        XCTAssertEqual(spy.trimCalls, [Int32(10)],
            "onTerminate() must apply trim(keep: 10) exactly once through the shared path (D6)")
    }

    /// D6: the collision save is burst-guard-FREE, so a saveCurrentBuffer() call
    /// between .active and .background() neither arms nor consumes the episode
    /// guard: the subsequent onBackground() still saves, giving exactly 2 writes.
    func test_saveCurrentBuffer_betweenActiveAndBackground_neitherSuppressesNorDuplicates() {
        var currentText = "old"
        let (spy, coordinator) = makeCoordinator(textProvider: { currentText })

        coordinator.onActive()                 // resets the buffer, clears the guard
        currentText = "draft to rescue"
        _ = coordinator.saveCurrentBuffer()    // collision save → 1st repository write
        currentText = "edited draft"
        coordinator.onBackground()             // episode save must still happen → 2nd write

        XCTAssertEqual(spy.saveCalls, ["draft to rescue", "edited draft"],
            "a collision save must neither suppress nor duplicate the episode save — " +
            "exactly 2 writes, each with the text current at that moment (D6 / FR-06)")
        XCTAssertEqual(spy.trimCalls, [Int32(10), Int32(10)],
            "both writes go through the shared trim(10) path exactly once each (D6)")
    }

    // -----------------------------------------------------------------------
    // MARK: - An inbound seed survives activation bounces (FR-16 / FR-29)
    // -----------------------------------------------------------------------

    /// The seed is applied before the inbox entry is deleted, so it cannot be
    /// re-drained. `.active` is delivered more than once per launch, therefore the
    /// guard must hold across every activation until the app really leaves.
    func test_onActive_keepsTheInboundSeedAcrossActivationBounces() {
        var resetCalls: [String] = []
        let coordinator = LifecycleSaveCoordinator(
            recovery: SpyRecoveryRepository(),
            textProvider: { "shared text" },
            resetNote: { resetCalls.append($0) }
        )

        coordinator.adoptInboundSeed()
        coordinator.onActive()          // launch activation
        coordinator.onInactive()        // the bounce a URL hand-off produces
        coordinator.onActive()
        coordinator.onInactive()
        coordinator.onActive()

        XCTAssertEqual(resetCalls, [],
            "no activation may reset an inbound seed — the inbox is already drained, " +
            "so every reset loses the shared text for good (FR-16)")
    }

    /// Leaving the foreground ends the exception: a seed the user has backgrounded
    /// is an ordinary ephemeral buffer and is cleared on the next activation.
    func test_onActive_afterBackground_stillResetsTheInboundSeed() {
        var resetCalls: [String] = []
        let coordinator = LifecycleSaveCoordinator(
            recovery: SpyRecoveryRepository(),
            textProvider: { "shared text" },
            resetNote: { resetCalls.append($0) }
        )

        coordinator.adoptInboundSeed()
        coordinator.onBackground()               // the app left the foreground
        coordinator.onActive()                   // coming back

        XCTAssertEqual(resetCalls, [""],
            "an inbound seed must not survive a .background — the ephemeral contract " +
            "clears the buffer on the next .active like any other note (FR-14)")
    }

    /// The guard is dropped even when the background save is skipped by the burst
    /// guard, so a skipped save can never keep the inbound seed alive.
    func test_onBackground_dropsTheInboundSeedEvenWhenTheBurstGuardSkipsTheSave() {
        var resetCalls: [String] = []
        var currentText = "shared text"
        let spy = SpyRecoveryRepository()
        let coordinator = LifecycleSaveCoordinator(
            recovery: spy,
            textProvider: { currentText },
            resetNote: { resetCalls.append($0) }
        )

        coordinator.adoptInboundSeed()
        coordinator.onBackground()               // saves and arms the burst guard
        currentText = "edited"
        coordinator.onBackground()               // burst-guarded: no second save
        coordinator.onActive()

        XCTAssertEqual(spy.saveCalls, ["shared text"],
            "the second .background stays burst-guarded (FR-14)")
        XCTAssertEqual(resetCalls, [""],
            "the inbound-seed guard must be dropped before the burst guard can skip " +
            "the save, otherwise a skipped save would keep the seed alive across an exit")
    }

    /// A plain `.active` with no inbound seed still resets, exactly as before.
    func test_onActive_withoutAnInboundSeed_stillResets() {
        var resetCalls: [String] = []
        let coordinator = LifecycleSaveCoordinator(
            recovery: SpyRecoveryRepository(),
            textProvider: { "typed by the user" },
            resetNote: { resetCalls.append($0) }
        )

        coordinator.onActive()

        XCTAssertEqual(resetCalls, [""],
            "the seed exception is scoped to an inbound seed: an ordinary activation " +
            "resets the ephemeral buffer (FR-14)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Failure path + synchrony — source inspection (FR-07 / NFR-01)
    // -----------------------------------------------------------------------

    /// FR-07 / EC-11: a Kotlin exception must terminate the process before the
    /// inbound seed, so this file may contain no `catch` at all, and no
    /// async-introducing construct, so the write is complete when the call returns.
    /// D6 adds: exactly one save site, and both lifecycle seams delegate to it.
    func test_source_noCatch_noAsyncConstructs_singleSavePath() throws {
        let codeLines = try coordinatorCodeLines()
        let code = codeLines.joined(separator: "\n")

        XCTAssertFalse(code.contains("catch"),
            "LifecycleSaveCoordinator.swift must contain no 'catch' — the save failure is a " +
            "value returned by attemptSave, not an exception handled here (FR-07)")

        for token in ["Task", "DispatchQueue", "async", "await"] {
            XCTAssertEqual(wordOccurrences(of: token, in: code), 0,
                "LifecycleSaveCoordinator.swift must contain zero '\(token)' occurrences " +
                "outside comments — the save path is synchronous (NFR-01)")
        }

        let saveSites = codeLines.filter { $0.contains("saveUseCase.attemptSave(") }
        XCTAssertEqual(saveSites.count, 1,
            "exactly one save code path must exist: one 'saveUseCase.attemptSave(' call site (D6)")

        XCTAssertFalse(
            code.contains("saveUseCase.invoke("),
            "the coordinator must not call the throwing invoke( — a Kotlin exception crossing " +
            "into Swift terminates the process (FR-07 / EC-11)"
        )

        let delegationSites = codeLines.filter {
            $0.contains("saveCurrentBuffer()") && !$0.contains("func saveCurrentBuffer(")
        }
        XCTAssertEqual(delegationSites.count, 2,
            "onBackground() and onTerminate() must both delegate to saveCurrentBuffer() — " +
            "exactly 2 call sites, no other save entry point (D6)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Source inspection helpers
    // -----------------------------------------------------------------------

    /// Non-comment lines of `iosApp/iosApp/LifecycleSaveCoordinator.swift`,
    /// resolved from this test file's own compile-time `#filePath` (the
    /// `ShareExtensionContractTests` technique) — no bundle resource needed.
    private func coordinatorCodeLines() throws -> [String] {
        // `#filePath` is absolute for Xcode builds of this target; the extra
        // candidates only cover a toolchain that emits a repo-relative path.
        let testFileDirectory = URL(filePath: #filePath).deletingLastPathComponent()
        let iosAppDirectory = testFileDirectory.deletingLastPathComponent()
        let repositoryRoot = iosAppDirectory.deletingLastPathComponent()
        let relativeSource = "iosApp/iosApp/LifecycleSaveCoordinator.swift"

        let candidates = [
            iosAppDirectory.appending(path: "iosApp/LifecycleSaveCoordinator.swift"),
            repositoryRoot.appending(path: relativeSource),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: relativeSource)
        ]

        let existingCandidate = candidates.first { FileManager.default.fileExists(atPath: $0.path) }
        guard let sourceURL = existingCandidate else {
            throw SourceInspectionError.coordinatorSourceNotFound(paths: candidates.map(\.path))
        }

        let data = try Data(contentsOf: sourceURL)
        let source = String(decoding: data, as: UTF8.self)
        return source
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
    }

    /// Counts whole-word occurrences of `token`, so `beginBackgroundTask` /
    /// `endBackgroundTask` never match `Task`.
    private func wordOccurrences(of token: String, in code: String) -> Int {
        code
            .split { !($0.isLetter || $0.isNumber || $0 == "_") }
            .filter { String($0) == token }
            .count
    }

    /// Raised when the coordinator source cannot be located from `#filePath`;
    /// failing the test (not skipping) keeps the FR-07 / D6 assertions honest.
    private enum SourceInspectionError: Error, CustomStringConvertible {
        case coordinatorSourceNotFound(paths: [String])

        var description: String {
            switch self {
            case let .coordinatorSourceNotFound(paths):
                return "LifecycleSaveCoordinator.swift not found from #filePath. "
                    + "Tried: \(paths.joined(separator: ", "))"
            }
        }
    }
}
