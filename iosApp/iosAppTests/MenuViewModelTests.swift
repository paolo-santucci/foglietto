// MenuViewModelTests.swift
// iosAppTests
//
// XCTest coverage for MenuViewModel — fresh RecoveryListViewModel creation (FR-12).
// (The theme-selection and font-stepping suites retired with the pickers — user
// direction: the app always follows the system theme and the system default font.)
//
// Stubs are FILE-PRIVATE to this file to avoid duplicate-symbol CI failures
// (M3 lesson: StubSettingsRepository declared internal across two files → CI red).
//
// M3 CI conventions (MANDATORY):
//   @MainActor at class level — MenuViewModel is composed of @Observable deps.
//   File-private stubs — never hit real NSUserDefaults/disk.
//   No async/await, no Task, no wall-clock sleeps.
//   import shared + @testable import iosApp.
//
// Spec refs: §7.1 Layer B (MenuViewModel section); §7.2 integration
//   (MenuViewModel + SettingsRepository round-trip); EC-10;
//   OQ-11 note: select(path:) calls read synchronously (non-suspend, FR-24) so
//   no async harness is required.
// CI-verified on macos-26 iOS Simulator — not locally compilable (no macOS host).

import XCTest
import UIKit
import shared
@testable import iosApp

// ---------------------------------------------------------------------------
// MARK: - File-private stubs
// ---------------------------------------------------------------------------

/// Minimal settings stub: the NoteViewModel dependency only (no font slot
/// read anymore — the settings construction contract is retained for the
/// composition root; the VM never reads it).
private final class StubSettingsRepository: SettingsRepository {
    var storedSettings: AppSettings

    init(fontSizeIndex: Int32 = 8, colorScheme: AppColorScheme = AppColorScheme.follow) {
        storedSettings = AppSettings(colorScheme: colorScheme, fontSizeIndex: fontSizeIndex)
    }

    private(set) var saveCallCount: Int = 0
    private(set) var savedSettings: [AppSettings] = []

    func load() -> AppSettings { storedSettings }

    func save(settings: AppSettings) {
        saveCallCount += 1
        storedSettings = settings
        savedSettings.append(settings)
    }
}

/// Minimal recovery stub: returns an empty list and nil for all reads.
/// Used as an inert dependency for MenuViewModel (recovery paths tested separately).
private final class StubRecoveryRepository: RecoveryRepository {
    func list() -> [RecoveryNote] { [] }
    func read(path: String) -> String? { nil }
    func save(text: String) -> String { "" }
    func delete(path: String) {}
    func deleteAll() {}
    func trim(keep: Int32) {}
}

// ---------------------------------------------------------------------------
// MARK: - MenuViewModelTests
// ---------------------------------------------------------------------------

/// Tests for MenuViewModel — recovery VM factory.
///
/// `@MainActor`: composes @Observable types.
@MainActor
final class MenuViewModelTests: XCTestCase {

    // MARK: - Helpers

    /// Builds a MenuViewModel with file-private stubs.
    private func makeVM() -> MenuViewModel {
        let recovery = StubRecoveryRepository()
        let noteVM = NoteViewModel(settings: StubSettingsRepository())
        return MenuViewModel(recovery: recovery, viewModel: noteVM)
    }

    // -----------------------------------------------------------------------
    // MARK: - makeRecoveryListViewModel — fresh instance per call (FR-12)

    /// makeRecoveryListViewModel() returns a distinct instance each call.
    func test_makeRecoveryListViewModel_returnsDistinctInstance() {
        let vm = makeVM()

        let first = vm.makeRecoveryListViewModel()
        let second = vm.makeRecoveryListViewModel()

        XCTAssertFalse(first === second,
            "makeRecoveryListViewModel() must return a DISTINCT instance on each call (FR-12 fresh-per-expand)")
    }
}

@MainActor
final class ChromeBarCoordinatorTests: XCTestCase {
    func testPopoverTrailingEdgeMovesWithPill() {
        let bounds = CGRect(x: 0, y: 0, width: 390, height: 844)
        let minimum = UIEdgeInsets(top: 0, left: 16, bottom: 16, right: 0)
        let closed = PillPopoverAlignment.margins(
            pillFrame: CGRect(x: 274, y: 59, width: 100, height: 44), bounds: bounds, minimum: minimum
        )
        let editing = PillPopoverAlignment.margins(
            pillFrame: CGRect(x: 214, y: 59, width: 100, height: 44), bounds: bounds, minimum: minimum
        )
        XCTAssertEqual(closed.right, 16)
        XCTAssertEqual(editing.right, 76)
        XCTAssertEqual(editing.top, 59)
        XCTAssertEqual(editing.left, closed.left)
    }

