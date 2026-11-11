// RecoveryListViewModelTests.swift
// iosAppTests
//
// XCTest coverage for RecoveryListViewModel — refresh, row mapping, and restore
// (FR-12, FR-13, FR-14; EC-02, EC-04, EC-05, EC-06, EC-16).
//
// Stubs are FILE-PRIVATE to this file (M3 CI convention — no duplicate symbols).
// Real NSUserDefaults/disk never touched.
//
// M3 CI conventions (MANDATORY):
//   @MainActor at class level — RecoveryListViewModel is @Observable.
//   File-private stubs — never hit real NSUserDefaults/disk.
//   No async/await, no Task, no wall-clock sleeps (OQ-11 note below).
//   import shared + @testable import iosApp.
//
// OQ-11 resolution: select(path:) calls recovery.read(path:) synchronously
// (the shared API is non-suspend, FR-24). No async/await harness needed.
// The stub's read returns synchronously, so assertions are immediate.
//
// Spec refs: §7.1 Layer B (RecoveryListViewModel section); §7.2 integration;
//   EC-02, EC-04, EC-05, EC-06, EC-16; FR-12, FR-13, FR-14.
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import shared
@testable import iosApp

// ---------------------------------------------------------------------------
// MARK: - File-private stubs
// ---------------------------------------------------------------------------

/// Controllable RecoveryRepository stub.
///
/// - `listResult`: the list returned by `list()`.
/// - `readResult`: the string returned by `read(path:)`; nil simulates a vanished file.
/// - `listCallCount`: how many times `list()` was invoked (proves re-fetch on each expand).
/// - `readCallCount`: how many times `read(path:)` was invoked.
private final class ControlledRecoveryRepository: RecoveryRepository {
    var listResult: [RecoveryNote] = []
    var readResult: String? = nil

    private(set) var listCallCount: Int = 0
    private(set) var readCallCount: Int = 0

    func list() -> [RecoveryNote] {
        listCallCount += 1
        return listResult
    }

    func read(path: String) -> String? {
        readCallCount += 1
        return readResult
    }

    // Remaining RecoveryRepository members — inert; RecoveryListViewModel never calls them.
    func save(text: String) -> String { "" }
    func delete(path: String) {}
    func deleteAll() {}
    func trim(keep: Int32) {}
}

/// Minimal settings stub (inert — RecoveryListViewModel doesn't use settings).
private final class StubSettingsRepository: SettingsRepository {
    private var stored = AppSettings(colorScheme: AppColorScheme.follow, fontSizeIndex: 8)
    func load() -> AppSettings { stored }
    func save(settings: AppSettings) { stored = settings }
}

/// Spy for NoteViewModel that records populate calls.
private final class PopulateSpy: NoteViewModel {
    private(set) var populateCallCount: Int = 0
    private(set) var lastPopulatedText: String? = nil

    // Initialise with an inert stub so no real UserDefaults are touched.
    init() {
        super.init(settings: StubSettingsRepository())
    }

    override func populate(_ text: String) {
        populateCallCount += 1
        lastPopulatedText = text
        super.populate(text)
    }
}

/// Helper: build a RecoveryNote from primitive values.
/// `RecoveryInstant` carries 7 Int fields (year/month/day/hour/minute/second/millis).
private func makeNote(path: String, preview: String, year: Int = 2025, month: Int = 6,
                      day: Int = 15, hour: Int = 10, minute: Int = 30) -> RecoveryNote {
    RecoveryNote(
        path: path,
        savedAt: RecoveryInstant(
            year: Int32(year), month: Int32(month), day: Int32(day),
            hour: Int32(hour), minute: Int32(minute), second: 0, millis: 0
        ),
        preview: preview
    )
}

// ---------------------------------------------------------------------------
// MARK: - RecoveryListViewModelTests
// ---------------------------------------------------------------------------

/// Tests for RecoveryListViewModel — refresh, row mapping, and restore-on-tap.
///
/// `@MainActor`: RecoveryListViewModel is `@Observable`.
@MainActor
final class RecoveryListViewModelTests: XCTestCase {

    // MARK: - Helpers

