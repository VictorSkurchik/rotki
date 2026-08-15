import Foundation
import LocalAuthentication
import RotkiShared

/// One manually assembled security graph retained for the iOS application process.
@MainActor
final class IOSSecurityComposition {
    let facade: CompanionFacade
    let visibility: ApplicationVisibilityController
    let pairingConnection: PairingConnection

    private let deviceProofSigner: IOSDeviceProofSigner
    private let pairingRecordStore: IOSPairingRecordStore
    private let pairingCleanupJournal: IOSPairingCleanupJournal
    private let deviceAuthenticator = IOSDeviceAuthenticator()

    init(facade: CompanionFacade? = nil) {
        let signer = IOSDeviceProofSigner()
        let recordStore = IOSPairingRecordStore()
        let cleanupJournal = IOSPairingCleanupJournal()
        let resolvedFacade: CompanionFacade
        if let facade {
            resolvedFacade = facade
        } else if recordStore.hasUsableRecord && signer.hasCurrentKey && cleanupJournal.isClear {
            resolvedFacade = CompanionFacade.companion.restorePaired(
                snapshotCoverage: SnapshotCoverageAbsent.shared
            )
        } else {
            resolvedFacade = CompanionFacade()
        }
        self.facade = resolvedFacade
        visibility = ApplicationVisibilityController()
        deviceProofSigner = signer
        pairingRecordStore = recordStore
        pairingCleanupJournal = cleanupJournal
        pairingConnection = resolvedFacade.pairingConnection(
            configuration: PairingConnectionConfiguration(
                deviceLabel: "iPhone",
                platform: .ios,
                deviceProofSigner: deviceProofSigner,
                pairingRecordStore: pairingRecordStore,
                pairingCleanupJournal: pairingCleanupJournal,
                idempotencyKeyGenerator: IOSIdempotencyKeyGenerator(),
                applicationVisibility: visibility,
                clock: IOSEpochClock()
            )
        )
    }

    func sceneBecameActive() async {
        visibility.onActiveForeground()
        guard rootStateCode == "device_locked" else { return }
        guard await deviceAuthenticator.authenticate() else { return }
        facade.deviceAuthenticationSucceeded()
    }

    func sceneBecameInactive() {
        visibility.onInactive()
    }

    func sceneEnteredBackground() {
        visibility.onBackgroundOrLocked()
        facade.lock()
    }

    func close() {
        pairingConnection.close()
    }

    private var rootStateCode: String? {
        (facade.status.value as? CompanionStatus)?.rootState.code
    }
}

private final class IOSDeviceAuthenticator {
    func authenticate() async -> Bool {
        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        var policyError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) else {
            return false
        }
        return await withCheckedContinuation { continuation in
            context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: "Unlock your rotki companion portfolio"
            ) { accepted, _ in
                continuation.resume(returning: accepted)
            }
        }
    }
}
