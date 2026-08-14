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
