import XCTest
@testable import RotkiCompanion

final class CompanionAppModelTests: XCTestCase {
    @MainActor
    func testSharedFacadeStartsAtUnpairedPresentation() {
        let model = CompanionAppModel()

        XCTAssertEqual(model.sharedRootStateCode, "unpaired")
        XCTAssertEqual(model.sharedPairingStateCode, "intro")
        XCTAssertEqual(model.route, .unpaired)
    }

    @MainActor
    func testPairingActionAdvancesTheSharedPairingFlow() {
        let model = CompanionAppModel()

        model.beginPairingSetup()

        XCTAssertEqual(model.sharedPairingStateCode, "scanning")
        XCTAssertEqual(model.route, .pairing(.preparingScanner))
    }

    @MainActor
    func testTabShellHasAnExplicitTestAndPreviewSeam() {
        let model = CompanionAppModel(launchMode: .tabShell)

        XCTAssertEqual(model.route, .tabShell)
    }

    @MainActor
    func testConnectingAndLockedStatesNeverFallThroughToTabs() {
        XCTAssertEqual(
            CompanionAppModel.route(rootStateCode: "connecting", pairingStateCode: nil),
            .pairing(.connecting)
        )
        XCTAssertEqual(
            CompanionAppModel.route(rootStateCode: "device_locked", pairingStateCode: nil),
            .recovery(.deviceLocked)
        )
    }

    @MainActor
    func testUnknownOrUnreadableSharedStateFailsClosed() {
        XCTAssertEqual(
            CompanionAppModel.route(rootStateCode: "future_state", pairingStateCode: nil),
            .recovery(.unavailable)
        )
        XCTAssertEqual(
            CompanionAppModel.route(rootStateCode: nil, pairingStateCode: nil),
            .recovery(.unavailable)
        )
    }
}
