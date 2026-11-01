// TextOffsetBridge.swift
// iosApp
//
// The SINGLE NSRange ↔ shared-Int UTF-16 offset-conversion boundary (FR-06, NFR-04).
// All offsets are NSString UTF-16 code units — nsText.length is used exclusively.
// Do NOT use Swift grapheme-count or index arithmetic here (the surrogate trap, NFR-04).
//
// Spec refs: FR-06, FR-07, FR-08, NFR-04; §5.1.c / §5.1.e; §6.1 EC-02/EC-06/EC-08

import Foundation

/// The one NSRange ↔ shared-`Int` conversion site.
///
/// Static funcs only. All offsets are NSString UTF-16 code units.
/// Uses `nsText.length` (NSString UTF-16 count) exclusively — never grapheme-count
/// or index-based arithmetic (EC-02, NFR-04: an emoji is 1 grapheme but 2 UTF-16 units).
enum TextOffsetBridge {

    // MARK: - Continuation caret (FR-03/FR-04, §5.1.e)

    /// Convert a `UITextView.selectedRange` into the UTF-16 caret offset the shared API expects.
    ///
    /// Uses `range.location` (pre-edit caret position), clamped to `[0, nsText.length]` via NSString UTF-16 length (FR-08).
    /// - Parameters:
    ///   - range: The `selectedRange` from the `UITextView` at the moment of the pre-edit hook.
    ///   - nsText: The `UITextView.text` cast to `NSString` — UTF-16 length authority.
    /// - Returns: A clamped UTF-16 offset suitable for `ListContinuation.shared.process(caretOffset:)`.
    static func caretOffset(from range: NSRange, in nsText: NSString) -> Int {
        return clamp(range.location, length: nsText.length)
    }

    /// Build a collapsed `NSRange` at a UTF-16 offset returned by the shared API.
    ///
    /// Clamps `offset` to `[0, nsText.length]` (FR-08) so the range is always valid even if
    /// the shared module returns an offset at the exact end of the buffer.
    /// - Parameters:
    ///   - offset: UTF-16 code-unit offset (e.g. `ContinuationResult.caret`).
    ///   - nsText: The NSString whose `length` bounds the clamp.
    /// - Returns: `NSRange(location: clamp(offset, length), length: 0)`.
    static func collapsedRange(at offset: Int, in nsText: NSString) -> NSRange {
        return NSRange(location: clamp(offset, length: nsText.length), length: 0)
    }

    // MARK: - Indent / outdent selection (FR-07, §5.1.e)

    /// Convert an `NSRange` selection into the `(selStart, selExtent)` pair the shared API expects.
    ///
    /// `selExtent` is an **ABSOLUTE** UTF-16 offset (`location + length`), NOT a length —
    /// this is the I-1 critical finding from the assessment. The shared `LineIndent` API
    /// mirrors Flutter's `TextSelection.extentOffset`, which is absolute (FR-07).
    ///
    /// - Parameter range: The current `UITextView.selectedRange`.
    /// - Returns: `(selStart: range.location, selExtent: range.location + range.length)`.
    static func selection(from range: NSRange) -> (selStart: Int, selExtent: Int) {
        return (selStart: range.location, selExtent: range.location + range.length)
    }

    /// Convert an `IndentResult`'s `(selStart, selExtent)` back into an `NSRange`.
    ///
    /// Handles reversed anchors (e.g. a backward selection where `selExtent < selStart`)
    /// via `min`/`abs` (EC-06). The shared module accepts unordered anchors natively, so
    /// M3 never needs to normalize the direction before sending inbound — only outbound.
    ///
    /// - Parameters:
    ///   - selStart: `IndentResult.selStart` — an absolute UTF-16 offset.
    ///   - selExtent: `IndentResult.selExtent` — an absolute UTF-16 offset (NOT a length).
    /// - Returns: `NSRange(location: min(s,e), length: abs(e − s))`.
    static func range(selStart: Int, selExtent: Int) -> NSRange {
        return NSRange(
            location: min(selStart, selExtent),
            length: abs(selExtent - selStart)
        )
    }

    // MARK: - Primitive clamp (FR-08, §5.1.e)

    /// Clamp a UTF-16 offset to `[0, length]`.
    ///
    /// Special case: `offset == -1` (the shared API's "no cursor" sentinel) is treated as
    /// `max(0, offset)` which also yields `0`, satisfying FR-08 without a separate branch —
    /// `max(0, min(-1, length))` == `max(0, -1)` == `0`.
    ///
    /// - Parameters:
    ///   - offset: Raw UTF-16 offset, possibly out of bounds or `-1`.
    ///   - length: `nsText.length` — the exclusive upper bound.
    /// - Returns: A value in `[0, length]`.
    static func clamp(_ offset: Int, length: Int) -> Int {
        return max(0, min(offset, length))
    }
}
