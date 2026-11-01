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
        let recent = app.buttons["Recent notes"]
        XCTAssertTrue(recent.waitForExistence(timeout: 5), app.debugDescription)
        capture(app, name: "menu-closed-keyboard")
        recent.tap()
        capture(app, name: "recent-submenu-closed-keyboard")
        app.terminate()
        app.launch()
        let editor = app.textViews.firstMatch
        XCTAssertTrue(editor.waitForExistence(timeout: 10))
        editor.tap()
        editor.typeText("Menu visual verification\nSecond line")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        capture(app, name: "pill-keyboard")
        app.buttons["Menu"].tap()
        XCTAssertTrue(recent.waitForExistence(timeout: 5), app.debugDescription)
        capture(app, name: "menu-keyboard")
        app.buttons["About"].tap()
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
