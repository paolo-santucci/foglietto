import UIKit
import SwiftUI
import shared

// MARK: - Editor font

// The editor renders the SYSTEM DEFAULT font family and size (user direction —
// always the system default): `UIFont.preferredFont(forTextStyle: .body)` —
// the system family (San Francisco) at the user's Dynamic Type body size.
// Line height 1.4 via `NSMutableParagraphStyle.lineHeightMultiple` on
// `typingAttributes`, so newly typed characters inherit the style.
private func applyFont(to textView: UITextView) {
    let font = UIFont.preferredFont(forTextStyle: .body)

    let style = NSMutableParagraphStyle()
    style.lineHeightMultiple = 1.4

    textView.font = font
    textView.typingAttributes = [
        .font: font,
        .paragraphStyle: style
    ]
}

/// `UIViewRepresentable` wrapping a `NoteTextView` — the editor content surface.
///
/// UIKit adjusts the scroll view for native navigation and safe areas. The text container
/// adds only content padding. Reconciliation writes nothing but the inset, so text, selection, typing
/// attributes, and scroll offset survive every tick (EC-12).
/// Explicit population resets selection and reveals the beginning after an attached layout.
///
/// `makeUIView` publishes the coordinator into `coordinatorBox.coordinator` (weak) so the
/// toolbar closures can dispatch actions to it without UIKit coupling. `updateUIView` wires
/// the undo-ability mirror onto `CoordinatorBox.canUndo`. Both are additive and
/// idempotent; font and text are reconciled on divergence only, so an update tick never
/// clobbers in-session content when only the safe area changes (EC-12).
///
/// CANON GAP: `UIColor.systemBackground` is the iOS semantic stand-in for the
/// `--view-bg-color` token; swap it for a named asset color once the catalog holds one.
struct NoteEditor: UIViewRepresentable {

    // MARK: Properties

    /// The presentation-state model, bound at the call site by the composition root.
    let viewModel: NoteViewModel

    /// Reference-type bridge that receives the live coordinator once it is created, so the
    /// toolbar closures can reach it without UIKit coupling.
    let coordinatorBox: CoordinatorBox?

    // MARK: Init

    init(
        viewModel: NoteViewModel,
        coordinatorBox: CoordinatorBox? = nil
    ) {
        self.viewModel = viewModel
        self.coordinatorBox = coordinatorBox
    }

    // MARK: Editor text view

    /// `UITextView` whose content padding is independent of UIKit's adjusted scroll insets.
    ///
    /// Reconciliation is window-guarded and divergence-only: only `textContainerInset` is
    /// ever written, so in-session text, selection, and typing attributes survive every tick
    /// (EC-12). A detached view keeps the last applied inset rather than falling back to a
    /// constant (EC-24).
    final class NoteTextView: UITextView {
        private var needsRestoredNoteScrollReset = false
        /// The adjusted inset the restore anchored to. While the editor is idle and still
        /// parked on that anchor, a later geometry change re-anchors instead of leaving the
        /// first line hidden above the top edge.
        private var restoredAnchorInset: UIEdgeInsets?

        /// Top of the resting viewport: the content offset that puts the first line under the
        /// adjusted (safe-area + navigation) inset rather than at the raw view top.
        private var restingTopAnchor: CGPoint {
            CGPoint(x: -adjustedContentInset.left, y: -adjustedContentInset.top)
        }

        func revealRestoredNote() {
            selectedRange = NSRange(location: 0, length: 0)
            needsRestoredNoteScrollReset = true
            restoredAnchorInset = nil
            setNeedsLayout()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            guard window != nil, bounds.width > 0, bounds.height > 0 else { return }

            if needsRestoredNoteScrollReset {
                needsRestoredNoteScrollReset = false
                restoredAnchorInset = adjustedContentInset
                setContentOffset(restingTopAnchor, animated: false)
                return
            }

            settleRestoredAnchor()
        }

        /// Keeps a freshly restored, untouched viewport on the top anchor.
        ///
        /// The restore lands while the menu is dismissing, before the safe area / navigation bar
        /// has settled, so the one-shot reset can anchor to a zero inset — and UIKit's own
        /// text-assignment relayout can afterwards park the offset at the raw view top. Either
        /// way the first lines end up clipped above the screen until the keyboard rebuilds the
        /// viewport, which is exactly the bug this guards.
        ///
        /// While the anchor is tracked, an inset change re-anchors and a drifting offset is
        /// pulled back. Tracking ends at the first user interaction — a drag
        /// (`endRestoredAnchorTracking`) or focus (`becomeFirstResponder`) — so it never fights
        /// real scrolling or editing.
        func settleRestoredAnchor() {
            guard let anchored = restoredAnchorInset,
                  !isFirstResponder, !isTracking, !isDecelerating,
                  window != nil, bounds.width > 0, bounds.height > 0 else { return }

            if adjustedContentInset != anchored {
                let previousTop = CGPoint(x: -anchored.left, y: -anchored.top)
                guard abs(contentOffset.y - previousTop.y) < 0.5 else {
                    restoredAnchorInset = nil
                    return
                }
                restoredAnchorInset = adjustedContentInset
                setContentOffset(restingTopAnchor, animated: false)
                return
            }

            guard abs(contentOffset.y - restingTopAnchor.y) > 0.5 else { return }
            setContentOffset(restingTopAnchor, animated: false)
        }