    func testMissingPillFrameKeepsSafeMargins() {
        let minimum = UIEdgeInsets(top: 12, left: 16, bottom: 16, right: 12)
        XCTAssertEqual(PillPopoverAlignment.margins(pillFrame: .null, bounds: .zero, minimum: minimum), minimum)
    }

    private func makeMenu() -> MenuViewModel {
        MenuViewModel(
            recovery: StubRecoveryRepository(),
            viewModel: NoteViewModel(settings: StubSettingsRepository())
        )
    }

    func testShareTracksWhitespaceAndEditedNote() throws {
        let root = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        let coordinator = ChromeBarCoordinator()
        coordinator.attach(to: navigation)
        let menu = makeMenu()
        coordinator.update(text: " \n\t", menuVM: menu, canUndo: false, onUndo: {})
        let share = try XCTUnwrap(root.navigationItem.trailingItemGroups.last?.barButtonItems.first)
        XCTAssertFalse(share.isEnabled)
        coordinator.update(text: "Note", menuVM: menu, canUndo: true, onUndo: {})
        XCTAssertTrue(share.isEnabled)
        coordinator.update(text: "", menuVM: menu, canUndo: false, onUndo: {})
        XCTAssertFalse(share.isEnabled)
    }

    func testUndoChangesPreservePresentationSourceItems() throws {
        let root = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        let coordinator = ChromeBarCoordinator()
        coordinator.attach(to: navigation)
        let menu = makeMenu()
        coordinator.update(text: "Note", menuVM: menu, canUndo: false, onUndo: {})
        let originalGroup = try XCTUnwrap(root.navigationItem.trailingItemGroups.last)
        let originalItems = originalGroup.barButtonItems
        XCTAssertEqual(originalItems.count, 2)
        coordinator.update(text: "Edited", menuVM: menu, canUndo: true, onUndo: {})
        XCTAssertEqual(root.navigationItem.trailingItemGroups.count, 2)
        XCTAssertTrue(root.navigationItem.trailingItemGroups.last === originalGroup)
        coordinator.update(text: "Note", menuVM: menu, canUndo: false, onUndo: {})
        XCTAssertEqual(root.navigationItem.trailingItemGroups.count, 1)
        XCTAssertTrue(root.navigationItem.trailingItemGroups[0].barButtonItems[0] === originalItems[0])
        XCTAssertTrue(root.navigationItem.trailingItemGroups[0].barButtonItems[1] === originalItems[1])
    }

    func testMenuRemainsPopoverInCompactWidth() {
        let coordinator = ChromeBarCoordinator()
        let presentation = UIPresentationController(
            presentedViewController: UIViewController(), presenting: UIViewController()
        )
        XCTAssertEqual(coordinator.adaptivePresentationStyle(
            for: presentation, traitCollection: UITraitCollection(horizontalSizeClass: .compact)
        ), .none)
    }

    func testKeyboardDismissalHasSeparateProminentGroupAndPreservesMenuAnchor() throws {
        let root = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        let coordinator = ChromeBarCoordinator()
        coordinator.attach(to: navigation)
        let menuGroup = try XCTUnwrap(root.navigationItem.trailingItemGroups.first)

        coordinator.updateKeyboard(isPresented: true, onClose: {})
        let groups = root.navigationItem.trailingItemGroups
        XCTAssertEqual(groups.count, 2)
        XCTAssertTrue(groups[0] === menuGroup)
        let dismiss = try XCTUnwrap(groups.last?.barButtonItems.first)
        XCTAssertEqual(dismiss.style, .prominent)
        XCTAssertEqual(dismiss.accessibilityLabel, String(localized: "Hide keyboard"))
        XCTAssertNotNil(dismiss.tintColor)

        coordinator.update(text: "Edited", menuVM: makeMenu(), canUndo: true, onUndo: {})
        XCTAssertEqual(root.navigationItem.trailingItemGroups.count, 3)
        XCTAssertTrue(root.navigationItem.trailingItemGroups.last?.barButtonItems.first === dismiss)
        coordinator.updateKeyboard(isPresented: false, onClose: {})
        XCTAssertEqual(root.navigationItem.trailingItemGroups.count, 2)
        XCTAssertTrue(root.navigationItem.trailingItemGroups.last === menuGroup)
    }

