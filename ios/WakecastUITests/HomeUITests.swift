import XCTest

final class HomeUITests: XCTestCase {
    func testHomeShowsRunningOrder() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.navigationBars["Wakecast"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Weather"].exists)
        XCTAssertTrue(app.staticTexts["News"].exists)
    }
}
