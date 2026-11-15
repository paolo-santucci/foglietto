// LocalizationInventoryTests.swift
// iosAppTests
//
// P9 of the inbound-share contract: the iOS String Catalog is an EXACT inventory.
// Every user-visible key is a deliberate, reviewed addition, so any new key —
// including share/inbound copy — must fail this pin rather than ship unlocalized
// (FR-11, NFR-02, EC-26).
//
// The baseline below was captured from the tree BEFORE the share-sheet work
// touched anything: 20 keys. It is a literal on purpose — computing it from the
// catalog at runtime would make the test agree with whatever the file says.
// `Localizable.xcstrings` itself is never written by this suite.
//
// Everything is read from the repository through the compile-time `#filePath`
// (via `Repository` in ShareExtensionContractTests.swift) — no bundle resource,
// no device. XCTest cannot run on Linux, so the suite is CI-only: `test_ios` is
// the only real proof.
//
// Spec refs: FR-11, FR-12; §7.1 (lines 855-860); OQ-24.

import XCTest
import Foundation

final class LocalizationInventoryTests: XCTestCase {

    private static let catalogPath = "iosApp/iosApp/Localizable.xcstrings"

    /// The 20 `strings` keys of `iosApp/iosApp/Localizable.xcstrings`, captured
    /// from the pre-change tree (2026-11-15, TASK-13). Sorted for a stable diff.
    private static let baselineKeys: Set<String> = [
        "About",
        "Clear editor",
        "Copy",
        "Copy text",
        "Hide keyboard",
        "Indent",
        "Menu",
        "No recent notes.",
        "Open menu",
        "Outdent",
        "Paste",
        "Paste text",
        "Recent notes",
        "Report an issue",
        "Restore note: %@, saved %@",
        "Restore this note",
        "Share",
        "Share text",
        "Undo",
        "Website"
    ]

    /// The catalog's key set equals the pinned baseline, so the diff of a new or
    /// dropped key names itself in the failure message (FR-11, NFR-02).
    func testP9CatalogKeysMatchThePinnedBaseline() throws {
        let repository = try Repository.locate()
        let data = try Data(contentsOf: try repository.url(Self.catalogPath))
        let root = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any],
            "\(Self.catalogPath) must be a JSON object — an Xcode String Catalog"
        )
        let strings = try XCTUnwrap(
            root["strings"] as? [String: Any],
            "\(Self.catalogPath) must carry a 'strings' object"
        )

        let keys = Set(strings.keys)
        let added = keys.subtracting(Self.baselineKeys).sorted()
        let removed = Self.baselineKeys.subtracting(keys).sorted()

        XCTAssertEqual(
            added,
            [],
            "\(Self.catalogPath) gained \(added.count) key(s) \(added) — the inventory is 20 keys: no localizable copy without the spec (FR-11, NFR-02, EC-26)"
        )
        XCTAssertEqual(
            removed,
            [],
            "\(Self.catalogPath) lost \(removed.count) baseline key(s) \(removed) — the inventory is exact"
        )
    }
}
