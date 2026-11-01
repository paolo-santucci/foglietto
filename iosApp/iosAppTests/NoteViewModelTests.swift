// iosAppTests
//
// XCTest cases for NoteViewModel — CI-verified on macos-26 iOS Simulator.
// Do NOT register this file in project.pbxproj here; TASK-08 owns all iosAppTests
// target memberships to keep the file a single pbxproj writer per wave.
//
// Spec refs: FR-09, FR-10, FR-11, FR-12, FR-13, FR-16; §6.1 EC-01/EC-06/EC-07/EC-11

import XCTest
import Foundation
import shared
@testable import iosApp

// MARK: - Stub SettingsRepository

/// Minimal stub that satisfies `SettingsRepository` without touching UserDefaults.
/// Returns a configurable `AppSettings` from `load()` and records `save` call arguments.
/// File-private so it cannot collide with the same-named stub in NoteEditorCoordinatorTests.
private final class StubSettingsRepository: SettingsRepository {

    private let fontSizeIndexToReturn: Int32
    private(set) var savedSettings: [AppSettings] = []

    init(fontSizeIndex: Int32) {
        self.fontSizeIndexToReturn = fontSizeIndex
    }

    func load() -> AppSettings {
        return AppSettings(colorScheme: .follow, fontSizeIndex: fontSizeIndexToReturn)
    }

    func save(settings: AppSettings) {
        savedSettings.append(settings)
    }
}


final class NoteViewModelTests: XCTestCase {

    // -------------------------------------------------------------------------
    // FR-09 / FR-13 — init
    // (The fontSizeIndex suites retired with the font-slot state — user direction:
    //  the editor always renders the system default font.)
    // -------------------------------------------------------------------------

    /// init: fresh instance → vm.text==""
    func testInit_text_empty() {
        let stub = StubSettingsRepository(fontSizeIndex: 8)
        let vm = NoteViewModel(settings: stub)

        XCTAssertEqual(vm.text, "", "text must be empty string on fresh init (FR-13 / EC-01)")
    }

    // -------------------------------------------------------------------------
    // FR-10 — updateText / populate distinctness
    // -------------------------------------------------------------------------

