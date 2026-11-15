// ShareInboxTests.swift
// iosAppTests
//
// XCTest coverage for ShareInbox — the App Group inbox that carries one inbound
// share payload from the Share Extension to the host app (FR-13, FR-15, FR-20).
//
// Test seam (OQ-23): every case constructs `ShareInbox(containerURL:)` over a
// throwaway temporary directory. A simulator unit-test runner has no App Group
// container, so `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)`
// returns nil there; injecting the container keeps the file contract executable
// instead of degrading every case to "returns nil".
//
// Synchronous only: no Task, no async/await, no wall-clock sleeps, no RunLoop pump.
//
// Source-structure assertions (EC-16) read `iosApp/iosApp/Share/ShareInbox.swift`
// through this file's compile-time `#filePath`, the same technique the other
// contract tests use — no bundle resource, no device, no App Group.
//
// Spec refs: FR-10, FR-13, FR-15, FR-20, NFR-03, NFR-08; §5.1.2, §7.1 (819-824), OQ-23.
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import Foundation
@testable import iosApp

// ---------------------------------------------------------------------------
// MARK: - Temporary container
// ---------------------------------------------------------------------------

/// A throwaway stand-in for the App Group container directory.
///
/// Mirrors the inbox path grammar independently of the implementation: the test
/// must fail if `ShareInbox` ever moves `pending.txt` somewhere else.
private struct TemporaryContainer {

    let url: URL

    init() throws {
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "ShareInboxTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        self.url = directory
    }

    /// `Library/Application Support/FogliettoInbox` — one component at a time so a
    /// space in `Application Support` can never be read as part of a longer name.
    var inboxDirectory: URL {
        url
            .appending(path: "Library", directoryHint: .isDirectory)
            .appending(path: "Application Support", directoryHint: .isDirectory)
            .appending(path: "FogliettoInbox", directoryHint: .isDirectory)
    }

    var entryFile: URL {
        inboxDirectory.appending(path: "pending.txt", directoryHint: .notDirectory)
    }

    /// A stray sibling the atomic write may leave behind: never read, never cleared.
    var strayTemporaryFile: URL {
        inboxDirectory.appending(path: "pending.txt.tmp", directoryHint: .notDirectory)
    }

    func createInboxDirectory() throws {
        try FileManager.default.createDirectory(at: inboxDirectory, withIntermediateDirectories: true)
    }

    func discard() {
        try? FileManager.default.removeItem(at: url)
    }
}

// ---------------------------------------------------------------------------
// MARK: - ShareInboxTests
// ---------------------------------------------------------------------------

final class ShareInboxTests: XCTestCase {

    // -----------------------------------------------------------------------
    // MARK: - Round trip
    // -----------------------------------------------------------------------

