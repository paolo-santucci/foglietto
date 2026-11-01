// TextOffsetBridgeTests.swift
// iosAppTests
//
// Authored by TASK-03; target/scheme/pbxproj registration is TASK-08's responsibility.
// CI-verified on macos-26 / Xcode 26 simulator — not locally compilable (no macOS host).
//
// Spec refs: FR-06, FR-07, FR-08, NFR-04; §6.1 EC-02/EC-06/EC-08; §5.1.e

import XCTest
@testable import iosApp

final class TextOffsetBridgeTests: XCTestCase {

    // MARK: - caretOffset(from:in:)

    func testCaretOffset_withinRange() {
        // caretOffset(NSRange{5,0}, ascii len 10) == 5  (clamped within range)
        let nsText = "0123456789" as NSString
        XCTAssertEqual(TextOffsetBridge.caretOffset(from: NSRange(location: 5, length: 0), in: nsText), 5)
    }

    func testCaretOffset_upperClamp() {
        // caretOffset(NSRange{20,0}, len 10) == 10  (upper clamp, EC-08)
        let nsText = "0123456789" as NSString
        XCTAssertEqual(TextOffsetBridge.caretOffset(from: NSRange(location: 20, length: 0), in: nsText), 10)
    }

    func testCaretOffset_emptyString() {
        // caretOffset(NSRange{0,0}, empty NSString) == 0  (EC-08)
        let nsText = "" as NSString
        XCTAssertEqual(TextOffsetBridge.caretOffset(from: NSRange(location: 0, length: 0), in: nsText), 0)
    }

    // MARK: - collapsedRange(at:in:)

    func testCollapsedRange_valid() {
        // collapsedRange(at: 2, in: nsText) == NSRange{2,0}
        let nsText = "hello" as NSString
        let result = TextOffsetBridge.collapsedRange(at: 2, in: nsText)
        XCTAssertEqual(result.location, 2)
        XCTAssertEqual(result.length, 0)
    }

    func testCollapsedRange_negativeClamp() {
        // negative offset clamps to 0
        let nsText = "hello" as NSString
        let result = TextOffsetBridge.collapsedRange(at: -3, in: nsText)
        XCTAssertEqual(result.location, 0)
        XCTAssertEqual(result.length, 0)
    }

    // MARK: - selection(from:) — inbound: selExtent is ABSOLUTE, NOT a length (I-1 critical)

    func testSelection_absoluteExtent() {
        // selection(NSRange{3,4}) == (selStart:3, selExtent:7)  (absolute, NOT length; I-1 critical)
        let result = TextOffsetBridge.selection(from: NSRange(location: 3, length: 4))
        XCTAssertEqual(result.selStart, 3)
        XCTAssertEqual(result.selExtent, 7)  // 3 + 4 = 7, ABSOLUTE offset
    }

    func testSelection_collapsed() {
        // selection(NSRange{5,0}) == (5,5)  (collapsed)
        let result = TextOffsetBridge.selection(from: NSRange(location: 5, length: 0))
        XCTAssertEqual(result.selStart, 5)
        XCTAssertEqual(result.selExtent, 5)
    }

    // MARK: - range(selStart:selExtent:) — outbound

    func testRange_roundTrip() {
        // range(selStart:3, selExtent:7) == NSRange{3,4}
        let result = TextOffsetBridge.range(selStart: 3, selExtent: 7)
        XCTAssertEqual(result.location, 3)
        XCTAssertEqual(result.length, 4)
    }

    func testRange_reversedAnchors() {
        // range(selStart:7, selExtent:3) == NSRange{3,4}  (reversed anchors → min/abs, EC-06)
        let result = TextOffsetBridge.range(selStart: 7, selExtent: 3)
        XCTAssertEqual(result.location, 3)
        XCTAssertEqual(result.length, 4)
    }

    // MARK: - clamp(_:length:)

    func testClamp_noCursor() {
        // clamp(-1, length:5) == 0  (no-cursor, EC-08)
        XCTAssertEqual(TextOffsetBridge.clamp(-1, length: 5), 0)
    }

    func testClamp_withinBounds() {
        XCTAssertEqual(TextOffsetBridge.clamp(3, length: 10), 3)
    }

    func testClamp_upperBound() {
        XCTAssertEqual(TextOffsetBridge.clamp(15, length: 10), 10)
    }

    func testClamp_zero() {
        XCTAssertEqual(TextOffsetBridge.clamp(0, length: 5), 0)
    }

    // MARK: - Surrogate pair (UTF-16, EC-02, NFR-01)

    func testCaretOffset_surrogatePair() {
        // surrogate: NSString "👋..." (emoji len 2 UTF-16), caretOffset(NSRange{2,0}) == 2 not 1
        // "👋" occupies UTF-16 positions 0 and 1; the caret at position 2 (just after the emoji)
        // must be 2 in UTF-16 code units — the same view as the shared Kotlin module.
        let nsText = "👋hello" as NSString
        // NSString("👋hello").length == 7: emoji 2 + "hello" 5
        XCTAssertEqual(nsText.length, 7)
        let caret = TextOffsetBridge.caretOffset(from: NSRange(location: 2, length: 0), in: nsText)
        XCTAssertEqual(caret, 2)  // UTF-16 offset 2, NOT grapheme-count 1
    }
}
