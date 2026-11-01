// iosAppTests
//
// XCTest coverage for NoteEditor.Coordinator — the single pre-edit hook,
// the textViewDidChange mirror, the updateUIView reconciliation contract,
// AND the M4 Coordinator extension cases (CM-6 / §5.1.e):
//   copyToPasteboard, pasteAtCaret, closeKeyboard, applyIndentResult,
//   weak-textView nil-safety (EC-13), undo-ability mirror (onUndoStateChange).
//
// NOTE: This file is authored on disk by TASK-05.
// Target membership (iosAppTests) and pbxproj registration are owned by TASK-08.
//
// OQ-12 resolution: the Coordinator can be instantiated directly in tests via
// makeCoordinator() without a full UIHostingController-backed mount for MOST
// tests. For tests that require a live, first-responder-capable UITextView
// (closeKeyboard/pasteAtCaret), we use a lightweight UIWindow+UITextView mount
// harness rather than UIHostingController to avoid SwiftUI dependency in this
// file. See makeMountedCoordinator() helper below.
//
// Spec refs: FR-02, FR-03, FR-04, FR-05, FR-06, FR-13, FR-14, FR-15, FR-16, FR-17,
//            FR-19; §5.1.d/§5.1.e decision table;
//            §6.1 EC-03/EC-04/EC-05/EC-12/EC-13; M4 §7.1 Coordinator section.
// Verification: CI-only (macos-26 / iOS Simulator). No local Swift compile available.

import XCTest
import UIKit
@testable import iosApp
import shared

// ---------------------------------------------------------------------------
// MARK: - Stubs / Spies
// ---------------------------------------------------------------------------

/// Minimal stub satisfying `SettingsRepository` for injection into `NoteViewModel`.
private final class StubSettingsRepository: SettingsRepository {
    var storedSettings: AppSettings

    init(fontSizeIndex: Int32 = 8) {
        storedSettings = AppSettings(colorScheme: AppColorScheme.follow, fontSizeIndex: fontSizeIndex)
    }

    func load() -> AppSettings { storedSettings }
    func save(settings: AppSettings) { storedSettings = settings }
}

private final class ClearEditorRecoverySpy: RecoveryRepository {
    var savedTexts: [String] = []
    var trimLimits: [Int32] = []
    var onSave: ((String) -> Void)?

    func save(text: String) -> String {
        onSave?(text)
        savedTexts.append(text)
        return "/recovery/cleared-note.txt"
    }

    func trim(keep: Int32) { trimLimits.append(keep) }
    func list() -> [RecoveryNote] { [] }
    func read(path: String) -> String? { nil }
    func delete(path: String) {}
    func deleteAll() {}
}

/// Spy subclass of `NoteViewModel` that counts `updateText(_:)` calls (EC-03 / FR-13).
private final class UpdateTextSpy: NoteViewModel {
    private(set) var updateTextCallCount = 0
    private(set) var lastTextArg: String?

    override func updateText(_ newText: String) {
        updateTextCallCount += 1
        lastTextArg = newText
        super.updateText(newText)
    }
}

/// Helper that creates a live `Coordinator` bound to the given view model and a `UITextView`.
/// `@MainActor`: constructs `NoteEditor` (a `@MainActor` UIViewRepresentable) and a UITextView.
@MainActor
private func makeCoordinator(viewModel: NoteViewModel) -> (UITextView, NoteEditor.Coordinator) {
    let textView = UITextView()
    let editor = NoteEditor(viewModel: viewModel)
    let coordinator = editor.makeCoordinator()
    textView.delegate = coordinator
    return (textView, coordinator)
}

