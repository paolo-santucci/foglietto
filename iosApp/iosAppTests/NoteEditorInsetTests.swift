import XCTest
import UIKit
@testable import iosApp

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
}
