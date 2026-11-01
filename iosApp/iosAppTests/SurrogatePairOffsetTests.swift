// SurrogatePairOffsetTests.swift
// iosAppTests
//
// NFR-01 oracle: verifies that NSRange/NSString UTF-16 offsets round-trip
// correctly through TextOffsetBridge when a non-BMP surrogate pair is present
// in the text.  The emoji "hand wave" (U+1F44B) encodes as two UTF-16 code
// units (a surrogate pair) — so "👋hello" has NSString.length == 7, not 6.
//
// These tests are the authoritative CI check that the shared Kotlin offset
// model and the Swift NSString-backed TextOffsetBridge agree on UTF-16 unit
// count (NFR-01, FR-06, EC-02).
//
// CI-verified on macos-26 / Xcode 26 iOS Simulator.  No local Swift compile.
// Registration in the iosAppTests target is owned by TASK-08 (this file).
//
// Spec refs: NFR-01, FR-06, FR-08; §6.1 EC-02/EC-15; §7.2 section B/C; TASK-08.

import XCTest
@testable import iosApp
import shared

final class SurrogatePairOffsetTests: XCTestCase {

    // MARK: - Setup

    // "👋hello" as NSString:
    //   - UTF-16 length: emoji occupies code units 0..1 (surrogate pair) + "hello" 5 units = 7
    //   - grapheme count (Swift String): 6 ("👋", "h", "e", "l", "l", "o")
    // The shared Kotlin module uses UTF-16 offsets; TextOffsetBridge must match.

    private let nsText: NSString = "👋hello" as NSString

    // MARK: - Step 1: NSString.length reflects UTF-16 unit count (7, not 6 graphemes)

    /// Confirms the NSString UTF-16 code unit count of "👋hello".
    /// This is a pre-condition guard — if the runtime reports a different length the
    /// remaining tests are invalid and will fail loudly.
    func testNSStringLength_surrogatePair_is7() {
        // NFR-01 / EC-02: the emoji contributes 2 UTF-16 code units.
        XCTAssertEqual(nsText.length, 7,
            "NSString('👋hello').length must be 7 (2 surrogate + 5 ASCII) — not 6 graphemes (EC-02)")
    }

    // MARK: - Step 2: caretOffset returns UTF-16 unit 2, NOT grapheme index 1

    /// caretOffset(NSRange{2,0}, "👋hello") == 2 (UTF-16 unit after the emoji, not grapheme 1).
    ///
    /// A collapsed NSRange at location 2 is just past the two UTF-16 code units of the emoji.
    /// TextOffsetBridge must preserve this as-is — 2 in UTF-16 space, not 1 in grapheme space.
    func testCaretOffset_afterSurrogatePair_is2() {
        let range = NSRange(location: 2, length: 0)
        let offset = TextOffsetBridge.caretOffset(from: range, in: nsText)

        XCTAssertEqual(offset, 2,
            "caretOffset at UTF-16 position 2 (just past the emoji surrogate pair) must be 2, " +
            "not 1 grapheme — TextOffsetBridge works in UTF-16 code units (NFR-01/EC-02/FR-06)")
    }

    // MARK: - Step 3: collapsedRange(at:in:) returns NSRange{2,0}

    /// collapsedRange(at: 2, in: nsText) == NSRange{location: 2, length: 0}.
    func testCollapsedRange_at2_is_NSRange2_0() {
        let result = TextOffsetBridge.collapsedRange(at: 2, in: nsText)

        XCTAssertEqual(result.location, 2,
            "collapsedRange(at: 2).location must be 2 (EC-02/FR-06)")
        XCTAssertEqual(result.length, 0,
            "collapsedRange must produce a length-0 (collapsed) range (EC-02)")
    }

    // MARK: - Step 4: round-trip collapsedRange(caretOffset(NSRange{2,0})) == NSRange{2,0}

    /// Composing caretOffset then collapsedRange must be an identity on the UTF-16 position.
    ///
    /// round-trip: collapsedRange(at: caretOffset(NSRange{2,0}, nsText), in: nsText)
    ///          == NSRange{location: 2, length: 0}
    ///
    /// A non-zero round-trip error here would mean the shared module and UIKit diverge
    /// on surrogate-pair boundaries — that is the defect NFR-01 guards against.
    func testRoundTrip_caretOffset_then_collapsedRange_is_identity() {
        let original = NSRange(location: 2, length: 0)

        let offset = TextOffsetBridge.caretOffset(from: original, in: nsText)
        let recovered = TextOffsetBridge.collapsedRange(at: offset, in: nsText)

        XCTAssertEqual(recovered.location, original.location,
            "Round-trip NSRange{2,0} -> caretOffset -> collapsedRange must preserve location (NFR-01)")
        XCTAssertEqual(recovered.length, 0,
            "Round-trip must produce a collapsed range (length 0) (NFR-01)")
    }

    // MARK: - Step 5: ListContinuation.shared.process at surrogate-pair boundary does not crash

    /// Calls ListContinuation.shared.process with the emoji text and a caret at UTF-16 offset 2.
    /// The call is expected to return nil (no list marker in "👋hello") but must NOT crash.
    ///
    /// Rationale: the shared Kotlin ListContinuation operates on UTF-16 offsets.  A crash or
    /// thrown exception here would indicate an offset-range violation inside the shared module
    /// when a surrogate pair precedes the text.  nil is the valid "not a list" result (FR-05).
    func testListContinuation_atSurrogatePairBoundary_doesNotCrash() {
        // "👋hello" is not a list item, so process must return nil (FR-05).
        // NFR-01: it must not crash regardless.
        let result = ListContinuation.shared.process(
            fullText: nsText as String,
            caretOffset: 2
        )

        // nil means "not a list" — this is the expected result and confirms no crash.
        XCTAssertNil(result,
            "ListContinuation.shared.process('👋hello', caretOffset: 2) must return nil " +
            "(no list marker) and must not crash (NFR-01/FR-05)")
    }

    // MARK: - False-green guard: ensure this suite is non-empty

    /// Sentinel test that guarantees the test class has at least one executable test.
    ///
    /// CI guard (ios.yml test_ios): if XCTest somehow reports zero tests executed for this
    /// file, the test runner's exit code would be 0 but the false-green guard in the workflow
    /// catches it.  This test exists as an explicit belt-and-suspenders at the Swift level.
    func testSuiteIsNonEmpty() {
        // This method itself constitutes 1 test — satisfying the >= 1 suite-count invariant.
        XCTAssertTrue(true, "SurrogatePairOffsetTests suite is non-empty (false-green guard)")
    }
}
