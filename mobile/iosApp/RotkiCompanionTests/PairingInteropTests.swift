import RotkiShared
import XCTest

final class PairingInteropTests: XCTestCase {
    func testProtocolValueSurfaceRemainsExportedByUmbrellaFramework() {
        // These are public deterministic protocol vectors, never production Pairing material.
        let deviceSessionOutcome: any ProtocolValueParseOutcome = DeviceSessionId.companion.parse(
            candidate: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        )
        let idempotencyOutcome: any ProtocolValueParseOutcome = IdempotencyKey.companion.parse(
            candidate: "AAECAwQFBgcICQoLDA0ODw"
        )
        let signatureOutcome: any ProtocolValueParseOutcome = P1363Signature.companion.parse(
            candidate: "zKnDT8nsSEMoIxqZIzUybLt-QJJr6mtaaXm6SJMRmK7kJLpg-BP20iAJBHwLqXstAHFxaHwb_vs_jb4hSU6N8A"
        )
        let publicKeyOutcome: any ProtocolValueParseOutcome = X963PublicKey.companion.parse(
            candidate: "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
        )

        guard
            let deviceSession = (
                deviceSessionOutcome as? ProtocolValueParseOutcomeAccepted<DeviceSessionId>
            )?.value,
            let idempotencyKey = (
                idempotencyOutcome as? ProtocolValueParseOutcomeAccepted<IdempotencyKey>
            )?.value,
            let signature = (
                signatureOutcome as? ProtocolValueParseOutcomeAccepted<P1363Signature>
            )?.value,
            let publicKey = (
                publicKeyOutcome as? ProtocolValueParseOutcomeAccepted<X963PublicKey>
            )?.value
        else {
            XCTFail("Expected public protocol vectors to parse through exported value types")
            return
        }

        XCTAssertEqual(deviceSession.description(), "DeviceSessionId(redacted)")
        XCTAssertEqual(idempotencyKey.description(), "IdempotencyKey(redacted)")
        XCTAssertEqual(signature.description(), "P1363Signature(redacted)")
        XCTAssertEqual(publicKey.description(), "X963PublicKey(redacted)")

        let rejectedOutcome: any ProtocolValueParseOutcome = DeviceSessionId.companion.parse(
            candidate: "invalid"
        )
        guard let rejected = rejectedOutcome as? ProtocolValueParseOutcomeRejected else {
            XCTFail("Expected the exported rejected outcome type")
            return
        }
        XCTAssertTrue(rejected.reason === ProtocolValueRejection.invalidLength)
    }

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