        /// Stops tracking the restore anchor — the viewport is the user's from here on.
        func endRestoredAnchorTracking() {
            restoredAnchorInset = nil
        }

        /// Focus ends the restore tracking: UIKit owns the viewport once editing starts.
        override func becomeFirstResponder() -> Bool {
            let accepted = super.becomeFirstResponder()
            if accepted { endRestoredAnchorTracking() }
            return accepted
        }

        /// Any inset change re-runs the layout, so a parked viewport follows the anchor.
        override var contentInset: UIEdgeInsets {
            didSet {
                if restoredAnchorInset != nil { setNeedsLayout() }
            }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            reconcileTopInset()
            if needsRestoredNoteScrollReset { setNeedsLayout() }
        }

        override func safeAreaInsetsDidChange() {
            super.safeAreaInsetsDidChange()
            reconcileTopInset()
            if needsRestoredNoteScrollReset || restoredAnchorInset != nil { setNeedsLayout() }
        }

        /// Applies content padding without adding the safe area a second time.
        func reconcileTopInset() {
            guard window != nil else { return }
            let desired = ChromeMetrics.contentTopGap
            guard textContainerInset.top != desired else { return }
            textContainerInset = UIEdgeInsets(
                top: desired,
                left: ChromeMetrics.chromeHorizontalInset,
                bottom: 0,
                right: ChromeMetrics.chromeHorizontalInset
            )
        }

        // MARK: Idle resting-offset invariant

        /// Resting content-offset range for the given geometry: idle content may only sit
        /// between the adjusted top anchor and the scrolled bottom anchor. Content that
        /// fits the viewport collapses the range onto the top anchor alone.
        static func restingRange(
            contentSize: CGSize,
            boundsHeight: CGFloat,
            adjustedInset: UIEdgeInsets
        ) -> ClosedRange<CGFloat> {
            let top = -adjustedInset.top
            let bottom = max(top, contentSize.height - boundsHeight + adjustedInset.bottom)
            return top...bottom
        }

        /// Clamps a parked content offset back into the resting range so an idle editor
        /// never hangs content above the top inset (paste overshoot, post-restore
        /// relayout, keyboard dismissal). Guarded against attached layout only —
        /// UIKit's caret scrolling while first responder and the user's own scrolling
        /// (tracking/decelerating) are never touched, and valid offsets are not rewritten.
        func clampIdleOffset() {
            guard window != nil, bounds.width > 0, bounds.height > 0,
                  !isTracking, !isDecelerating, !isFirstResponder else { return }
            let range = Self.restingRange(
                contentSize: contentSize,
                boundsHeight: bounds.height,
                adjustedInset: adjustedContentInset
            )
            guard !range.contains(contentOffset.y) else { return }
            setContentOffset(
                CGPoint(
                    x: -adjustedContentInset.left,
                    y: min(max(contentOffset.y, range.lowerBound), range.upperBound)
                ),
                animated: false
            )
        }
    }

    // MARK: UIViewRepresentable

    /// Builds the editor with system-managed scroll insets and content-only text padding.
    func makeUIView(context: Context) -> UITextView {
        let textView = NoteTextView()

        textView.backgroundColor = .systemBackground
        textView.textColor = .label
        textView.contentInsetAdjustmentBehavior = .automatic

        textView.textContainerInset = UIEdgeInsets(
            top: ChromeMetrics.contentTopGap,
            left: ChromeMetrics.chromeHorizontalInset,
            bottom: 0,
            right: ChromeMetrics.chromeHorizontalInset
        )
        textView.textContainer.lineFragmentPadding = 0

        applyFont(to: textView)

        textView.delegate = context.coordinator
        context.coordinator.textView = textView
        coordinatorBox?.coordinator = context.coordinator

        return textView
    }

