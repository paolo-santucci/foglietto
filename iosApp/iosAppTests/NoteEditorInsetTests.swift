import XCTest
import UIKit
@testable import iosApp
import shared

private final class StubSettingsRepository: SettingsRepository {
    var storedSettings = AppSettings(colorScheme: AppColorScheme.follow, fontSizeIndex: 8)

    func load() -> AppSettings { storedSettings }
    func save(settings: AppSettings) { storedSettings = settings }
}

@MainActor
private final class TestSafeAreaWindow: UIWindow {
    var safeAreaTop: CGFloat

    init(safeAreaTop: CGFloat) {
        self.safeAreaTop = safeAreaTop
        super.init(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    }

    required init?(coder: NSCoder) {
        return nil
    }

    override var safeAreaInsets: UIEdgeInsets {
        UIEdgeInsets(top: safeAreaTop, left: 0, bottom: 0, right: 0)
    }
}

@MainActor
final class NoteEditorInsetTests: XCTestCase {
    func testRestoringShortNoteResetsScrolledLongNoteWithoutOpeningKeyboard() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = makeScrolledEditor(in: window)
        let model = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = NoteEditor.Coordinator(viewModel: model)

        model.populate("Restored note")
        coordinator.reconcileText(in: view)
        view.layoutIfNeeded()

        XCTAssertEqual(view.text, "Restored note")
        XCTAssertEqual(view.selectedRange, NSRange(location: 0, length: 0))
        XCTAssertEqual(view.contentOffset.y, -view.adjustedContentInset.top, accuracy: 0.5)
        XCTAssertFalse(view.isFirstResponder)
    }

    func testRestoringIdenticalTextStillRevealsBeginning() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = makeScrolledEditor(in: window)
        let model = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = NoteEditor.Coordinator(viewModel: model)
        model.updateText(view.text)
        coordinator.reconcileText(in: view)
        XCTAssertGreaterThan(view.contentOffset.y, 0)

        model.populate(view.text)
        coordinator.reconcileText(in: view)
        view.layoutIfNeeded()