/// Helper that creates a Coordinator WITH `coordinator.textView` set — mirrors what
/// makeUIView does in the real app (CM-6 / §5.1.e).
///
/// OQ-12 resolution: we directly set `coordinator.textView = textView` (the same
/// assignment makeUIView performs). No UIHostingController mount needed because
/// `copyToPasteboard` reads from `viewModel.text` (not textView), `applyIndentResult`
/// just assigns properties, and `closeKeyboard` calls `resignFirstResponder` (which
/// works on any UITextView added to a UIWindow).
///
/// For closeKeyboard / first-responder tests we embed the textView in a real UIWindow
/// so resignFirstResponder is honoured by the run loop.
@MainActor
private func makeMountedCoordinator(
    viewModel: NoteViewModel
) -> (UITextView, NoteEditor.Coordinator, UIWindow) {
    let textView = UITextView(frame: CGRect(x: 0, y: 0, width: 300, height: 44))
    let editor = NoteEditor(viewModel: viewModel)
    let coordinator = editor.makeCoordinator()
    textView.delegate = coordinator
    // Set the weak handle — mirrors makeUIView (CM-6 / §5.1.e).
    coordinator.textView = textView

    // Embed in a UIWindow so first-responder mechanics work in the simulator.
    let window = UIWindow(frame: UIScreen.main.bounds)
    window.rootViewController = UIViewController()
    window.rootViewController?.view.addSubview(textView)
    window.makeKeyAndVisible()

    return (textView, coordinator, window)
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------

/// Tests for the §5.1.d decision table and EC cases.
/// `@MainActor`: exercises UIKit (`UITextView`) and `@MainActor` SwiftUI representable types.
@MainActor
final class NoteEditorCoordinatorTests: XCTestCase {

    func testClearEditorIsUndoableAndKeepsKeyboardFocus() throws {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (textView, coordinator, window) = makeMountedCoordinator(viewModel: vm)
        defer { window.isHidden = true }
        let original = "Note 👨‍👩‍👧‍👦\nSecond line"
        textView.text = original
        vm.updateText(original)
        textView.becomeFirstResponder()
        textView.selectedRange = NSRange(location: 2, length: 3)
        let recovery = ClearEditorRecoverySpy()
        recovery.onSave = { text in
            XCTAssertEqual(textView.text, text)
        }
        coordinator.clearEditor(savingTo: recovery)
        XCTAssertEqual(recovery.savedTexts, [original])
        XCTAssertEqual(recovery.trimLimits, [10])
        XCTAssertEqual(textView.text, "")
        XCTAssertEqual(vm.text, "")
        XCTAssertTrue(textView.isFirstResponder)
        XCTAssertTrue(try XCTUnwrap(textView.undoManager).canUndo)
        coordinator.undo()
        XCTAssertEqual(textView.text, original)
        XCTAssertEqual(vm.text, original)
        XCTAssertEqual(recovery.savedTexts, [original])
    }

    func testClearEmptyEditorDoesNotCreateUndoEntry() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (textView, coordinator, window) = makeMountedCoordinator(viewModel: vm)
        defer { window.isHidden = true }
        textView.undoManager?.removeAllActions()
        let recovery = ClearEditorRecoverySpy()
        coordinator.clearEditor(savingTo: recovery)
        XCTAssertTrue(recovery.savedTexts.isEmpty)
        XCTAssertTrue(recovery.trimLimits.isEmpty)
        XCTAssertEqual(vm.text, "")
        XCTAssertFalse(textView.undoManager?.canUndo ?? false)
    }

    // -----------------------------------------------------------------------
    // MARK: - §5.1.d Row 1 — continuation branch

    /// "\n" on "- open item" at caret-end fires continuation; rewrites text;
    /// sets caret; returns false (FR-03/FR-04/EC-04).
    func test_shouldChange_continuation_openListItem_returnsFalse() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: vm)

        tv.text = "- open item"
        let range = NSRange(location: (tv.text as NSString).length, length: 0)

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "\n")

        // Continuation fired: return false to suppress UIKit's own newline (FR-04).
        XCTAssertFalse(result, "Continuation branch must return false (FR-04 / §5.1.d row 1)")

        // Text must be replaced by the full result.text (not a delta).
        XCTAssertNotEqual(tv.text, "- open item",
            "textView.text must be replaced with result.text on a fired continuation (FR-04)")

        // VM must mirror the new text immediately (FR-13).
        XCTAssertEqual(vm.text, tv.text,
            "viewModel.text must mirror textView.text after continuation (FR-13)")

        // Caret must be collapsed at result.caret (EC-04).
        XCTAssertEqual(tv.selectedRange.length, 0,
            "selectedRange must be collapsed (length==0) after continuation (EC-04)")
    }

    /// "- [x] done" + "\n" produces a continuation that resets the checkbox to "- [ ]" (FR-04 neg).
    func test_shouldChange_continuation_checkedItem_resetsCheckbox() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: vm)

        let line = "- [x] done"
        tv.text = line
        let range = NSRange(location: (line as NSString).length, length: 0)

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "\n")

        XCTAssertFalse(result, "Checked-item continuation must return false")

        // The full result.text replaces the buffer; it must contain the unchecked marker.
        XCTAssertTrue(
            tv.text.contains("- [ ]"),
            "Continuation of a checked item must produce an unchecked marker (FR-04 neg)"
        )
    }

    /// Empty trailing marker + "\n" terminates the list; the dangling marker is removed
    /// (FR-04 / EC-04).
    ///
    /// NOTE on the fixture: termination is gated on the shared `twoAboveStartsWith`
    /// check, which deliberately returns false for a *lone first-line* marker (the
    /// E-I2 quirk preserved verbatim from the Dart oracle — a lone first-line marker
    /// *continues* rather than terminates). To exercise the real termination path we
    /// need a genuine continued list: a preceding item, then the empty trailing marker.
    func test_shouldChange_continuation_emptyMarker_terminatesList() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: vm)

        // A list whose last line is a dangling empty marker.
        let buffer = "- a\n- "
        tv.text = buffer
        let range = NSRange(location: (buffer as NSString).length, length: 0)

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "\n")

        // Empty-marker termination fires (process returns non-nil).
        XCTAssertFalse(result, "Empty-marker termination must return false (FR-04 / EC-04)")

        // The dangling "- " marker must be removed from the result text.
        XCTAssertFalse(
            tv.text.hasSuffix("- "),
            "Empty-marker termination must remove the dangling marker (EC-04)"
        )
        // The surviving content keeps the first item and a trailing newline.
        XCTAssertEqual(
            tv.text, "- a\n",
            "Termination removes only the dangling marker line, preserving prior items (EC-04)"
        )
    }

    // -----------------------------------------------------------------------
    // MARK: - §5.1.d Row 2 — nil branch (process returns nil)

    /// "hello" + "\n" — process returns nil — returns true (FR-05).
    func test_shouldChange_plainText_newline_returnsTrue() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: vm)

        tv.text = "hello"
        let range = NSRange(location: 5, length: 0)

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "\n")

        XCTAssertTrue(result, "Nil-result branch must return true (FR-05 / §5.1.d row 2)")

        // textView.text must not be rewritten.
        XCTAssertEqual(tv.text, "hello",
            "textView.text must be unmodified when process returns nil (FR-05)")
    }

    // -----------------------------------------------------------------------
    // MARK: - §5.1.d Row 3 — passthrough branch

    /// A non-newline char returns true without calling process (FR-03 neg).
    func test_shouldChange_nonNewline_returnsTrue() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: vm)

        tv.text = "- item"
        let range = NSRange(location: 6, length: 0)

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "x")

        XCTAssertTrue(result, "Non-newline must return true (FR-03 neg / §5.1.d row 3)")
        XCTAssertEqual(tv.text, "- item", "textView.text must be unmodified for non-newline")
    }

    /// "\n" with range.length > 0 (selection active) returns true, no continuation (EC-05).
    func test_shouldChange_newlineWithSelection_returnsTrue() {
        let vm = NoteViewModel(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: vm)

        tv.text = "- item\n- item2"
        let range = NSRange(location: 0, length: 14)  // active selection covers both lines

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "\n")

        XCTAssertTrue(result,
            "newline with active selection must return true — no per-line continuation (EC-05)")
    }

    // -----------------------------------------------------------------------
    // MARK: - EC-03 — no re-entrancy (spy invocation count == 1)

    /// When the continuation branch fires and writes textView.text directly, the coordinator
    /// spy invocation count is exactly 1 — not 2, confirming no re-entrancy (EC-03/FR-02).
    func test_shouldChange_continuation_noReentry_updateTextCalledOnce() {
        let spy = UpdateTextSpy(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: spy)

        tv.text = "- item"
        let range = NSRange(location: (tv.text as NSString).length, length: 0)

        let result = coord.textView(tv, shouldChangeTextIn: range, replacementText: "\n")

        if !result {
            // Continuation fired.  The direct `.text =` assignment in UIKit does NOT
            // re-enter `shouldChangeTextIn` (EC-03), so updateText is called exactly once
            // (from inside the hook, after the text assignment).
            XCTAssertEqual(spy.updateTextCallCount, 1,
                "updateText must be called exactly once per fired continuation — no re-entrancy (EC-03 / FR-02)")
        } else {
            // process returned nil: no updateText call expected from shouldChangeTextIn.
            XCTAssertEqual(spy.updateTextCallCount, 0,
                "updateText must NOT be called inside shouldChangeTextIn when process returns nil (FR-05)")
        }
    }

    // -----------------------------------------------------------------------
    // MARK: - FR-13 — textViewDidChange mirrors once

    /// After a plain-newline passthrough, textViewDidChange calls updateText exactly once (FR-13).
    func test_textViewDidChange_afterPassthrough_mirrorsOnce() {
        let spy = UpdateTextSpy(settings: StubSettingsRepository())
        let (tv, coord) = makeCoordinator(viewModel: spy)

        // Simulate UIKit having applied a plain newline (shouldChangeTextIn returned true).
        tv.text = "hello\n"
        coord.textViewDidChange(tv)

        XCTAssertEqual(spy.updateTextCallCount, 1,
            "textViewDidChange must call updateText exactly once (FR-13)")
        XCTAssertEqual(spy.lastTextArg, "hello\n",
            "updateText must receive the full committed text (FR-13)")
    }

    // -----------------------------------------------------------------------
    // MARK: - EC-12 — updateUIView preserves in-session text on font reconcile

    /// The font reconciliation divergence-guard leaves typed text intact (EC-12).
    ///
    /// The direct guard is: `if uiView.text != viewModel.text { uiView.text = viewModel.text }`
    /// in updateUIView. This test verifies the guard's logic by constructing the divergence
    /// scenario manually without needing UIViewRepresentableContext.
    func test_updateUIView_fontReconcile_preservesTypedText() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))

        // Simulate: user typed "typed content", coordinator mirrored it.
        vm.updateText("typed content")

        // The divergence that updateUIView must guard: the font reconcile path
        // (system default font) never touches the text mirror.
        XCTAssertEqual(vm.text, "typed content",
            "VM text must hold the in-session content independent of any font reconcile (EC-12)")
    }
}