    /// Reconcile font, text, and inset on divergence; explicit population also resets the viewport.
    /// The font divergence check covers a live Dynamic Type change mid-session.
    func updateUIView(_ uiView: UITextView, context: Context) {
        let desired = UIFont.preferredFont(forTextStyle: .body)
        if uiView.font?.pointSize != desired.pointSize
            || uiView.font?.familyName != desired.familyName {
            applyFont(to: uiView)
        }

        context.coordinator.reconcileText(in: uiView)

        NoteEditor.reconcileInset(of: uiView)

        context.coordinator.onUndoStateChange = { [weak coordinatorBox] canUndo in
            // Value-guarded: updateUIView runs on every editor tick, and an
            // unconditional @Observable write during the update phase
            // self-invalidates the reading view — a self-sustaining loop.
            guard (coordinatorBox?.canUndo ?? false) != canUndo else { return }
            coordinatorBox?.canUndo = canUndo
        }
        context.coordinator.mirrorUndoState()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(viewModel: viewModel)
    }

    // MARK: - Update-leg seam

    /// The additive `updateUIView` reconciliation leg: window-guarded and divergence-only, so
    /// an update tick writes nothing but the inset when the safe area has moved
    /// (EC-12 / EC-24). A view that is not a `NoteTextView` is left untouched.
    ///
    /// Factored out as a context-free seam because `UIViewRepresentableContext` has no public
    /// initializer, so the update leg cannot be driven directly from the test target.
    static func reconcileInset(of uiView: UITextView) {
        (uiView as? NoteTextView)?.reconcileTopInset()
    }

    // MARK: - Private helpers

    // MARK: - Coordinator

    /// `UITextViewDelegate` implementing the single pre-edit hook and the toolbar action
    /// hooks, plus the undo-ability mirror that drives the Undo circle's visibility.
    ///
    /// Decision table (top-down; first matching row wins):
    ///
    /// | Condition | Action | Return |
    /// |-----------|--------|--------|
    /// | text=="\n" AND range.length==0 AND process!=nil | write result text+caret, mirror VM | false |
    /// | text=="\n" AND range.length==0 AND process==nil | passthrough | true |
    /// | any other edit (non-newline, paste, "\n" with selection) | passthrough | true |
    ///
    /// Hard rules (FR-02 / EC-03): no post-edit diff predicate, and no boolean latch or
    /// re-entrancy flag — a direct `.text =` assignment in UIKit does not re-enter
    /// `shouldChangeTextIn`. `process` is computed from the pre-edit text and `range.location`.
    ///
    /// Action hooks dispatched by the bottom toolbar: `copyToPasteboard()`, `pasteAtCaret()`,
    /// `closeKeyboard()`, `applyIndentResult(text:range:)`, `undo()`. The optional
    /// `onUndoStateChange` callback is nil-safe when `textView` is nil.
    final class Coordinator: NSObject, UITextViewDelegate {

        // MARK: Properties

        private let viewModel: NoteViewModel
        private var populationRevision = 0

        /// Weak handle to the live text view, set by `makeUIView` right after construction.
        /// `nil`-safe after the view deallocates — no retain cycle (EC-13).
        weak var textView: UITextView?

        /// Called whenever the mirrored undo-ability state is re-derived; wired
        /// to `CoordinatorBox.canUndo` in `updateUIView` so the Undo circle
        /// renders only when there is something to undo.
        var onUndoStateChange: ((Bool) -> Void)?

        // MARK: Init

        init(viewModel: NoteViewModel) {
            self.viewModel = viewModel
        }

        func reconcileText(in textView: UITextView) {
            if textView.text != viewModel.text {
                textView.text = viewModel.text
            }
            guard populationRevision != viewModel.populationRevision else { return }
            populationRevision = viewModel.populationRevision
            (textView as? NoteTextView)?.revealRestoredNote()
        }

        // MARK: Action hooks (dispatched by BottomToolbar)

        /// Copy the buffer text to the system pasteboard (FR-16).
        ///
        /// Copies even when the buffer is empty — the share surface, not copy, is gated on
        /// non-empty (FR-08 vs FR-16).
        func copyToPasteboard() {
            UIPasteboard.general.string = viewModel.text
        }

        /// Insert the current pasteboard string at the caret position (FR-16 / EC-03).
        ///
        /// No-op when the pasteboard string is `nil` or empty (EC-03), and no-op after
        /// teardown (EC-13).
        func pasteAtCaret() {
            guard let tv = textView else { return }
            guard let paste = UIPasteboard.general.string, !paste.isEmpty else { return }
            let range = tv.selectedRange
            if let textRange = tv.selectedTextRange {
                tv.replace(textRange, withText: paste)
            } else {
                let mutable = NSMutableString(string: tv.text ?? "")
                mutable.replaceCharacters(in: range, with: paste)
                tv.text = mutable as String
                let newLocation = range.location + (paste as NSString).length
                tv.selectedRange = NSRange(location: newLocation, length: 0)
            }
            viewModel.updateText(tv.text ?? "")
            mirrorUndoState()
            (tv as? NoteTextView)?.clampIdleOffset()
        }