    private func makeVM(
        recovery: ControlledRecoveryRepository = ControlledRecoveryRepository(),
        spy: PopulateSpy = PopulateSpy()
    ) -> RecoveryListViewModel {
        RecoveryListViewModel(recovery: recovery, viewModel: spy)
    }

    // -----------------------------------------------------------------------
    // MARK: - refresh() — row count and ordering (FR-12)

    /// refresh() with 3 notes → rows.count == 3 in list() order.
    func test_refresh_threeNotes_rowsCountEqualsThree() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [
            makeNote(path: "/docs/r3.txt", preview: "Third"),
            makeNote(path: "/docs/r2.txt", preview: "Second"),
            makeNote(path: "/docs/r1.txt", preview: "First"),
        ]
        let vm = makeVM(recovery: recovery)

        vm.refresh()

        XCTAssertEqual(vm.rows.count, 3,
            "refresh() with 3 notes must produce rows.count == 3 (FR-12)")
        XCTAssertEqual(vm.rows[0].path, "/docs/r3.txt",
            "rows must preserve list() order — newest-first by filename (FR-12)")
        XCTAssertEqual(vm.rows[1].path, "/docs/r2.txt", "rows[1] order (FR-12)")
        XCTAssertEqual(vm.rows[2].path, "/docs/r1.txt", "rows[2] order (FR-12)")
    }

    /// refresh() with [] → rows.isEmpty (EC-02 / FR-12).
    func test_refresh_emptyList_rowsEmpty() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = []
        let vm = makeVM(recovery: recovery)

        vm.refresh()

        XCTAssertTrue(vm.rows.isEmpty,
            "refresh() with empty list must leave rows empty (EC-02 empty-recovery / FR-12)")
    }

    // -----------------------------------------------------------------------
    // MARK: - refresh() — re-fetch on every call (FR-12)

    /// refresh() called twice → list() called twice (not cached).
    func test_refresh_twice_listCalledTwice() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [makeNote(path: "/r.txt", preview: "Note")]
        let vm = makeVM(recovery: recovery)

        vm.refresh()
        vm.refresh()

        XCTAssertEqual(recovery.listCallCount, 2,
            "refresh() must call list() on every invocation, not cache (FR-12 re-fetch on expand)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Row subtitle (NFR-06)

    /// Each row's dateSubtitle is non-empty and matches a YYYY-MM-DD HH:MM pattern.
    ///
    /// The exact format string is an implementation detail (OQ-06); the contract is
    /// non-empty, non-epoch, derived only from the 7 Int fields (NFR-06).
    func test_refresh_rowSubtitle_nonEmptyAndMatchesDateShape() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [
            makeNote(path: "/r.txt", preview: "Note", year: 2025, month: 6, day: 15, hour: 10, minute: 30)
        ]
        let vm = makeVM(recovery: recovery)

        vm.refresh()

        XCTAssertFalse(vm.rows.isEmpty, "precondition: rows must not be empty")
        let subtitle = vm.rows[0].dateSubtitle

        XCTAssertFalse(subtitle.isEmpty,
            "dateSubtitle must be non-empty (NFR-06 / FR-12)")
        // The implementation uses "YYYY-MM-DD HH:MM" format.
        // Assert it contains the expected year and month fragments.
        XCTAssertTrue(subtitle.contains("2025"),
            "dateSubtitle must contain the year 2025 derived from RecoveryInstant (NFR-06)")
        XCTAssertFalse(subtitle.contains("1970"),
            "dateSubtitle must NOT contain 1970 — epoch-math would produce 1970 (NFR-06)")
        // Basic shape check: at least 16 chars "YYYY-MM-DD HH:MM"
        XCTAssertGreaterThanOrEqual(subtitle.count, 16,
            "dateSubtitle must have at least 16 characters (YYYY-MM-DD HH:MM shape; OQ-06)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Subtitle delegation to the shared formatter (FR-09 / TASK-15)

    /// The row subtitle IS the shared Kotlin `RecoveryInstant.formatSubtitle()` output.
    ///
    /// RED before TASK-15: `RecoveryListViewModel` carried a private Swift
    /// `subtitle(from:)` formatter. The expectation is byte-identical — this pins the
    /// delegation, not a format change.
    func test_rowSubtitle_comesFromTheSharedFormatter() {
        let recovery = ControlledRecoveryRepository()
        let note = makeNote(
            path: "/shared/format.txt", preview: "Shared",
            year: 2026, month: 5, day: 4, hour: 9, minute: 7
        )
        recovery.listResult = [note]
        let vm = makeVM(recovery: recovery)

        vm.refresh()

        XCTAssertEqual(vm.rows.count, 1, "precondition: one row after refresh")
        XCTAssertEqual(
            vm.rows[0].dateSubtitle,
            note.savedAt.formatSubtitle(),
            "dateSubtitle must be the shared formatSubtitle() output (FR-09 / TASK-15)"
        )
        XCTAssertEqual(
            vm.rows[0].dateSubtitle, "2026-05-04 09:07",
            "shared formatter keeps the zero-padded YYYY-MM-DD HH:MM contract (TASK-05 vectors)"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: - Share gating delegates to the shared predicate (FR-09 / TASK-15)

    /// `ChromeBarCoordinator` gates Share with the shared `canShare(text:)`.
    ///
    /// This pins the exported Swift symbol and its trim semantics; the behavioural
    /// proof that the toolbar button follows it is the existing
    /// `ChromeBarCoordinatorTests.testShareTracksWhitespaceAndEditedNote` (unchanged).
    func test_shareGating_matchesTheSharedPredicate() {
        XCTAssertTrue(ShareGateKt.canShare(text: "hello"),
            "non-empty text is shareable")
        XCTAssertTrue(ShareGateKt.canShare(text: "  x  "),
            "surrounding whitespace does not block sharing")
        XCTAssertFalse(ShareGateKt.canShare(text: ""),
            "the empty buffer is not shareable")
        XCTAssertFalse(ShareGateKt.canShare(text: " \n\t"),
            "whitespace- and newline-only buffers are not shareable (trim semantics)")
    }

    // -----------------------------------------------------------------------
    // MARK: - select(path:) — happy path (FR-13)

    /// select(path:) with stub read=="hello" → populate("hello") called once.
    func test_select_withValidRead_populatesOnce() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [makeNote(path: "/notes/foo.txt", preview: "Foo")]
        recovery.readResult = "hello"
        let spy = PopulateSpy()
        let vm = RecoveryListViewModel(recovery: recovery, viewModel: spy)

        vm.refresh()
        vm.select(path: "/notes/foo.txt")

        XCTAssertEqual(spy.populateCallCount, 1,
            "select() with a non-nil read must call populate exactly once (FR-13)")
        XCTAssertEqual(spy.lastPopulatedText, "hello",
            "populate must be called with the text returned by read() (FR-13)")
    }

    // -----------------------------------------------------------------------
    // MARK: - select(path:) — vanished-file nil tolerance (EC-04 / FR-13)

    /// select(path:) with stub read==nil → no populate call, no crash.
    func test_select_nilRead_noPopulate_noCrash() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [makeNote(path: "/notes/vanished.txt", preview: "Gone")]
        recovery.readResult = nil          // file vanished since list()
        let spy = PopulateSpy()
        let vm = RecoveryListViewModel(recovery: recovery, viewModel: spy)

        vm.refresh()
        XCTAssertFalse(vm.rows.isEmpty, "precondition: rows must not be empty")

        vm.select(path: "/notes/vanished.txt")

        XCTAssertEqual(spy.populateCallCount, 0,
            "select() with nil read must NOT call populate (EC-04 / FR-13 nil-tolerance)")
    }

    // -----------------------------------------------------------------------
    // MARK: - select(path:) — mutated file EC-05

    /// select(path:) with read returning different text than preview → restore uses on-disk text.
    func test_select_mutatedFile_restoresOnDiskText() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [makeNote(path: "/notes/mutated.txt", preview: "Old preview")]
        recovery.readResult = "Mutated content on disk"  // different from preview
        let spy = PopulateSpy()
        let vm = RecoveryListViewModel(recovery: recovery, viewModel: spy)

        vm.refresh()
        vm.select(path: "/notes/mutated.txt")

        XCTAssertEqual(spy.lastPopulatedText, "Mutated content on disk",
            "restore must use the text returned by read(), not the stale preview (EC-05 / FR-13)")
    }

    // -----------------------------------------------------------------------
    // MARK: - §7.2 Integration: refresh-on-expand contract (FR-12)

    /// Two refreshes with different list results → second call's result wins (re-fetch, not cache).
    func test_integration_refreshOnExpandContract_secondListWins() {
        let recovery = ControlledRecoveryRepository()
        let spy = PopulateSpy()
        let vm = RecoveryListViewModel(recovery: recovery, viewModel: spy)

        // First expand: 2 notes.
        recovery.listResult = [
            makeNote(path: "/a.txt", preview: "A"),
            makeNote(path: "/b.txt", preview: "B"),
        ]
        vm.refresh()
        XCTAssertEqual(vm.rows.count, 2, "first refresh: 2 rows")

        // Second expand: 1 note (simulates a note having been overwritten).
        recovery.listResult = [makeNote(path: "/a.txt", preview: "A updated")]
        vm.refresh()
        XCTAssertEqual(vm.rows.count, 1, "second refresh: 1 row (not cached from first)")
        XCTAssertEqual(vm.rows[0].previewTitle, "A updated",
            "second refresh must reflect the new list (FR-12 re-fetch)")
    }

    // -----------------------------------------------------------------------
    // MARK: - §7.2 Integration: absent directory EC-06

    /// Stub returns [] (simulating EC-06 absent directory); rows.isEmpty, no crash.
    func test_integration_absentDirectory_emptyRows_noCrash() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = []          // absent dir → repository returns []
        let vm = makeVM(recovery: recovery)

        vm.refresh()

        XCTAssertTrue(vm.rows.isEmpty,
            "absent recovery directory (list()=[]) must yield empty rows (EC-06)")
    }

    // -----------------------------------------------------------------------
    // MARK: - §7.2 Integration: vanished-file nil read after rows populated (EC-04)

    /// After refresh populates 2 entries, select with nil read → no populate, rows unchanged.
    func test_integration_vanishedFileAfterRefresh_noPopulate_rowsUnchanged() {
        let recovery = ControlledRecoveryRepository()
        recovery.listResult = [
            makeNote(path: "/a.txt", preview: "A"),
            makeNote(path: "/b.txt", preview: "B"),
        ]
        recovery.readResult = nil          // file vanished since list()
        let spy = PopulateSpy()
        let vm = RecoveryListViewModel(recovery: recovery, viewModel: spy)

        vm.refresh()
        XCTAssertEqual(vm.rows.count, 2, "precondition: 2 rows after refresh")

        vm.select(path: vm.rows[0].path)

        XCTAssertEqual(spy.populateCallCount, 0,
            "vanished-file nil read must NOT call populate (EC-04 / FR-13)")
        XCTAssertEqual(vm.rows.count, 2,
            "rows must be unchanged after a nil-read select (EC-04 — no side-effects)")
    }

    // -----------------------------------------------------------------------
    // MARK: - §7.2 Integration: EC-16 large-body synchronous read

    /// Stub returns a 2 MB string synchronously; select() completes without crash.
    func test_integration_EC16_largeBodySynchronousRead_noCrash() {
        let recovery = ControlledRecoveryRepository()
        // 2 MB of text (approx. 2_000_000 Unicode scalars, each 1-byte ASCII for simplicity).
        let largeText = String(repeating: "x", count: 2_000_000)
        recovery.listResult = [makeNote(path: "/large.txt", preview: "Large")]
        recovery.readResult = largeText
        let spy = PopulateSpy()
        let vm = RecoveryListViewModel(recovery: recovery, viewModel: spy)

        vm.refresh()
        vm.select(path: "/large.txt")      // synchronous read (FR-24 non-suspend)

        XCTAssertEqual(spy.populateCallCount, 1,
            "select with 2 MB read must call populate once (EC-16 synchronous large-body read)")
        XCTAssertEqual(spy.lastPopulatedText?.count, 2_000_000,
            "populate must be called with the full 2 MB text (EC-16)")
    }
}