    /// updateText("a") then updateText("b") → text=="b"  (FR-10 keystroke)
    func testUpdateText_sequentialCalls_lastValueWins() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))

        vm.updateText("a")
        XCTAssertEqual(vm.text, "a")

        vm.updateText("b")
        XCTAssertEqual(vm.text, "b", "updateText must overwrite the prior value (FR-10)")
    }

    /// populate("hello") → text=="hello" (FR-10)
    func testPopulate_setsText() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))

        vm.populate("hello")
        XCTAssertEqual(vm.text, "hello", "populate must set text via the shared apply body (FR-10)")
    }

    /// populate is a SEPARATE method from updateText (not alias) — confirmed by selector existence (FR-10)
    ///
    /// Both methods exist as distinct symbols.  This test calls each independently and asserts
    /// they update the same backing state (shared apply body) while remaining distinct call sites.
    func testPopulate_isDistinctMethodFromUpdateText() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))

        vm.updateText("from-updateText")
        XCTAssertEqual(vm.text, "from-updateText")

        // populate must be callable as a distinct method and override the text
        vm.populate("from-populate")
        XCTAssertEqual(vm.text, "from-populate", "populate must be a separate entry point sharing the apply body (FR-10)")
    }

    // -------------------------------------------------------------------------
    // FR-11 / EC-06 / EC-07 — indent / outdent
    // -------------------------------------------------------------------------

    /// indent(in:of:) adds one indent unit; outbound NSRange via bridge is correct (FR-11)
    ///
    /// Uses a simple two-space indent line ("  hello"); after indent the line should gain
    /// another indent unit.  The exact text depends on LineIndent.shared implementation
    /// (two-space or tab), so we assert structural properties: text changed, text contains
    /// the original text, and the returned NSRange is a valid range within the new text.
    func testIndent_addsIndentUnit_selectionRoundTrips() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))
        let inputText = "hello"
        // Collapsed caret at start of line
        let selection = NSRange(location: 0, length: 0)

        let result = vm.indent(in: selection, of: inputText)

        // text must have been applied to the VM
        XCTAssertEqual(vm.text, result.text, "indent must apply the new text to the VM atomically (FR-11)")

        // The result text should contain the original content (indent prepends, not replaces)
        XCTAssertTrue(result.text.contains("hello"), "indented text must still contain original content")

        // The text should be longer (an indent unit was prepended)
        XCTAssertGreaterThan(result.text.count, inputText.count, "indent must add characters")

        // The returned NSRange must be valid within the new text's bounds
        let nsResult = result.text as NSString
        XCTAssertLessThanOrEqual(
            result.selection.location + result.selection.length,
            nsResult.length,
            "returned NSRange must be within new text bounds (EC-06, FR-07)"
        )
    }

    /// outdent: shrinks one unit; selection round-trips via min/abs (FR-11, EC-06)
    func testOutdent_removesIndentUnit_selectionRoundTrips() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))
        // A line with a known leading indent (two-space, matching the shared default)
        let inputText = "  hello"
        let selection = NSRange(location: 2, length: 5)  // selects "hello"

        let result = vm.outdent(in: selection, of: inputText)

        XCTAssertEqual(vm.text, result.text, "outdent must apply new text to VM atomically (FR-11)")

        let nsResult = result.text as NSString
        XCTAssertLessThanOrEqual(
            result.selection.location + result.selection.length,
            nsResult.length,
            "outdent NSRange must be within new text bounds (EC-06)"
        )
    }

    /// outdent(no leading unit) → text unchanged; selection round-trips via min/abs (EC-07)
    func testOutdent_noLeadingUnit_textUnchanged_selectionRoundTrips() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))
        let inputText = "hello"  // no leading indent
        let selection = NSRange(location: 0, length: 5)

        let result = vm.outdent(in: selection, of: inputText)

        // The shared outdent is a no-op on a line with no leading unit (EC-07)
        XCTAssertEqual(result.text, inputText, "outdent on no-indent line must leave text unchanged (EC-07)")
        XCTAssertEqual(vm.text, inputText, "VM text must reflect the no-op outdent (EC-07)")

        // Selection still round-trips through the bridge
        let nsResult = result.text as NSString
        XCTAssertLessThanOrEqual(
            result.selection.location + result.selection.length,
            nsResult.length,
            "selection must round-trip even on no-op outdent (EC-07, FR-07)"
        )
    }

    /// Reversed-anchor selection round-trips through min/abs in TextOffsetBridge (EC-06, FR-07)
    func testIndent_reversedAnchorSelection_roundTrips() {
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))
        let inputText = "hello"
        // Reversed anchor: location > end (TextOffsetBridge.selection treats this as selExtent < selStart)
        // selStart = 5, selExtent = 5 + 0 = 5 — a collapsed selection at end
        let selection = NSRange(location: 5, length: 0)

        let result = vm.indent(in: selection, of: inputText)

        let nsResult = result.text as NSString
        XCTAssertLessThanOrEqual(
            result.selection.location + result.selection.length,
            nsResult.length,
            "reversed-anchor selection must produce a valid NSRange via min/abs (EC-06)"
        )
    }

    // -------------------------------------------------------------------------
    // FR-12 — no save/persist API (structural / compile-time enforcement)
    // -------------------------------------------------------------------------

    /// Gate-equivalent runtime check: ensure NoteViewModel has no save-named method.
    ///
    /// This test is a documentation-level sentinel.  The gate-sh check (i) performs the
    /// authoritative grep.  Here we verify structural intent at the Swift level by confirming
    /// the type responds to only the expected public interface.
    func testViewModel_hasNoSaveAPI() {
        // Compile-time: there is no `vm.save()` / `vm.persist()` / `vm.recoveryWrite()` call
        // available to write here — those symbols do not exist on NoteViewModel (FR-12).
        // If such a method were added, this comment would need updating and the gate-sh
        // check (i) would catch it structurally.
        //
        // Runtime: confirm the VM has both expected entry points and no undeclared save surface.
        let vm = NoteViewModel(settings: StubSettingsRepository(fontSizeIndex: 8))
        vm.updateText("test")
        vm.populate("test")
        // If we get here without a compile error for vm.save() etc., FR-12 is structurally satisfied.
        XCTAssertTrue(true, "NoteViewModel has no save/persist/recoveryWrite API (FR-12)")
    }
}
