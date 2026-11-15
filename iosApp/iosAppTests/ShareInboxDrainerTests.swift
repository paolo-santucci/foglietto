// ShareInboxDrainerTests.swift
// iosAppTests
//
// XCTest coverage for ShareInboxDrainer — the ONE host-side ordering seam that turns
// a pending inbound-share payload into a seeded editor buffer (FR-05, FR-06, FR-07,
// FR-09, FR-10, FR-18, FR-19, FR-20, NFR-01, NFR-05).
//
// TDD: written FIRST — before `ShareInboxDrainer.swift` exists the type is unresolved,
// so this class cannot compile (RED). It is green only when `drain()` runs exactly
// read → normalize → collision save → populate → clear, with the entry cleared ONLY
// after a successful seed.
//
// Test seam (OQ-23 / OQ-27): the four effects are injected, so these cases read/clear
// a one-slot in-memory stub — ZERO App Group I/O, no temporary directory, no second
// process, no extension. One extra case exercises the production `init(inbox:...)`
// over a real `ShareInbox` pointed at a throwaway temporary container.
//
// Synchrony (NFR-01): every case asserts the recorded sequence IMMEDIATELY after
// `drain()` returns — no await, no RunLoop pump, no wall-clock sleep.
//
// Source-structure assertions read `iosApp/iosApp/Share/ShareInboxDrainer.swift`
// through this file's compile-time `#filePath`, the same technique
// ShareInboxTests / LifecycleSaveCoordinatorTests already use.
//
// Spec refs: FR-05, FR-06, FR-07, FR-09, FR-10, FR-18, FR-19, FR-20, NFR-01,
// NFR-05; §5.1.3, §7.1 (lines 828-833), §7.2 (line 870), OQ-27.
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import Foundation
import shared
@testable import iosApp

// ---------------------------------------------------------------------------
// MARK: - File-private recording doubles
// ---------------------------------------------------------------------------

/// Ordered record of every effect the drainer applies, in call order.
///
/// One array carries the sequence the ordering assertions compare; the typed
/// accessors exist so a case can assert counts without re-deriving them.
private final class DrainRecorder {

    /// One entry per effect: `normalize:<raw>`, `normalize(nil)`, `save`,
    /// `populate:<normalized>`, `clear`.
    private(set) var events: [String] = []

    /// The RAW values handed to `normalize`, in call order (nil = absent entry).
    private(set) var normalizeArguments: [String?] = []

    /// The NORMALIZED values handed to `populate`, in call order.
    private(set) var populatedTexts: [String] = []

    var saveCount: Int { events.filter { $0 == "save" }.count }
    var populateCount: Int { populatedTexts.count }
    var normalizeCount: Int { normalizeArguments.count }

    func recordNormalize(_ raw: String?) {
        normalizeArguments.append(raw)
        if let raw {
            events.append("normalize:\(raw)")
        } else {
            events.append("normalize(nil)")
        }
    }

    func recordSave() {
        events.append("save")
    }

    func recordPopulate(_ text: String) {
        populatedTexts.append(text)
        events.append("populate:\(text)")
    }

    func recordClear() {
        events.append("clear")
    }

    func firstIndex(of event: String) -> Int? {
        events.firstIndex(of: event)
    }

    func firstIndex(withPrefix prefix: String) -> Int? {
        events.firstIndex { $0.hasPrefix(prefix) }
    }
}

/// The one-slot inbox seam: `read()` yields the pending payload (or nil) and
/// `clear()` deletes it. No App Group, no directory, no file I/O.
///
/// `clear()` sets the slot to nil exactly as `ShareInbox.clear()` deletes
/// `pending.txt`, so a second `drain()` reads nil — the FR-19 replay guard.
private final class InboxStub {

    private(set) var payload: String?
    private(set) var clearCount = 0
    private(set) var writeCount = 0

    init(payload: String?) {
        self.payload = payload
    }

    func read() -> String? {
        payload
    }

    func write(_ text: String) {
        writeCount += 1
        payload = text
    }

    func clear() {
        clearCount += 1
        payload = nil
    }
}

// ---------------------------------------------------------------------------
// MARK: - ShareInboxDrainerTests
// ---------------------------------------------------------------------------

/// Tests for the drain ordering seam.
///
/// `@MainActor`: ShareInboxDrainer is @MainActor (it is driven from
/// `iosAppApp.init()` and `.onChange(of: scenePhase)`), so constructing or calling
/// it from a non-@MainActor context would need async machinery. Annotating the test
/// class is the established convention here (see LifecycleSaveCoordinatorTests).
@MainActor
final class ShareInboxDrainerTests: XCTestCase {