    func testNativeMenuSurvivesKeyboardAndUndoUpdates() throws {
        let root = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        let coordinator = ChromeBarCoordinator()
        coordinator.attach(to: navigation)
        let model = makeMenu()
        coordinator.update(text: "Note", menuVM: model, canUndo: false, onUndo: {})
        let item = try XCTUnwrap(root.navigationItem.trailingItemGroups.first?.barButtonItems.last)
        let menu = try XCTUnwrap(item.menu)
        for editing in [true, false, true] {
            coordinator.updateKeyboard(isPresented: editing, onClose: {})
            coordinator.update(text: "Edited", menuVM: model, canUndo: editing, onUndo: {})
            XCTAssertTrue(item.menu === menu)
            XCTAssertNil(item.primaryAction)
        }
    }

    func testAboutComesFirstAndTheRecentNotesHeaderIsInert() throws {
        let menu = NativeEditorMenu.make(menuVM: makeMenu())
        XCTAssertEqual(menu.children.count, 3)

        // About is pinned by being the first row: UIKit opens a menu at its first entry.
        let about = try XCTUnwrap(menu.children.first as? UIMenu)
        XCTAssertEqual(about.title, String(localized: "About"))
        XCTAssertFalse(about.options.contains(.displayInline))
        XCTAssertNotNil(about.image)
        XCTAssertEqual(about.children.count, 3)

        // The header keeps the old submenu row's glyph + title but must not be actionable.
        let header = try XCTUnwrap(menu.children.dropFirst().first as? UIAction)
        XCTAssertEqual(header.title, String(localized: "Recent notes"))
        XCTAssertTrue(header.attributes.contains(.disabled))
        XCTAssertNotNil(header.image)

        // The recents are the menu's own rows, resolved on each open.
        XCTAssertTrue(menu.children.last is UIDeferredMenuElement)
    }

    func testEmptyRecoveryRemainsADisabledMenuRow() throws {
        let rows = NativeEditorMenu.recoveryActions(menuVM: makeMenu())
        XCTAssertEqual(rows.count, 1)
        let empty = try XCTUnwrap(rows.first as? UIAction)
        XCTAssertEqual(empty.title, String(localized: "No recent notes."))
        XCTAssertTrue(empty.attributes.contains(.disabled))
    }
}

final class MenuLocalizationTests: XCTestCase {
    private let locales = "en it af ar bg bn ca cs da de el es et eu fa fi fil fr gl he hi hr hu id is ja ko lt lv mk ms nb nl pl pt-BR pt-PT ro ru sk sl sq sr sv sw ta te th tr uk ur vi zh-Hans zh-Hant".split(separator: " ").map(String.init)
    private let keys = [
        "About", "Undo", "Copy", "Copy text", "Hide keyboard", "Indent", "Menu",
        "No recent notes.", "Open menu", "Outdent", "Paste", "Paste text", "Recent notes",
        "Report an issue", "Restore note: %@, saved %@", "Restore this note", "Share", "Share text", "Website"
    ]

    func testEverySupportedLocaleShipsEveryInterfaceString() throws {
        for locale in locales {
            XCTAssertTrue(Bundle.main.localizations.contains(locale), "Missing locale: \(locale)")
            let path = try XCTUnwrap(Bundle.main.path(forResource: locale, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: "MISSING_TRANSLATION", table: nil)
                XCTAssertNotEqual(value, "MISSING_TRANSLATION", "\(locale): \(key)")
                XCTAssertFalse(value.isEmpty, "\(locale): \(key)")
                let normalized = value.replacingOccurrences(of: #"%\d+\$@"#, with: "%@", options: .regularExpression)
                XCTAssertEqual(normalized.components(separatedBy: "%@").count,
                               key.components(separatedBy: "%@").count, "\(locale): \(key)")
            }
        }
    }

    func testUnsupportedLanguageFallsBackToEnglish() throws {
        XCTAssertEqual(Bundle.main.developmentLocalization, "en")
        XCTAssertEqual(Bundle.preferredLocalizations(from: locales, forPreferences: ["zz-ZZ"]).first, "en")
        let path = try XCTUnwrap(Bundle.main.path(forResource: "en", ofType: "lproj"))
        let english = try XCTUnwrap(Bundle(path: path))
        for key in keys {
            XCTAssertEqual(english.localizedString(forKey: key, value: nil, table: nil), key)
        }
    }
}
