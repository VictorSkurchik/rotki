import RotkiShared
import XCTest

final class ExactDecimalInteropTests: XCTestCase {
    func testExactDecimalRemainsExportedByUmbrellaFramework() {
        let decimal = ExactDecimal.companion.parse(value: "-123.4500")

        XCTAssertEqual(decimal.toDisplayString(fractionDigits: 2), "-123.45")
        XCTAssertEqual(decimal.canonicalScale, 2)
        XCTAssertFalse(decimal.isZero)
    }
}
