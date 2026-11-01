// iosApp
//
// @Observable presentation state model for the ephemeral text buffer.
//
// Spec refs: FR-09, FR-10, FR-11, FR-12, FR-13 (init/updateText), FR-16, FR-21;
//            §5.1.c; §6.1 EC-01/EC-06/EC-07/EC-11; §5.3 seam
//
// IMPORTANT: No save / persist / recovery-write member is permitted on this type (FR-12).

import Foundation
import Observation
import shared

/// `@Observable` presentation state for the text buffer.
///
/// - `text` is the ephemeral in-session mirror of the `UITextView` content.
///   On a fresh launch it is `""` — the initial state IS empty; there is no explicit clear step (FR-13).
///
/// (The former `fontSizeIndex` slot state is retired: the editor always renders
/// the system default font family and size — user direction.)
///
/// Two distinct entry points share one private `apply(text:)` body (FR-10 / §5.3 seam):
///   - `updateText(_:)` — keystroke origin (M3 uses this).
///   - `populate(_:)` — programmatic-restore origin (M4 recovery/share seed; defined-not-wired in M3).
///
/// `indent(in:of:)` / `outdent(in:of:)` delegate to `LineIndent.shared` via `TextOffsetBridge`,
/// apply the returned text atomically, and return the new `(text, NSRange)` to the caller so the
/// `UITextView.selectedRange` can be updated (FR-11).
///
/// NB: NO save / persist / recovery-write member anywhere on this type (FR-12).
@Observable
// Non-final: the iosAppTests `UpdateTextSpy` subclasses this to count `updateText`
// invocations for the no-re-entrancy coordinator test (M3 plan §6, TASK-08 finding).
// `@Observable` works on non-final classes; the structural gate does not require `final`.
class NoteViewModel {

    // MARK: - Observed state

    /// The ephemeral in-session text content.  Starts `""` on every fresh launch (FR-13, EC-01).
    private(set) var text: String

    // MARK: - Init

    /// Designated initialiser — accepts a `SettingsRepository` for dependency injection.
    ///
    /// The production instance is always supplied by the composition root in `iosAppApp.init()`
    /// via `IosSettingsFactoryKt.createIosSettingsRepository()` — a Kotlin top-level factory
    /// function that returns the concrete `SettingsRepository` implementation.  Unit tests pass
    /// a stub.  No default value is provided here because the composition root (FR-03 / CM-1)
    /// is the sole construction site; a default would create a second factory call outside it.
    ///
    /// - Parameter settings: The settings repository injected by the composition root.
    ///   (The font-size slot is retired — the repository dependency stays because the
    ///   composition root's construction contract is unchanged.)
    init(settings: SettingsRepository) {
        self.text = ""
    }

    // MARK: - Text update (FR-10)

    /// Keystroke-origin text update.  Called by the `Coordinator` after `textViewDidChange`
    /// or after a fired list-continuation rewrites the `UITextView` content directly.
    ///
    /// Shares the `apply(text:)` body with `populate(_:)` but remains a distinct entry point
    /// so M4 can gate on origin (FR-10 / §5.3 seam).
    func updateText(_ newText: String) {
        apply(text: newText)
    }

    /// Programmatic-restore origin.  Reserved for M4 recovery-restore taps and inbound share seeding.
    ///
    /// Defined here (once, exactly) so M4 can plug in cleanly — but **no M3 caller invokes it**
    /// (FR-10 / §5.3 seam).  The gate asserts this method is defined exactly once and is uncalled
    /// in M3 source.
    func populate(_ newText: String) {
        apply(text: newText)
    }

    // MARK: - Indent / outdent (FR-11)

    /// Indent the current selection by one unit.
    ///
    /// Converts `selection` (NSRange) to the `(selStart, selExtent)` absolute-offset pair the
    /// shared API expects via `TextOffsetBridge.selection(from:)` (FR-07), calls
    /// `LineIndent.shared.indent`, converts the `IndentResult` back to an `NSRange` via
    /// `TextOffsetBridge.range(selStart:selExtent:)`, applies the new text atomically, and
    /// returns the pair so the caller can update `UITextView.selectedRange` (FR-11, EC-06).
    ///
    /// - Parameters:
    ///   - selection: The current `UITextView.selectedRange`.
    ///   - text: The current buffer text (the same value as `self.text` in a live session,
    ///           passed explicitly to match the spec §5.1.c signature).
    /// - Returns: The new `(text, selection)` to apply atomically to the `UITextView`.
    func indent(in selection: NSRange, of text: String) -> (text: String, selection: NSRange) {
        return applyIndentResult(
            LineIndent.shared.indent(
                text: text,
                selStart: Int32(TextOffsetBridge.selection(from: selection).selStart),
                selExtent: Int32(TextOffsetBridge.selection(from: selection).selExtent)
            )
        )
    }

    /// Outdent the current selection by one unit.
    ///
    /// Same contract as `indent(in:of:)` but removes one indent unit.  When the line has
    /// no leading indent the shared outdent is a no-op; the selection still round-trips
    /// through the bridge (EC-07).
    ///
    /// - Parameters:
    ///   - selection: The current `UITextView.selectedRange`.
    ///   - text: The current buffer text.
    /// - Returns: The new `(text, selection)` to apply atomically to the `UITextView`.
    func outdent(in selection: NSRange, of text: String) -> (text: String, selection: NSRange) {
        return applyIndentResult(
            LineIndent.shared.outdent(
                text: text,
                selStart: Int32(TextOffsetBridge.selection(from: selection).selStart),
                selExtent: Int32(TextOffsetBridge.selection(from: selection).selExtent)
            )
        )
    }

    // MARK: - Private

    /// Shared body of `updateText(_:)` and `populate(_:)` (the `copyWith(text:)` equivalent).
    ///
    /// The single mutation point for `self.text` — keeping the two public entry points
    /// as distinct symbols while avoiding duplicated logic (FR-10).
    private func apply(text: String) {
        self.text = text
    }

    /// Convert an `IndentResult` to the `(text, NSRange)` pair, apply text atomically, and return.
    ///
    /// `IndentResult.selStart` / `selExtent` are **absolute** UTF-16 offsets (not lengths);
    /// `TextOffsetBridge.range(selStart:selExtent:)` handles reversed anchors via `min`/`abs`
    /// (EC-06, FR-07).  The returned text is applied via `apply(text:)` so `self.text` stays
    /// in sync with the UITextView after an indent/outdent.
    private func applyIndentResult(_ result: IndentResult) -> (text: String, selection: NSRange) {
        let newText = result.text
        // Int32 → Int at the call site; bridge converts absolute offsets → NSRange (FR-07).
        let newRange = TextOffsetBridge.range(
            selStart: Int(result.selStart),
            selExtent: Int(result.selExtent)
        )
        apply(text: newText)
        return (text: newText, selection: newRange)
    }
}
