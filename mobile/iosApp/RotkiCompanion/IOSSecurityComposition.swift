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
    private let deviceAuthenticator: any IOSDeviceAuthenticating
    private let deviceAuthenticationSucceeded: () -> Void
    private var nextDeviceAuthenticationAttemptId: UInt64 = 0
    private var currentDeviceAuthenticationAttemptId: UInt64?
    private var deferredAcceptedDeviceAuthenticationAttemptId: UInt64?
    private var isClosed = false

    init(
        facade: CompanionFacade? = nil,
        deviceAuthenticator: (any IOSDeviceAuthenticating)? = nil,
        deviceAuthenticationSucceeded: (() -> Void)? = nil
    ) {
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
        self.deviceAuthenticator = deviceAuthenticator ?? IOSDeviceAuthenticator()
        self.deviceAuthenticationSucceeded = deviceAuthenticationSucceeded ?? {
            _ = resolvedFacade.deviceAuthenticationSucceeded()
        }
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
        guard !isClosed else { return }
        visibility.onActiveForeground()
        guard rootStateCode == "device_locked" else {
            deferredAcceptedDeviceAuthenticationAttemptId = nil
            return
        }
        if deferredAcceptedDeviceAuthenticationAttemptId != nil {
            deferredAcceptedDeviceAuthenticationAttemptId = nil
            deviceAuthenticationSucceeded()
            return
        }
        guard currentDeviceAuthenticationAttemptId == nil else { return }

        nextDeviceAuthenticationAttemptId &+= 1
        let attemptId = nextDeviceAuthenticationAttemptId
        currentDeviceAuthenticationAttemptId = attemptId
        let accepted = await deviceAuthenticator.authenticate()
        guard currentDeviceAuthenticationAttemptId == attemptId else { return }
        currentDeviceAuthenticationAttemptId = nil
        guard
            accepted,
            !isClosed,
            rootStateCode == "device_locked"
        else {
            return
        }
        if isActiveForeground {
            deviceAuthenticationSucceeded()
        } else if isInactive {
            deferredAcceptedDeviceAuthenticationAttemptId = attemptId
        }
    }

    func sceneBecameInactive() {
        guard !isClosed else { return }
        visibility.onInactive()
    }

    func sceneEnteredBackground() {
        guard !isClosed else { return }
        visibility.onBackgroundOrLocked()
        facade.lock()
        invalidatePendingDeviceAuthentication()
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        invalidatePendingDeviceAuthentication()
        pairingConnection.close()
    }

    private var rootStateCode: String? {
        (facade.status.value as? CompanionStatus)?.rootState.code
    }

    private var isActiveForeground: Bool {
        guard let state = visibility.state.value as? ApplicationVisibilityState else {
            return false
        }
        return state === ApplicationVisibilityState.activeForeground
    }

    private var isInactive: Bool {
        guard let state = visibility.state.value as? ApplicationVisibilityState else {
            return false
        }
        return state === ApplicationVisibilityState.inactive
    }

    private func invalidatePendingDeviceAuthentication() {
        nextDeviceAuthenticationAttemptId &+= 1
        currentDeviceAuthenticationAttemptId = nil
        deferredAcceptedDeviceAuthenticationAttemptId = nil
        deviceAuthenticator.cancel()
    }
}

@MainActor
protocol IOSDeviceAuthenticating: AnyObject {
    func authenticate() async -> Bool

    func cancel()
}

@MainActor
final class IOSDeviceAuthenticator: IOSDeviceAuthenticating {
    private var currentContext: LAContext?

    func authenticate() async -> Bool {
        cancel()
        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        currentContext = context
        var policyError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) else {
            if currentContext === context {
                currentContext = nil
            }
            return false
        }
        let accepted: Bool = await withCheckedContinuation { continuation in
            context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: "Unlock your rotki companion portfolio"
            ) { accepted, _ in
                continuation.resume(returning: accepted)
            }
        }
        guard currentContext === context else { return false }
        currentContext = nil
        return accepted
    }

    func cancel() {
        currentContext?.invalidate()
        currentContext = nil
    }
}