    // -----------------------------------------------------------------------
    // MARK: - Harness
    // -----------------------------------------------------------------------

    /// Builds a drainer over the in-memory inbox with every effect recorded.
    ///
    /// - Parameters:
    ///   - inbox: the one-slot read/clear seam.
    ///   - recorder: receives every effect in call order.
    ///   - normalize: the injected rule; the default is a CRLF→LF stand-in so the
    ///     ordering cases never depend on the shared module.
    ///   - save: the collision save (must run before the seed).
    ///   - populate: the seed (must run after the save).
    private func makeDrainer(
        inbox: InboxStub,
        recorder: DrainRecorder,
        normalize: @escaping (String?) -> String? = { $0?.replacingOccurrences(of: "\r\n", with: "\n") },
        save: @escaping () -> Bool = { true },
        populate: @escaping (String) -> Void = { _ in }
    ) -> ShareInboxDrainer {
        ShareInboxDrainer(
            read: { inbox.read() },
            clear: {
                inbox.clear()
                recorder.recordClear()
            },
            normalize: { raw in
                recorder.recordNormalize(raw)
                return normalize(raw)
            },
            saveCurrentBuffer: {
                recorder.recordSave()
                return save()
            },
            populate: { text in
                recorder.recordPopulate(text)
                populate(text)
            }
        )
    }

    // -----------------------------------------------------------------------
    // MARK: - Happy path (FR-16, FR-18, FR-19)
    // -----------------------------------------------------------------------

    /// Raw `"a\r\nb\r\n"` → exactly normalize(raw) → save → populate(normalized) → clear.
    func testDrainAppliesNormalizeSavePopulateClearInOrder() {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: "a\r\nb\r\n")
        let drainer = makeDrainer(inbox: inbox, recorder: recorder)

        drainer.drain()