    func testReadOnAnEmptyInboxReturnsNil() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)

        XCTAssertNil(inbox.read(), "An inbox with no directory yet must read as empty (FR-13)")
    }

    func testRoundTripPreservesCRLFAndTrailingNewline() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)
        let payload = "a\r\nb\r\n"

        XCTAssertNil(inbox.read(), "Precondition: the inbox starts empty")

        XCTAssertTrue(inbox.write(payload), "A write into a fresh container must succeed (FR-13)")

        XCTAssertEqual(inbox.read(), payload, "CRLF and the trailing newline must survive byte-exact")

        // No BOM, no framing, no length prefix: the file is exactly the UTF-8 payload.
        let storedBytes = try Data(contentsOf: container.entryFile)
        XCTAssertEqual(storedBytes, Data(payload.utf8), "The entry must hold the raw payload bytes only")
    }

    func testWriteCreatesTheInboxDirectoryOnFirstUse() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: container.inboxDirectory.path),
            "Precondition: the inbox directory is created on first write, not earlier"
        )

        XCTAssertTrue(inbox.write("payload"))

        var isDirectory: ObjCBool = false
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: container.inboxDirectory.path, isDirectory: &isDirectory),
            "write must create Library/Application Support/FogliettoInbox"
        )
        XCTAssertTrue(isDirectory.boolValue)
    }

    // -----------------------------------------------------------------------
    // MARK: - Latest wins / temp isolation (EC-03, EC-16)
    // -----------------------------------------------------------------------

    func testLatestWriteWins() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)

        XCTAssertTrue(inbox.write("A"))
        XCTAssertTrue(inbox.write("B"))

        XCTAssertEqual(inbox.read(), "B", "One pending payload: the newest write replaces the previous one")
    }

    func testStrayTemporaryFileIsIgnoredByReadAndSurvivesClear() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)
        XCTAssertTrue(inbox.write("A"))

        try Data("stray".utf8).write(to: container.strayTemporaryFile)

        XCTAssertEqual(inbox.read(), "A", "A sibling temporary file is never the entry")

        inbox.clear()

        XCTAssertFalse(
            FileManager.default.fileExists(atPath: container.entryFile.path),
            "clear removes pending.txt"
        )
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: container.strayTemporaryFile.path),
            "clear must remove ONLY pending.txt — a stray pending.txt.tmp survives"
        )
        XCTAssertNil(inbox.read())
    }

    // -----------------------------------------------------------------------
    // MARK: - Failure paths (FR-20, EC-13)
    // -----------------------------------------------------------------------

    func testReadReturnsNilWhenEntryIsAbsentButDirectoryExists() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }
        try container.createInboxDirectory()

        let inbox = ShareInbox(containerURL: container.url)

        XCTAssertNil(inbox.read(), "Missing entry reads as empty, never throws")
    }

    func testReadReturnsNilForAnUnreadablePath() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }
        try container.createInboxDirectory()

        // A directory sitting where the entry belongs cannot be read as payload data.
        try FileManager.default.createDirectory(at: container.entryFile, withIntermediateDirectories: true)

        let inbox = ShareInbox(containerURL: container.url)

        XCTAssertNil(inbox.read(), "An unreadable entry path reads as empty, never throws")
    }

    func testClearOnAnAbsentEntryIsIdempotent() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }
        try container.createInboxDirectory()

        let inbox = ShareInbox(containerURL: container.url)

        inbox.clear()
        inbox.clear()

        XCTAssertTrue(
            FileManager.default.fileExists(atPath: container.inboxDirectory.path),
            "clear must not remove the inbox directory"
        )
        XCTAssertNil(inbox.read())
    }

    func testClearOnAnAbsentDirectoryIsIdempotent() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)

        inbox.clear()
        inbox.clear()

        XCTAssertFalse(FileManager.default.fileExists(atPath: container.inboxDirectory.path))
        XCTAssertNil(inbox.read())
    }

    func testClearAfterAWriteRemovesTheEntryAndIsIdempotent() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }

        let inbox = ShareInbox(containerURL: container.url)
        XCTAssertTrue(inbox.write("payload"))

        inbox.clear()
        inbox.clear()

        XCTAssertFalse(FileManager.default.fileExists(atPath: container.entryFile.path))
        XCTAssertNil(inbox.read())
    }

    // -----------------------------------------------------------------------
    // MARK: - Poison bytes (EC-15)
    // -----------------------------------------------------------------------

    func testPoisonBytesAreRejectedAndDeleted() throws {
        let container = try TemporaryContainer()
        defer { container.discard() }
        try container.createInboxDirectory()

        // Invalid UTF-8: a lone 0xFF, a lone 0xFE and a NUL — none of them decodes.
        try Data([0xFF, 0xFE, 0x00]).write(to: container.entryFile)
        XCTAssertTrue(FileManager.default.fileExists(atPath: container.entryFile.path))

        let inbox = ShareInbox(containerURL: container.url)

        XCTAssertNil(inbox.read(), "Invalid UTF-8 must read as empty, never throw")
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: container.entryFile.path),
            "Poison removal deletes the undecodable entry"
        )
        XCTAssertNil(inbox.read(), "A second read stays nil")
    }

    // -----------------------------------------------------------------------
    // MARK: - Unavailable container (FR-15, EC-14)
    // -----------------------------------------------------------------------

    func testUnavailableContainerFailsClosed() {
        let inbox = ShareInbox(containerURL: nil)

        XCTAssertNil(inbox.read(), "No container: nothing to read")
        XCTAssertFalse(inbox.write("x"), "No container: the write reports failure instead of throwing")
        inbox.clear()
        inbox.clear()
    }

    // -----------------------------------------------------------------------
    // MARK: - Source structure (EC-16)
    // -----------------------------------------------------------------------

    func testSourceHasExactlyOneAtomicWrite() throws {
        let codeLines = try shareInboxCodeLines()
        let atomicLines = codeLines.filter { $0.contains(".atomic") }

        XCTAssertEqual(atomicLines.count, 1, "The file must contain exactly one atomic-write option")

        let atomicLine = try XCTUnwrap(atomicLines.first)
        XCTAssertTrue(atomicLine.contains("write(to:"), "The single atomic write is the entry write: \(atomicLine)")
        XCTAssertTrue(atomicLine.contains("options: .atomic"), "The write options are exactly .atomic: \(atomicLine)")
    }

    func testSourceHasNoDirectoryEnumerationOrLogging() throws {
        let code = try shareInboxCodeLines().joined(separator: "\n")

        for token in ["contentsOfDirectory", "enumerator(", "print(", "NSLog", "os_log"] {
            XCTAssertFalse(code.contains(token), "ShareInbox must not contain `\(token)` (EC-16, FR-27)")
        }
        XCTAssertFalse(code.contains("import shared"), "ShareInbox is Foundation-only: no KMP dependency")
    }

    // -----------------------------------------------------------------------
    // MARK: - Source inspection helpers
    // -----------------------------------------------------------------------

    /// Non-comment lines of `iosApp/iosApp/Share/ShareInbox.swift`, resolved from
    /// this test file's own compile-time `#filePath` — no bundle resource needed.
    private func shareInboxCodeLines() throws -> [String] {
        let testFileDirectory = URL(filePath: #filePath).deletingLastPathComponent()
        let iosAppDirectory = testFileDirectory.deletingLastPathComponent()
        let repositoryRoot = iosAppDirectory.deletingLastPathComponent()
        let relativeSource = "iosApp/iosApp/Share/ShareInbox.swift"

        let candidates = [
            iosAppDirectory.appending(path: "iosApp/Share/ShareInbox.swift"),
            repositoryRoot.appending(path: relativeSource),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: relativeSource)
        ]

        guard let sourceURL = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) else {
            throw SourceInspectionError.shareInboxSourceNotFound(paths: candidates.map(\.path))
        }

        let data = try Data(contentsOf: sourceURL)
        let source = String(decoding: data, as: UTF8.self)
        return source
            .components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
    }

    /// Raised when the ShareInbox source cannot be located from `#filePath`;
    /// failing the test (not skipping) keeps the EC-16 assertions honest.
    private enum SourceInspectionError: Error, CustomStringConvertible {
        case shareInboxSourceNotFound(paths: [String])

        var description: String {
            switch self {
            case let .shareInboxSourceNotFound(paths):
                return "ShareInbox.swift not found from #filePath. Tried: \(paths.joined(separator: ", "))"
            }
        }
    }
}
