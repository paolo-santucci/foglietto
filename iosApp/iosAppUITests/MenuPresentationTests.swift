import XCTest

@MainActor
final class MenuPresentationTests: XCTestCase {
    func testNativeMenusWithAndWithoutKeyboard() {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let menu = app.buttons["Menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        capture(app, name: "pill-closed")
        menu.tap()
        let about = app.buttons["About"]
        XCTAssertTrue(about.waitForExistence(timeout: 5), app.debugDescription)
        assertMenuReadingOrder(in: app, about: about)
        capture(app, name: "menu-closed-keyboard")
        app.terminate()
        app.launch()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 10))
        editor.tap()
        editor.typeText("Menu visual verification\nSecond line")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        capture(app, name: "pill-keyboard")
        app.buttons["Menu"].tap()
        XCTAssertTrue(about.waitForExistence(timeout: 5), app.debugDescription)
        assertMenuReadingOrder(in: app, about: about)
        capture(app, name: "menu-keyboard")
        about.tap()
        XCTAssertTrue(app.buttons["Website"].waitForExistence(timeout: 5), app.debugDescription)
        capture(app, name: "about-submenu-keyboard")
    }

    /// Restoring must reveal the note from its first line, keyed off the menu dismissal — the
    /// bug this guards: the restore anchored to a not-yet-settled safe-area inset and the first
    /// lines stayed clipped above the top edge until the keyboard rebuilt the viewport.
    func testRestoredNoteOpensAtTheTopOfTheEditor() {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 10))
        editor.tap()
        editor.typeText("Restored from the menu\nSecond line")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))

        // Backgrounding saves the buffer into recovery; reactivating clears the buffer.
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(editor.waitForExistence(timeout: 10))

        let menu = app.buttons["Menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        menu.tap()
        let row = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH %@", "Restore note"))
            .firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), app.debugDescription)
        row.tap()

        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertTrue(
            (editor.value as? String ?? "").contains("Restored from the menu"),
            app.debugDescription
        )
        capture(app, name: "restored-note-no-keyboard")
    }

    /// About is the menu's first row (which is how it stays visible), then the inert
    /// "Recent notes" header, then the rows.
    private func assertMenuReadingOrder(in app: XCUIApplication, about: XCUIElement) {
        let header = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", "Recent notes"))
            .firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(header.isEnabled, "The recent-notes header must be inert: \(app.debugDescription)")
        XCTAssertLessThan(about.frame.minY, header.frame.minY, "About must sit above the notes")
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
