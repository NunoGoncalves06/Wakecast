import XCTest
@testable import Wakecast

final class WakecastTests: XCTestCase {
    func testAppVersionIsSet() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
        XCTAssertNotNil(version)
    }
}