        XCTAssertEqual(
            recorder.events,
            ["normalize:a\r\nb\r\n", "save", "populate:a\nb\n", "clear"],
            "The drain must apply normalize → save → populate → clear, once each (FR-19)"
        )
        XCTAssertEqual(recorder.normalizeCount, 1, "The payload is normalized exactly once (FR-18)")
        XCTAssertEqual(
            recorder.normalizeArguments,
            ["a\r\nb\r\n"],
            "normalize receives the RAW payload — the adapter must not pre-normalize (FR-18, NFR-05)"
        )
        XCTAssertEqual(
            recorder.populatedTexts,
            ["a\nb\n"],
            "populate receives the NORMALIZED value, not the raw CRLF form (FR-18)"
        )
        XCTAssertEqual(inbox.clearCount, 1, "A successful seed clears the entry exactly once (FR-19)")
        XCTAssertNil(inbox.read(), "The drained payload must not be replayable (FR-19)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Blank payload (FR-09, EC-02)
    // -----------------------------------------------------------------------

    /// A rejected payload is consumed: the entry is cleared, nothing is saved or seeded.
    func testBlankPayloadClearsWithoutSavingOrPopulating() {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: "   \r\n\t ")
        let drainer = makeDrainer(inbox: inbox, recorder: recorder, normalize: { _ in nil })

        drainer.drain()

        XCTAssertEqual(recorder.events.count, 2, "A blank payload has exactly two effects")
        XCTAssertTrue(
            recorder.events.first?.hasPrefix("normalize") == true,
            "normalize runs first even when it rejects the payload: \(recorder.events)"
        )
        XCTAssertEqual(recorder.events.last, "clear", "A blank payload is still consumed")
        XCTAssertEqual(recorder.saveCount, 0, "No collision save for a blank payload (FR-09)")
        XCTAssertEqual(recorder.populateCount, 0, "No seed and no toast for a blank payload (FR-09)")
        XCTAssertEqual(inbox.clearCount, 1)
        XCTAssertNil(inbox.read(), "The blank share is consumed, latest-wins (FR-10)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Absent inbox (EC-01, FR-20)
    // -----------------------------------------------------------------------

    /// Nothing pending: normalize(nil) → clear, with no buffer effect at all.
    func testAbsentInboxClearsWithoutAnyBufferEffect() {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: nil)
        let drainer = makeDrainer(inbox: inbox, recorder: recorder)

        drainer.drain()

        XCTAssertEqual(
            recorder.events,
            ["normalize(nil)", "clear"],
            "An absent entry is normalize(nil) then clear — nothing else (EC-01)"
        )
        XCTAssertEqual(
            recorder.normalizeArguments,
            [nil],
            "normalize is still called once with nil, never skipped (EC-01)"
        )
        XCTAssertEqual(recorder.saveCount, 0, "No save when nothing is pending")
        XCTAssertEqual(recorder.populateCount, 0, "No seed when nothing is pending")
        XCTAssertEqual(inbox.clearCount, 1, "clear is idempotent and always runs (FR-20)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Collision ordering (FR-06, FR-19)
    // -----------------------------------------------------------------------

    /// With a non-blank buffer the collision save lands strictly before the seed,
    /// and the delete lands strictly after it.
    func testCollisionSavePrecedesSeedAndClear() throws {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: "new")
        let drainer = makeDrainer(inbox: inbox, recorder: recorder)

        drainer.drain()

        let saveIndex = try XCTUnwrap(recorder.firstIndex(of: "save"), "the collision save must run")
        let populateIndex = try XCTUnwrap(
            recorder.firstIndex(withPrefix: "populate:"),
            "the seed must run"
        )
        let clearIndex = try XCTUnwrap(recorder.firstIndex(of: "clear"), "the delete must run")

        XCTAssertLessThan(saveIndex, populateIndex, "The old buffer is saved BEFORE the seed (FR-06)")
        XCTAssertLessThan(populateIndex, clearIndex, "The entry is deleted AFTER a successful seed (FR-19)")
        XCTAssertEqual(recorder.events.last, "clear", "clear is the final effect, never before the seed")
        XCTAssertEqual(recorder.saveCount, 1, "Exactly one collision save per drain")
    }

    /// A refused collision save must NOT replace the buffer and must NOT consume the
    /// payload: the draft stays on screen and the share replays on the next activation
    /// (FR-07). The drain also reports "no seed", so the caller cannot arm the
    /// inbound-seed guard for a seed that never happened.
    func testRefusedCollisionSaveLeavesTheBufferAndThePayload() {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: "new")
        let drainer = makeDrainer(inbox: inbox, recorder: recorder, save: { false })

        let seeded = drainer.drain()

        XCTAssertFalse(seeded, "a refused save is not a seed")
        XCTAssertEqual(recorder.saveCount, 1, "the collision save is attempted exactly once")
        XCTAssertTrue(recorder.populatedTexts.isEmpty,
            "no seed may run when the old buffer could not be saved (FR-07)")
        XCTAssertEqual(inbox.clearCount, 0,
            "the payload must stay pending so the next activation replays it (FR-19)")
        XCTAssertEqual(inbox.payload, "new", "the entry survives a refused save")
    }

    // -----------------------------------------------------------------------
    // MARK: - Idempotence (FR-10, FR-19)
    // -----------------------------------------------------------------------

    /// A drain consumes the entry: the second drain is a blank no-op.
    func testSecondDrainIsABlankNoOp() {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: "hello")
        let drainer = makeDrainer(inbox: inbox, recorder: recorder)

        drainer.drain()
        drainer.drain()

        XCTAssertEqual(recorder.populatedTexts, ["hello"], "Exactly one seed across two drains (FR-10)")
        XCTAssertEqual(recorder.populateCount, 1, "The payload is never replayed (FR-19)")
        XCTAssertEqual(recorder.saveCount, 1, "The second drain makes no collision save")
        XCTAssertEqual(
            recorder.normalizeArguments,
            ["hello", nil],
            "The second read sees an empty inbox, not a replay"
        )
        XCTAssertEqual(inbox.clearCount, 2, "Both drains clear; the second is a no-op")
    }

    // -----------------------------------------------------------------------
    // MARK: - Write inside the drain window (OQ-27, documented behaviour)
    // -----------------------------------------------------------------------

    /// A write landing between `read()` and `clear()` is deleted and never applied.
    ///
    /// This records the CURRENT specified behaviour — the narrow loss accepted at
    /// plan time (OQ-27, plan §6) — and deliberately does NOT fix it. The collision
    /// save stands in for a second extension invocation: it runs after the read and
    /// before the clear, which is exactly the window in question.
    func testWriteArrivingInsideTheDrainWindowIsDeletedAndNeverApplied() {
        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: "old")
        let drainer = makeDrainer(inbox: inbox, recorder: recorder, save: {
            inbox.write("new")
        })

        drainer.drain()

