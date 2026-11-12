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
        // Recents are top-level rows now: no "Recent notes" submenu entry.
        XCTAssertFalse(app.buttons["Recent notes"].exists, app.debugDescription)
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
        XCTAssertFalse(app.buttons["Recent notes"].exists, app.debugDescription)
        capture(app, name: "menu-keyboard")
        about.tap()
        XCTAssertTrue(app.buttons["Website"].waitForExistence(timeout: 5), app.debugDescription)
        capture(app, name: "about-submenu-keyboard")
    }

    private func capture(_ app: XCUIApplication, name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
