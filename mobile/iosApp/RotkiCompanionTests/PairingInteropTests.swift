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

    func testEngineOriginSurfaceRemainsExportedByUmbrellaFramework() {
        let acceptedOutcome: any EngineOriginParseOutcome = EngineOrigin.companion.parse(
            candidate: "https://rotki.example:4242"
        )
        guard let accepted = acceptedOutcome as? EngineOriginParseOutcomeAccepted else {
            XCTFail("Expected the exported accepted engine-origin outcome type")
            return
        }

        XCTAssertEqual(accepted.origin.canonical, "https://rotki.example:4242")
        XCTAssertEqual(accepted.origin.restApiBase, "https://rotki.example:4242/api/1")
        XCTAssertEqual(accepted.origin.webSocketEndpoint, "wss://rotki.example:4242/ws")
        XCTAssertEqual(accepted.origin.description(), "EngineOrigin(redacted)")

        let rejectedOutcome: any EngineOriginParseOutcome = EngineOrigin.companion.parse(
            candidate: "https://rotki.example:443"
        )
        guard let rejected = rejectedOutcome as? EngineOriginParseOutcomeRejected else {
            XCTFail("Expected the exported rejected engine-origin outcome type")
            return
        }
        XCTAssertTrue(rejected.reason === EngineOriginRejection.defaultPortForbidden)
    }

    func testSecurityPortSurfaceRemainsExportedByUmbrellaFramework() {
        let originOutcome: any EngineOriginParseOutcome = EngineOrigin.companion.parse(
            candidate: "https://rotki.example:4242"
        )
        let sessionOutcome: any ProtocolValueParseOutcome = DeviceSessionId.companion.parse(
            candidate: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        )
        let idempotencyOutcome: any ProtocolValueParseOutcome = IdempotencyKey.companion.parse(
            candidate: "AAECAwQFBgcICQoLDA0ODw"
        )
        let publicKeyOutcome: any ProtocolValueParseOutcome = X963PublicKey.companion.parse(
            candidate: "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
        )
        let signatureOutcome: any ProtocolValueParseOutcome = P1363Signature.companion.parse(
            candidate: "zKnDT8nsSEMoIxqZIzUybLt-QJJr6mtaaXm6SJMRmK7kJLpg-BP20iAJBHwLqXstAHFxaHwb_vs_jb4hSU6N8A"
        )

        guard
            let origin = (originOutcome as? EngineOriginParseOutcomeAccepted)?.origin,
            let sessionId = (
                sessionOutcome as? ProtocolValueParseOutcomeAccepted<DeviceSessionId>
            )?.value,
            let idempotencyKey = (
                idempotencyOutcome as? ProtocolValueParseOutcomeAccepted<IdempotencyKey>
            )?.value,
            let publicKey = (
                publicKeyOutcome as? ProtocolValueParseOutcomeAccepted<X963PublicKey>
            )?.value,
            let signature = (
                signatureOutcome as? ProtocolValueParseOutcomeAccepted<P1363Signature>
            )?.value
        else {
            XCTFail("Expected public security-port vectors to parse")
            return
        }

        let record = PairingRecord(engineOrigin: origin, deviceSessionId: sessionId)
        XCTAssertEqual(record.description(), "PairingRecord(redacted)")
        XCTAssertEqual(record.engineOrigin.canonical, "https://rotki.example:4242")
        XCTAssertEqual(record.deviceSessionId.description(), "DeviceSessionId(redacted)")

        let present: any PairingRecordReadOutcome = PairingRecordReadOutcomePresent(record: record)
        XCTAssertEqual(
            (present as? PairingRecordReadOutcomePresent)?.record.description(),
            "PairingRecord(redacted)"
        )
        let readOutcomes: [any PairingRecordReadOutcome] = [
            PairingRecordReadOutcomeMissing.shared,
            PairingRecordReadOutcomeCorrupt.shared,
            PairingRecordReadOutcomeUnavailable.shared,
        ]
        XCTAssertTrue(readOutcomes[0] is PairingRecordReadOutcomeMissing)
        XCTAssertTrue(readOutcomes[1] is PairingRecordReadOutcomeCorrupt)
        XCTAssertTrue(readOutcomes[2] is PairingRecordReadOutcomeUnavailable)

        let writeOutcomes: [any PairingRecordWriteOutcome] = [
            PairingRecordWriteOutcomeStored.shared,
            PairingRecordWriteOutcomeUnavailable.shared,
        ]
        XCTAssertTrue(writeOutcomes[0] is PairingRecordWriteOutcomeStored)
        XCTAssertTrue(writeOutcomes[1] is PairingRecordWriteOutcomeUnavailable)

        let deleteOutcomes: [any PairingRecordDeleteOutcome] = [
            PairingRecordDeleteOutcomeDeleted.shared,
            PairingRecordDeleteOutcomeUnavailable.shared,
        ]
        XCTAssertTrue(deleteOutcomes[0] is PairingRecordDeleteOutcomeDeleted)
        XCTAssertTrue(deleteOutcomes[1] is PairingRecordDeleteOutcomeUnavailable)

        let publicKeyResult: any DeviceProofPublicKeyOutcome =
            DeviceProofPublicKeyOutcomePublicKey(value: publicKey)
        XCTAssertEqual(
            (publicKeyResult as? DeviceProofPublicKeyOutcomePublicKey)?.value.description(),
            "X963PublicKey(redacted)"
        )
        let publicKeyFailures: [any DeviceProofPublicKeyOutcome] = [
            DeviceProofPublicKeyOutcomePairingRequired.shared,
            DeviceProofPublicKeyOutcomeUnexpectedFailure.shared,
        ]
        XCTAssertTrue(publicKeyFailures[0] is DeviceProofPublicKeyOutcomePairingRequired)
        XCTAssertTrue(publicKeyFailures[1] is DeviceProofPublicKeyOutcomeUnexpectedFailure)

        let signed: any DeviceProofSigningOutcome =
            DeviceProofSigningOutcomeSigned(signature: signature)
        XCTAssertEqual(
            (signed as? DeviceProofSigningOutcomeSigned)?.signature.description(),
            "P1363Signature(redacted)"
        )
        let signingFailures: [any DeviceProofSigningOutcome] = [
            DeviceProofSigningOutcomeDeviceAuthenticationCancelled.shared,
            DeviceProofSigningOutcomeDeviceAuthenticationUnavailable.shared,
            DeviceProofSigningOutcomePairingRequired.shared,
            DeviceProofSigningOutcomeUnexpectedFailure.shared,
        ]
        XCTAssertTrue(
            signingFailures[0] is DeviceProofSigningOutcomeDeviceAuthenticationCancelled
        )
        XCTAssertTrue(
            signingFailures[1] is DeviceProofSigningOutcomeDeviceAuthenticationUnavailable
        )
        XCTAssertTrue(signingFailures[2] is DeviceProofSigningOutcomePairingRequired)
        XCTAssertTrue(signingFailures[3] is DeviceProofSigningOutcomeUnexpectedFailure)

        let keyDeleteOutcomes: [any DeviceProofKeyDeleteOutcome] = [
            DeviceProofKeyDeleteOutcomeDeleted.shared,
            DeviceProofKeyDeleteOutcomeUnavailable.shared,
        ]
        XCTAssertTrue(keyDeleteOutcomes[0] is DeviceProofKeyDeleteOutcomeDeleted)
        XCTAssertTrue(keyDeleteOutcomes[1] is DeviceProofKeyDeleteOutcomeUnavailable)

        let generator = FixedIdempotencyKeyGenerator(key: idempotencyKey)
        XCTAssertTrue(generator.generate() === idempotencyKey)
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

private final class FixedIdempotencyKeyGenerator: NSObject, IdempotencyKeyGenerator {
    private let key: IdempotencyKey

    init(key: IdempotencyKey) {
        self.key = key
    }

    func generate() -> IdempotencyKey {
        key
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