        XCTAssertEqual(inbox.writeCount, 1, "Precondition: the newer payload landed inside the window")
        XCTAssertEqual(
            recorder.populatedTexts,
            ["old"],
            "The payload read at step 1 is applied; the newer one never is (OQ-27)"
        )
        XCTAssertNil(
            inbox.read(),
            "Current specified behaviour: clear() deletes the newer payload too — the recorded loss (OQ-27)"
        )
        XCTAssertEqual(inbox.clearCount, 1)
    }

    // -----------------------------------------------------------------------
    // MARK: - Adapter parity with the shared rule (§7.2 line 870, NFR-05)
    // -----------------------------------------------------------------------

    /// A drainer whose `normalize` IS `IncomingTextKt.acceptIncomingText` seeds
    /// exactly what `IncomingTextTest` asserts — the adapter adds no normalization.
    func testSharedNormalizerParityOverTheNfr05Corpus() {
        // (raw payload, the value IncomingTextTest pins for it; nil = rejected).
        let corpus: [(raw: String, expected: String?)] = [
            ("a\r\nb\r\n", "a\nb\n"),
            ("a\r\r\nb", "a\r\nb"),
            ("a\rb", "a\rb"),
            ("a\r\n\r\nb", "a\n\nb"),
            ("a\r\n\nb", "a\n\nb"),
            (" x \n", " x \n"),
            ("\u{1F600}e\u{301}\u{00A0}", "\u{1F600}e\u{301}\u{00A0}"),
            ("", nil),
            ("   ", nil),
            ("\t", nil),
            ("\r\n", nil),
            ("\r\n\t ", nil),
            ("\n\n", nil),
            ("\u{00A0}", nil)
        ]

        for entry in corpus {
            let recorder = DrainRecorder()
            let inbox = InboxStub(payload: entry.raw)
            let drainer = ShareInboxDrainer(
                read: { inbox.read() },
                clear: {
                    inbox.clear()
                    recorder.recordClear()
                },
                normalize: IncomingTextKt.acceptIncomingText,
            saveCurrentBuffer: {
                recorder.recordSave()
                return true
            },
                populate: { recorder.recordPopulate($0) }
            )

            drainer.drain()

            let expectedSeeds = entry.expected.map { [$0] } ?? []
            XCTAssertEqual(
                recorder.populatedTexts,
                expectedSeeds,
                "the adapter must seed exactly what the shared rule returns for: \(escapedForDiagnostics(entry.raw))"
            )
            XCTAssertEqual(
                inbox.clearCount,
                1,
                "the entry is consumed in every case, accepted or rejected: \(escapedForDiagnostics(entry.raw))"
            )
            XCTAssertEqual(
                recorder.saveCount,
                entry.expected == nil ? 0 : 1,
                "a rejected payload never saves; an accepted one saves once: \(escapedForDiagnostics(entry.raw))"
            )
        }
    }

    /// The 1 MiB corpus member: accepted whole (no cap), only CRLF collapsed.
    ///
    /// One generated string, one drain — no timing assertion, so the case is a
    /// functional pin rather than a benchmark (NFR-05, FR-04).
    func testOneMebibytePayloadIsSeededWhole() throws {
        let filler = String(repeating: "x", count: 1_048_576 - 30_000 - 50)
        let raw = filler + String(repeating: "a\r\n", count: 10_000) + String(repeating: "\n", count: 50)
        // UTF-16 units, not Characters: "\r\n" is ONE grapheme cluster but THREE
        // UTF-16 code units, which is the length IncomingTextTest pins.
        XCTAssertEqual(raw.utf16.count, 1_048_576, "fixture must be exactly one mebibyte")

        let recorder = DrainRecorder()
        let inbox = InboxStub(payload: raw)
        let drainer = ShareInboxDrainer(
            read: { inbox.read() },
            clear: {
                inbox.clear()
                recorder.recordClear()
            },
            normalize: IncomingTextKt.acceptIncomingText,
            saveCurrentBuffer: {
                recorder.recordSave()
                return true
            },
            populate: { recorder.recordPopulate($0) }
        )

        drainer.drain()

        let seeded = try XCTUnwrap(recorder.populatedTexts.first, "a one-mebibyte payload must be accepted whole")
        XCTAssertEqual(recorder.populatedTexts.count, 1)
        XCTAssertEqual(seeded.utf16.count, raw.utf16.count - 10_000, "only the 10_000 CRLF pairs are collapsed")
        XCTAssertTrue(
            seeded.hasSuffix(String(repeating: "\n", count: 50)),
            "the trailing newline run survives — no trimming at the adapter"
        )
        XCTAssertFalse(seeded.contains("\r"), "no CR may remain after normalization")
        XCTAssertEqual(inbox.clearCount, 1)
    }

    // -----------------------------------------------------------------------
    // MARK: - Production initialiser over an injected container (OQ-23)
    // -----------------------------------------------------------------------

    /// The `init(inbox:...)` seam TASK-10/TASK-11 use: a real `ShareInbox` over a
    /// throwaway container (never the App Group) drains and deletes the entry.
    func testProductionInitializerDrainsARealInboxOverAnInjectedContainer() {
        let container = FileManager.default.temporaryDirectory
            .appending(path: "ShareInboxDrainerTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: container) }

        let inbox = ShareInbox(containerURL: container)
        XCTAssertTrue(inbox.write("a\r\nb\r\n"), "Precondition: the extension wrote the raw payload")

        let recorder = DrainRecorder()
        let drainer = ShareInboxDrainer(
            inbox: inbox,
            normalize: IncomingTextKt.acceptIncomingText,
            saveCurrentBuffer: {
                recorder.recordSave()
                return true
            },
            populate: { recorder.recordPopulate($0) }
        )

        drainer.drain()

        XCTAssertEqual(recorder.populatedTexts, ["a\nb\n"], "The production seam seeds the normalized text")
        XCTAssertNil(inbox.read(), "The production seam deletes the entry after a successful seed (FR-19)")
        XCTAssertEqual(recorder.saveCount, 1)
    }

    // -----------------------------------------------------------------------
    // MARK: - Source structure (NFR-01, FR-07)
    // -----------------------------------------------------------------------

    /// The drain path stays synchronous and silent: no Task/DispatchQueue/async/
    /// await, no do/catch, no logging, exactly one collision-save call.
    func testSourceHasNoConcurrencyTokensLoggingOrCatch() throws {
        let code = try drainerCodeLines().joined(separator: "\n")

        for token in ["Task", "DispatchQueue", "async", "await"] {
            XCTAssertEqual(
                wholeWordOccurrences(of: token, in: code),
                0,
                "ShareInboxDrainer must contain no `\(token)` — the drain is synchronous (NFR-01)"
            )
        }
        XCTAssertFalse(code.contains("catch"), "No `catch`: a Kotlin failure must terminate before the seed (FR-07)")
        XCTAssertFalse(code.contains("do {"), "No `do` block: there is nothing to catch (FR-07)")

        for token in ["print(", "NSLog", "os_log", "Logger"] {
            XCTAssertFalse(code.contains(token), "The drain must not log payload text (NFR-03): `\(token)`")
        }

        let saveCalls = code.components(separatedBy: .newlines).filter { $0.contains("saveCurrentBuffer()") }
        XCTAssertEqual(saveCalls.count, 1, "Exactly one collision-save call site: \(saveCalls)")

        let clearCalls = code.components(separatedBy: .newlines).filter { $0.contains("clearEntry()") }
        XCTAssertEqual(clearCalls.count, 2, "clear runs on the reject path and after the seed: \(clearCalls)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Helpers
    // -----------------------------------------------------------------------

    private func escapedForDiagnostics(_ raw: String) -> String {
        raw.isEmpty ? "<empty>" : raw.unicodeScalars.map { String(format: "\\u{%02X}", $0.value) }.joined()
    }

    /// Counts `token` as a whole word only, so `UIBackgroundTask`/`taskIdentifier`
    /// style identifiers cannot produce a false positive.
    private func wholeWordOccurrences(of token: String, in text: String) -> Int {
        text.split { !($0.isLetter || $0.isNumber || $0 == "_") }.filter { String($0) == token }.count
    }

    /// Non-comment lines of `iosApp/iosApp/Share/ShareInboxDrainer.swift`, resolved
    /// from this test file's own compile-time `#filePath` — no bundle resource.
    private func drainerCodeLines() throws -> [String] {
        let testFileDirectory = URL(filePath: #filePath).deletingLastPathComponent()
        let iosAppDirectory = testFileDirectory.deletingLastPathComponent()
        let repositoryRoot = iosAppDirectory.deletingLastPathComponent()
        let relativeSource = "iosApp/iosApp/Share/ShareInboxDrainer.swift"

        let candidates = [
            iosAppDirectory.appending(path: "iosApp/Share/ShareInboxDrainer.swift"),
            repositoryRoot.appending(path: relativeSource),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: relativeSource)
        ]

        guard let sourceURL = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) else {
            throw SourceInspectionError.drainerSourceNotFound(paths: candidates.map(\.path))
        }

        let data = try Data(contentsOf: sourceURL)
        let source = String(decoding: data, as: UTF8.self)
        return source
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
    }

    /// Raised when the drainer source cannot be located from `#filePath`; failing
    /// the test (not skipping) keeps the NFR-01 assertions honest.
    private enum SourceInspectionError: Error, CustomStringConvertible {
        case drainerSourceNotFound(paths: [String])

        var description: String {
            switch self {
            case let .drainerSourceNotFound(paths):
                return "ShareInboxDrainer.swift not found from #filePath. Tried: \(paths.joined(separator: ", "))"
            }
        }
    }
}
