import RotkiShared
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

    @MainActor
    func testQueuedActiveDoesNotOverrideNewerInactiveOrBackground() async {
        let facade = CompanionFacade.companion.restorePaired(
            snapshotCoverage: SnapshotCoverageAbsent.shared
        )
        let authenticator = ImmediateDeviceAuthenticator()
        let composition = IOSSecurityComposition(
            facade: facade,
            deviceAuthenticator: authenticator
        )
        let model = CompanionAppModel(
            facade: facade,
            securityComposition: composition
        )
        defer { composition.close() }
        composition.visibility.onActiveForeground()

        let inactiveAdmission = model.sceneBecameActive()
        XCTAssertNotNil(inactiveAdmission)
        model.sceneBecameInactive()
        await inactiveAdmission?.value

        XCTAssertEqual(authenticator.authenticateCalls, 0)
        XCTAssertEqual(authenticator.cancelCalls, 0)
        XCTAssertEqual(visibilityStateName(composition.visibility), "INACTIVE")

        let backgroundAdmission = model.sceneBecameActive()
        XCTAssertNotNil(backgroundAdmission)
        model.sceneEnteredBackground()
        await backgroundAdmission?.value

        XCTAssertEqual(authenticator.authenticateCalls, 0)
        XCTAssertEqual(authenticator.cancelCalls, 1)
        XCTAssertEqual(visibilityStateName(composition.visibility), "BACKGROUND_OR_LOCKED")
    }

    @MainActor
    private func visibilityStateName(
        _ visibility: ApplicationVisibilityController
    ) -> String? {
        (visibility.state.value as? ApplicationVisibilityState)?.name
    }
}

@MainActor
private final class ImmediateDeviceAuthenticator: IOSDeviceAuthenticating {
    private(set) var authenticateCalls = 0
    private(set) var cancelCalls = 0

    func authenticate() async -> Bool {
        authenticateCalls += 1
        return false
    }

    func cancel() {
        cancelCalls += 1
    }
}
