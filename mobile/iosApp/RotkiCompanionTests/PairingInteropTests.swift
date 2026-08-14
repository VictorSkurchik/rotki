import RotkiShared
import XCTest

final class PairingInteropTests: XCTestCase {
    func testPairingFlowAdapterRemainsExportedByUmbrellaFramework() {
        let facade = CompanionFacade()
        let flow = facade.pairingFlow()

        guard let intro = flow.presentation.value as? PairingPresentation else {
            XCTFail("Expected an exported PairingPresentation for the initial state")
            return
        }
        XCTAssertEqual(intro.state.code, "intro")
        XCTAssertNil(intro.rejectionCategory)

        flow.startScanning()

        guard let scanning = flow.presentation.value as? PairingPresentation else {
            XCTFail("Expected an exported PairingPresentation while scanning")
            return
        }
        XCTAssertEqual(scanning.state.code, "scanning")
        XCTAssertNil(scanning.rejectionCategory)

        flow.submitQr(rawPayload: "{}")

        guard let rejected = flow.presentation.value as? PairingPresentation else {
            XCTFail("Expected an exported PairingPresentation after QR rejection")
            return
        }
        XCTAssertEqual(rejected.state.code, "invalid_qr")
        XCTAssertEqual(rejected.rejectionCategory?.code, "malformed")
    }

    func testCoreCommonLifecycleSurfaceRemainsExportedByUmbrellaFramework() {
        let controller = ApplicationVisibilityController()

        XCTAssertEqual(visibilityStateName(controller), "BACKGROUND_OR_LOCKED")
        controller.onInactive()
        XCTAssertEqual(visibilityStateName(controller), "BACKGROUND_OR_LOCKED")
        controller.onActiveForeground()
        XCTAssertEqual(visibilityStateName(controller), "ACTIVE_FOREGROUND")
        controller.onInactive()
        XCTAssertEqual(visibilityStateName(controller), "INACTIVE")
        controller.onBackgroundOrLocked()
        XCTAssertEqual(visibilityStateName(controller), "BACKGROUND_OR_LOCKED")
        XCTAssertEqual(controller.description(), "ApplicationVisibilityController(redacted)")

        assertDecision(
            ApplicationVisibilityPolicy.shared.decide(state: .activeForeground),
            networkAllowed: true,
            destroyBearer: false,
            discardPlaintext: false,
            requiresDeviceAuthenticationOnReturn: false
        )
        assertDecision(
            ApplicationVisibilityPolicy.shared.decide(state: .inactive),
            networkAllowed: false,
            destroyBearer: false,
            discardPlaintext: false,
            requiresDeviceAuthenticationOnReturn: false
        )
        assertDecision(
            ApplicationVisibilityPolicy.shared.decide(state: .backgroundOrLocked),
            networkAllowed: false,
            destroyBearer: true,
            discardPlaintext: true,
            requiresDeviceAuthenticationOnReturn: true
        )
    }

    func testSwiftClockDrivesExportedPairingFlowOverload() {
        let clock = FixedClock(nowEpochSeconds: 1_786_550_300)
        let flow = CompanionFacade().pairingFlow(clock: clock)
        flow.startScanning()

        flow.submitQr(
            rawPayload: """
            {"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"https://rotki.example","pairing_id":"AAECAwQFBgcICQoLDA0ODw","pairing_credential":"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8","expires_at":1786550300}
            """
        )

        guard let presentation = flow.presentation.value as? PairingPresentation else {
            XCTFail("Expected an exported PairingPresentation after clock-driven QR validation")
            return
        }
        XCTAssertEqual(clock.callCount, 1)
        XCTAssertEqual(presentation.state.code, "expired_qr")
        XCTAssertEqual(presentation.rejectionCategory?.code, "expired")
    }

    private func visibilityStateName(
        _ controller: ApplicationVisibilityController
    ) -> String? {
        (controller.state.value as? ApplicationVisibilityState)?.name
    }

    private func assertDecision(
        _ decision: ApplicationVisibilityDecision,
        networkAllowed: Bool,
        destroyBearer: Bool,
        discardPlaintext: Bool,
        requiresDeviceAuthenticationOnReturn: Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(decision.networkAllowed, networkAllowed, file: file, line: line)
        XCTAssertEqual(decision.destroyBearer, destroyBearer, file: file, line: line)
        XCTAssertEqual(decision.discardPlaintext, discardPlaintext, file: file, line: line)
        XCTAssertEqual(
            decision.requiresDeviceAuthenticationOnReturn,
            requiresDeviceAuthenticationOnReturn,
            file: file,
            line: line
        )
    }
}

private final class FixedClock: NSObject, Clock {
    private let fixedNowEpochSeconds: Int64

    private(set) var callCount = 0

    init(nowEpochSeconds: Int64) {
        self.fixedNowEpochSeconds = nowEpochSeconds
    }

    func nowEpochSeconds() -> Int64 {
        callCount += 1
        return fixedNowEpochSeconds
    }
}

private func compilePairingConnectionSurface(
    facade: CompanionFacade,
    deviceProofSigner: any DeviceProofSigner,
    pairingRecordStore: any PairingRecordStore,
    pairingCleanupJournal: any PairingCleanupJournal,
    idempotencyKeyGenerator: any IdempotencyKeyGenerator,
    applicationVisibility: any ApplicationVisibility,
    clock: any Clock
) -> PairingConnection {
    let configuration = PairingConnectionConfiguration(
        deviceLabel: "iPhone",
        platform: .ios,
        deviceProofSigner: deviceProofSigner,
        pairingRecordStore: pairingRecordStore,
        pairingCleanupJournal: pairingCleanupJournal,
        idempotencyKeyGenerator: idempotencyKeyGenerator,
        applicationVisibility: applicationVisibility,
        clock: clock
    )
    return facade.pairingConnection(configuration: configuration)
}

private func compileSecurityStoreSurface(
    store: any SecureSnapshotStore,
    readOutcome: any SecureSnapshotReadOutcome,
    writeOutcome: any SecureSnapshotWriteOutcome,
    deleteOutcome: any SecureSnapshotDeleteOutcome
) {
    store.discardPlaintext()
    _ = readOutcome
    _ = writeOutcome
    _ = deleteOutcome
}
