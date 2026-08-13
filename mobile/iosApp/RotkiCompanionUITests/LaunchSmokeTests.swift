import XCTest

final class LaunchSmokeTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testLaunchesAtUnpairedScreen() {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.buttons["pairing.start"].waitForExistence(timeout: 10))
    }

    func testFourTabShellLaunchSeam() {
        let app = XCUIApplication()
        app.launchArguments.append("--rotki-ui-tabs")
        app.launch()

        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 10))
        XCTAssertTrue(tabBar.buttons["Overview"].exists)
        XCTAssertTrue(tabBar.buttons["Portfolio"].exists)
        XCTAssertTrue(tabBar.buttons["History"].exists)
        XCTAssertTrue(tabBar.buttons["Sources"].exists)
    }
}