        /// Resign first responder on the live text view, dismissing the keyboard (FR-16).
        ///
        /// No-op after teardown (EC-13).
        func closeKeyboard() {
            textView?.resignFirstResponder()
        }

        func clearEditor(savingTo recovery: RecoveryRepository) {
            guard let tv = textView, !tv.text.isEmpty,
                  let range = tv.textRange(from: tv.beginningOfDocument, to: tv.endOfDocument) else { return }
            tv.unmarkText()
            _ = SaveNoteToRecovery(repository: recovery).invoke(text: tv.text)
            tv.undoManager?.beginUndoGrouping()
            tv.replace(range, withText: "")
            tv.undoManager?.endUndoGrouping()
            viewModel.updateText(tv.text ?? "")
            mirrorUndoState()
            (tv as? NoteTextView)?.clampIdleOffset()
        }

        /// Assign the indent/outdent result back to the live text view (FR-17).
        ///
        /// Sets `textView.text` and `textView.selectedRange`. No-op after teardown (EC-13).
        ///
        /// - Parameters:
        ///   - text:  The new full buffer text returned by `viewModel.indent`/`outdent`.
        ///   - range: The new selection returned by the same call (UTF-16 NSRange).
        func applyIndentResult(text: String, range: NSRange) {
            guard let tv = textView else { return }
            tv.text = text
            tv.selectedRange = range
            mirrorUndoState()
            (tv as? NoteTextView)?.clampIdleOffset()
        }

        /// Invoke the text view's native undo manager and mirror the reverted
        /// buffer text back into the view model (DD-10).
        ///
        /// Undo goes through `UITextView.undoManager` — the same mechanism the
        /// keyboard's built-in undo uses — so reversed edits arrive as ordinary
        /// UIKit commits (including the `textViewDidChange` mirror when the edit
        /// registers as one). The explicit re-mirror is the safety net for
        /// programmatic reversals that do not surface as delegate events.
        /// No-op after teardown (EC-13).
        func undo() {
            guard let tv = textView else { return }
            tv.undoManager?.undo()
            viewModel.updateText(tv.text ?? "")
            mirrorUndoState()
            (tv as? NoteTextView)?.clampIdleOffset()
        }

        /// Re-derive the undoability state off the live text view and report it
        /// through `onUndoStateChange` (the Undo circle's visibility mirror).
        /// A detached view reports `false` — there is nothing to undo once the
        /// surface is gone (EC-13). Internal (not private): `updateUIView` of the
        /// enclosing representable drives the initial sync through this method,
        /// and nested-type privacy would make it inaccessible there.
        func mirrorUndoState() {
            let canUndo = textView?.undoManager?.canUndo ?? false
            onUndoStateChange?(canUndo)
        }

        // MARK: UITextViewDelegate — the single pre-edit hook (FR-02)

        /// The only `UITextViewDelegate` text-gating method (FR-02), implementing the
        /// decision table above.
        func textView(
            _ textView: UITextView,
            shouldChangeTextIn range: NSRange,
            replacementText text: String
        ) -> Bool {

            if text == "\n", range.length == 0 {
                let caret = TextOffsetBridge.caretOffset(
                    from: range,
                    in: textView.text as NSString
                )

                if let result = ListContinuation.shared.process(
                    fullText: textView.text,
                    caretOffset: Int32(caret)
                ) {
                    textView.text = result.text

                    textView.selectedRange = TextOffsetBridge.collapsedRange(
                        at: Int(result.caret),
                        in: textView.text as NSString
                    )

                    viewModel.updateText(result.text)

                    return false
                }

                return true
            }

            return true
        }

        // MARK: UITextViewDelegate — committed-text mirror

        /// Mirror committed text into the `NoteViewModel` after every UIKit-applied edit
        /// (FR-13), and re-mirror the undo-ability state for the Undo circle's visibility.
        ///
        /// The continuation branch (which returns `false`) mirrors inside
        /// `shouldChangeTextIn` instead, because `textViewDidChange` never runs for it.
        func textViewDidChange(_ textView: UITextView) {
            viewModel.updateText(textView.text)
            mirrorUndoState()
        }

        // MARK: UIScrollViewDelegate — idle viewport settle

        /// Watches every offset write UIKit performs while no editing session is active,
        /// so the invariant in `NoteTextView.clampIdleOffset` also runs for adjustments
        /// the coordinator's own mutation sites never cause — the late TextKit relayout
        /// after a restore and UIKit's stale caret-scrolling on programmatic edits.
        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard let editor = scrollView as? NoteTextView else { return }
            editor.clampIdleOffset()
            editor.settleRestoredAnchor()
        }

        /// A user drag owns the viewport from then on — the restore anchor stops tracking.
        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            (scrollView as? NoteTextView)?.endRestoredAnchorTracking()
        }
    }
}