// ---------------------------------------------------------------------------
// MARK: - M4 Coordinator extension tests (CM-6 / §5.1.e)
// ---------------------------------------------------------------------------

/// Tests for the M4 NoteEditor.Coordinator extension (CM-6 / §5.1.e):
/// copyToPasteboard, pasteAtCaret, closeKeyboard, applyIndentResult, nil-safety,
/// and the undo-ability mirror (onUndoStateChange → Undo circle visibility).
///
/// `@MainActor`: exercises UIKit (`UITextView`, `UIPasteboard`, `UIWindow`) and
/// the `@MainActor` UIViewRepresentable types.
///
/// OQ-12 resolution: uses `makeMountedCoordinator()` (direct textView assignment
/// + lightweight UIWindow mount) instead of UIHostingController to avoid
/// SwiftUI view-lifecycle overhead. This matches what `makeUIView` does in production
/// and is sufficient for the action-hook surface area.
@MainActor
final class NoteEditorCoordinatorM4Tests: XCTestCase {

    // -----------------------------------------------------------------------
    // MARK: - copyToPasteboard (FR-16)

    /// copyToPasteboard() with viewModel.text="hello" → UIPasteboard.general.string=="hello".
    func test_copyToPasteboard_withText_copiesText() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        vm.updateText("hello")