        XCTAssertEqual(view.selectedRange, NSRange(location: 0, length: 0))
        XCTAssertEqual(view.contentOffset.y, -view.adjustedContentInset.top, accuracy: 0.5)
    }

    func testEditingAfterRestoreDoesNotResetSelectionOrScrollAgain() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = makeScrolledEditor(in: window)
        let model = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = NoteEditor.Coordinator(viewModel: model)
        model.populate(view.text)
        coordinator.reconcileText(in: view)
        view.layoutIfNeeded()
        view.selectedRange = NSRange(location: 100, length: 0)
        view.setContentOffset(CGPoint(x: 0, y: 200), animated: false)
        view.layoutIfNeeded()
        let offset = view.contentOffset
        let selection = view.selectedRange

        model.updateText(view.text)
        coordinator.reconcileText(in: view)
        view.setNeedsLayout()
        view.layoutIfNeeded()

        XCTAssertEqual(view.contentOffset, offset)
        XCTAssertEqual(view.selectedRange, selection)
    }

    func testDetachedRestoreUsesInsetsFromFirstAttachedLayout() {
        let view = NoteEditor.NoteTextView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        let model = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = NoteEditor.Coordinator(viewModel: model)
        model.populate("Restored before attachment")
        coordinator.reconcileText(in: view)
        view.layoutIfNeeded()
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        window.addSubview(view)
        view.contentInsetAdjustmentBehavior = .never
        view.contentInset.top = 103
        view.layoutIfNeeded()

        XCTAssertEqual(view.contentOffset.y, -103, accuracy: 0.5)
        XCTAssertFalse(view.isFirstResponder)
    }

    private func makeScrolledEditor(in window: UIWindow) -> NoteEditor.NoteTextView {
        let view = NoteEditor.NoteTextView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        window.addSubview(view)
        view.contentInsetAdjustmentBehavior = .never
        view.contentInset.top = 103
        view.text = String(repeating: "A line of the previous note\n", count: 150)
        view.selectedRange = NSRange(location: (view.text as NSString).length, length: 0)
        view.layoutIfNeeded()
        view.setContentOffset(CGPoint(x: 0, y: 400), animated: false)
        return view
    }

    func testNativeNavigationSafeAreaIsNotAddedToTextPadding() {
        let view = NoteEditor.NoteTextView()
        let safeAreaTops: [CGFloat] = [0, 20, 47, 59]
        for safeAreaTop in safeAreaTops {
            let window = TestSafeAreaWindow(safeAreaTop: safeAreaTop)
            window.addSubview(view)
            view.textContainerInset = UIEdgeInsets(top: 99, left: 1, bottom: 1, right: 1)
            view.reconcileTopInset()
            XCTAssertEqual(view.textContainerInset.top, 0)
            XCTAssertEqual(view.textContainerInset.left, ChromeMetrics.chromeHorizontalInset)
            XCTAssertEqual(view.textContainerInset.right, ChromeMetrics.chromeHorizontalInset)
            XCTAssertEqual(view.contentInsetAdjustmentBehavior, .automatic)
            view.removeFromSuperview()
        }
    }

    func testDetachedViewKeepsLastAppliedInsets() {
        let view = NoteEditor.NoteTextView()
        let applied = UIEdgeInsets(top: 3, left: 16, bottom: 5, right: 16)
        view.textContainerInset = applied
        view.reconcileTopInset()
        XCTAssertEqual(view.textContainerInset, applied)
    }

    func testAttachmentAppliesContentPaddingAndDetachmentPreservesIt() {
        let view = NoteEditor.NoteTextView()
        view.textContainerInset.top = 99
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        window.addSubview(view)
        XCTAssertEqual(view.textContainerInset.top, 0)
        let applied = view.textContainerInset
        view.removeFromSuperview()
        view.reconcileTopInset()
        XCTAssertEqual(view.textContainerInset, applied)
    }

    func testConvergedTopDoesNotRewriteInsets() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = NoteEditor.NoteTextView()
        window.addSubview(view)
        let applied = UIEdgeInsets(top: 0, left: 99, bottom: 99, right: 99)
        view.textContainerInset = applied
        view.reconcileTopInset()
        XCTAssertEqual(view.textContainerInset, applied)
    }

    func testReconciliationPreservesTextSelectionAndTypingAttributes() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = NoteEditor.NoteTextView()
        window.addSubview(view)
        view.text = "mid-edit content"
        view.selectedRange = NSRange(location: 4, length: 7)
        view.typingAttributes = [.font: UIFont.monospacedSystemFont(ofSize: 21, weight: .regular)]
        view.textContainerInset.top = 99
        let text = view.text
        let selection = view.selectedRange
        let typing = view.typingAttributes as NSDictionary

        NoteEditor.reconcileInset(of: view)

        XCTAssertEqual(view.textContainerInset.top, 0)
        XCTAssertEqual(view.text, text)
        XCTAssertEqual(view.selectedRange, selection)
        XCTAssertEqual(view.typingAttributes as NSDictionary, typing)
    }

    func testSafeAreaChangeDoesNotMoveTextContainerPadding() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = NoteEditor.NoteTextView()
        window.addSubview(view)
        view.text = "rotation keeps my text"
        view.selectedRange = NSRange(location: 9, length: 4)
        let applied = view.textContainerInset
        let selection = view.selectedRange
        window.safeAreaTop = 0
        view.safeAreaInsetsDidChange()
        XCTAssertEqual(view.textContainerInset, applied)
        XCTAssertEqual(view.text, "rotation keeps my text")
        XCTAssertEqual(view.selectedRange, selection)
    }

    func testReconciliationLeavesOtherTextViewsUntouched() {
        let view = UITextView()
        let applied = view.textContainerInset
        NoteEditor.reconcileInset(of: view)
        XCTAssertEqual(view.textContainerInset, applied)
    }

    func testPasteWhileIdleRevealsFullContentFromRestingTop() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = NoteEditor.NoteTextView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        window.addSubview(view)
        let model = NoteViewModel(settings: StubSettingsRepository())
        let coordinator = NoteEditor.Coordinator(viewModel: model)
        coordinator.textView = view
        view.delegate = coordinator

        UIPasteboard.general.string = "First pasted line\nSecond pasted line"
        coordinator.pasteAtCaret()
        view.layoutIfNeeded()

        XCTAssertEqual(view.text, "First pasted line\nSecond pasted line")
        XCTAssertEqual(
            view.selectedRange,
            NSRange(location: (view.text as NSString).length, length: 0)
        )
        XCTAssertEqual(view.contentOffset.y, -view.adjustedContentInset.top, accuracy: 0.5)
        XCTAssertFalse(view.isFirstResponder)
    }

    func testIdleOffsetAboveRestingRangeClampsBack() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = makeScrolledEditor(in: window)
        XCTAssertEqual(view.contentOffset.y, 400, accuracy: 0.5)
        let coordinator = NoteEditor.Coordinator(viewModel: NoteViewModel(settings: StubSettingsRepository()))
        view.delegate = coordinator

        view.setContentOffset(CGPoint(x: 0, y: -500), animated: false)

        XCTAssertEqual(view.contentOffset.y, -103, accuracy: 0.5)
        XCTAssertFalse(view.isFirstResponder)
    }

    func testEditingSessionOffsetIsNeverClamped() {
        let window = TestSafeAreaWindow(safeAreaTop: 59)
        let view = NoteEditor.NoteTextView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        window.addSubview(view)
        view.contentInsetAdjustmentBehavior = .never
        view.contentInset.top = 103
        view.text = String(repeating: "Line\n", count: 5)
        view.layoutIfNeeded()
        view.becomeFirstResponder()

        view.setContentOffset(CGPoint(x: 0, y: -500), animated: false)
        view.clampIdleOffset()

        XCTAssertEqual(view.contentOffset.y, -500, accuracy: 0.5)
    }

    func testRestingRangeCollapsesShortContentToTopAnchor() {
        let range = NoteEditor.NoteTextView.restingRange(
            contentSize: CGSize(width: 390, height: 200),
            boundsHeight: 600,
            adjustedInset: UIEdgeInsets(top: 103, left: 0, bottom: 0, right: 0)
        )
        XCTAssertEqual(range.lowerBound, -103, accuracy: 0.5)
        XCTAssertEqual(range.upperBound, -103, accuracy: 0.5)
    }

    func testRestingRangeCoversScrolledTallContent() {
        let range = NoteEditor.NoteTextView.restingRange(
            contentSize: CGSize(width: 390, height: 2000),
            boundsHeight: 600,
            adjustedInset: UIEdgeInsets(top: 103, left: 0, bottom: 14, right: 0)
        )
        XCTAssertEqual(range.lowerBound, -103, accuracy: 0.5)
        XCTAssertEqual(range.upperBound, 1414, accuracy: 0.5)
    }
}