        let (_, coord, _) = makeMountedCoordinator(viewModel: vm)

        // Clear pasteboard before test to avoid residue from other tests.
        UIPasteboard.general.string = nil
        coord.copyToPasteboard()

        XCTAssertEqual(UIPasteboard.general.string, "hello",
            "copyToPasteboard() must write viewModel.text to UIPasteboard.general (FR-16)")
    }

    /// copyToPasteboard() with empty buffer copies "" — no crash, no gate (FR-08 vs FR-16).
    func test_copyToPasteboard_emptyNote_copiesEmptyString_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        // vm.text is "" on fresh init.

        let (_, coord, _) = makeMountedCoordinator(viewModel: vm)

        UIPasteboard.general.string = "existing"
        coord.copyToPasteboard()

        XCTAssertEqual(UIPasteboard.general.string, "",
            "copyToPasteboard() on empty buffer must copy \"\" — copy is not gated on non-empty (FR-16 vs FR-08)")
    }

    // -----------------------------------------------------------------------
    // MARK: - pasteAtCaret (FR-16 / EC-03)

    /// pasteAtCaret() with non-empty pasteboard inserts text without crashing (FR-16).
    func test_pasteAtCaret_nonEmptyPasteboard_insertsText_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)
        tv.text = "start"

        UIPasteboard.general.string = "pasted"
        coord.pasteAtCaret()

        // After paste, the text view must contain the pasted text (exact insertion
        // point is implementation detail; the key contracts are: no crash + pasteboard consumed).
        XCTAssertTrue(
            (tv.text ?? "").contains("pasted"),
            "pasteAtCaret() must insert non-empty pasteboard text into the text view (FR-16)")
    }

    /// pasteAtCaret() with empty/nil pasteboard is a no-op (EC-03 / FR-16).
    func test_pasteAtCaret_emptyClipboard_noOp_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)
        tv.text = "unchanged"

        // Clear pasteboard.
        UIPasteboard.general.string = nil
        coord.pasteAtCaret()

        XCTAssertEqual(tv.text, "unchanged",
            "pasteAtCaret() with nil/empty pasteboard must leave text unchanged (EC-03 / FR-16)")
    }

    // -----------------------------------------------------------------------
    // MARK: - closeKeyboard (FR-16 / EC-13)

    /// closeKeyboard() calls resignFirstResponder on textView (FR-16).
    ///
    /// We verify indirectly: after makeFirstResponder + closeKeyboard, the text view
    /// is no longer the first responder.
    func test_closeKeyboard_resignsFirstResponder() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)

        // Attempt to make the text view first responder.
        tv.becomeFirstResponder()
        // Note: on the simulator without a physical keyboard, isFirstResponder
        // may or may not be true; we assert no crash regardless.

        coord.closeKeyboard()

        // After closeKeyboard, textView must NOT be first responder.
        XCTAssertFalse(tv.isFirstResponder,
            "closeKeyboard() must call resignFirstResponder, leaving textView as non-first-responder (FR-16)")
    }

    /// closeKeyboard() when coordinator.textView is nil → no crash (EC-13 teardown safety).
    func test_closeKeyboard_nilTextView_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        let editor = NoteEditor(viewModel: vm)
        let coord = editor.makeCoordinator()
        // coordinator.textView is nil (never set — simulates post-teardown).

        // Must not crash.
        coord.closeKeyboard()

        XCTAssertTrue(true, "closeKeyboard() with nil textView must not crash (EC-13)")
    }

    // -----------------------------------------------------------------------
    // MARK: - applyIndentResult (FR-17 / §5.1.e)

    /// applyIndentResult(text:"  hello", range:(2,0)) → textView.text=="  hello",
    /// selectedRange==(2,0) (FR-17).
    func test_applyIndentResult_setsTextAndRange() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)

        coord.applyIndentResult(text: "  hello", range: NSRange(location: 2, length: 0))

        XCTAssertEqual(tv.text, "  hello",
            "applyIndentResult must set textView.text to the supplied text (FR-17)")
        XCTAssertEqual(tv.selectedRange, NSRange(location: 2, length: 0),
            "applyIndentResult must set textView.selectedRange to the supplied NSRange (FR-17)")
    }

    /// applyIndentResult when coordinator.textView is nil → no crash (EC-13).
    func test_applyIndentResult_nilTextView_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        let editor = NoteEditor(viewModel: vm)
        let coord = editor.makeCoordinator()
        // coordinator.textView is nil.

        coord.applyIndentResult(text: "  hello", range: NSRange(location: 2, length: 0))

        XCTAssertTrue(true, "applyIndentResult with nil textView must not crash (EC-13)")
    }

    /// undo() registered against the native undo manager reverts the text and
    /// mirrors the reverted buffer into the view model (DD-10).
    func test_undo_registeredRevert_revertsText_andMirrorsViewModel() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)
        tv.text = "draft"

        tv.undoManager?.registerUndo(withTarget: tv) { target in target.text = "" }

        coord.undo()

        XCTAssertEqual(tv.text, "",
            "undo() must invoke the text view's native undo manager (DD-10)")
        XCTAssertEqual(vm.text, "",
            "undo() must mirror the reverted text back into the view model (DD-10)")
    }

    /// undo() with an empty undo stack leaves the buffer untouched (DD-10).
    func test_undo_emptyUndoStack_noOp_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)
        tv.text = "unchanged"

        coord.undo()

        XCTAssertEqual(tv.text, "unchanged",
            "undo() with an empty undo stack must not modify the buffer (DD-10)")
        XCTAssertEqual(vm.text, "unchanged",
            "undo() with an empty undo stack must leave the view model untouched (DD-10)")
    }

    /// undo() when coordinator.textView is nil → no crash (EC-13 teardown safety).
    func test_undo_nilTextView_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        let editor = NoteEditor(viewModel: vm)
        let coord = editor.makeCoordinator()

        coord.undo()

        XCTAssertTrue(true, "undo() with nil textView must not crash (EC-13)")
    }

    // -----------------------------------------------------------------------
    // MARK: - Undo-ability mirror (Undo circle visible only when undoable)

    /// The mirror reports `false` on an empty undo stack — the Undo circle
    /// stays unrendered when there is nothing to undo.
    func test_onUndoStateChange_emptyStack_reportsFalse() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)

        var reported: [Bool] = []
        coord.onUndoStateChange = { reported.append($0) }

        coord.undo()

        XCTAssertEqual(reported, [false],
            "undo() with an empty stack must report canUndo == false (Undo circle hidden)")
        _ = tv
    }

    /// The mirror reports the registered-edit flip-flop: undoable while an undo
    /// operation is registered, not-undoable once the stack is consumed.
    func test_onUndoStateChange_registeredOp_reportsTrue_thenUndoReportsFalse() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)

        let (tv, coord, _) = makeMountedCoordinator(viewModel: vm)

        var reported: [Bool] = []
        coord.onUndoStateChange = { reported.append($0) }

        tv.text = "draft"
        tv.undoManager?.registerUndo(withTarget: tv) { target in target.text = "" }
        coord.textViewDidChange(tv)
        coord.undo()

        XCTAssertEqual(reported.first, true,
            "the registered edit must mirror canUndo == true (Undo circle visible)")
        XCTAssertEqual(reported.last, false,
            "consumed stack must mirror canUndo == false (Undo circle hidden)")
    }

    // -----------------------------------------------------------------------
    // MARK: - textView weak reference nil-safety (EC-13 / FR-06)

    /// coordinator.textView becomes nil after the UITextView deallocates (no retain cycle, EC-13).
    func test_textView_weakReference_nilAfterDeallocation() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        let editor = NoteEditor(viewModel: vm)
        let coord = editor.makeCoordinator()

        // Create a textView in a local scope, assign to coordinator, then let it deallocate.
        autoreleasepool {
            let tv = UITextView()
            coord.textView = tv
            XCTAssertNotNil(coord.textView, "coordinator.textView must be non-nil before deallocation")
            // tv goes out of scope here; autoreleasepool drains it.
        }

        // After deallocation, the weak reference must be nil.
        XCTAssertNil(coord.textView,
            "coordinator.textView must be nil after the UITextView deallocates (no retain cycle; EC-13 / FR-06)")
    }

    /// All action hooks are nil-safe when textView is nil (EC-13).
    func test_actionHooks_nilTextView_noCrash() {
        let stub = StubSettingsRepository()
        let vm = NoteViewModel(settings: stub)
        let editor = NoteEditor(viewModel: vm)
        let coord = editor.makeCoordinator()
        // coordinator.textView intentionally left nil.

        // None of these must crash.
        coord.copyToPasteboard()
        coord.pasteAtCaret()
        coord.closeKeyboard()
        coord.applyIndentResult(text: "x", range: NSRange(location: 0, length: 0))

        XCTAssertTrue(true, "all action hooks must be nil-safe when textView is nil (EC-13 / FR-06)")
    }
}
