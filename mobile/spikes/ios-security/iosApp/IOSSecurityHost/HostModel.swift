import Foundation
import IOSSecuritySpike

@MainActor
final class HostModel: ObservableObject {
    @Published private(set) var stateText = "unprepared"
    @Published private(set) var statusText = "launch continuity check pending"
    @Published private(set) var isBusy = false
    @Published private(set) var isPrivacyCovered = false

    private let harness = IOSSecurityPhysicalHarness()
    private let fixture = Data("rotki P0.3 physical snapshot fixture".utf8)

    func establishInstallationContinuity() {
        guard !isBusy else { return }
        let outcome = harness.establishInstallationContinuity()
        switch outcome {
        case .existingInstallation:
            statusText = "continuity: existing installation marker matched"
        case let .initializedFreshInstallation(replaced):
            statusText = replaced
                ? "continuity: mismatch detected; old material purged; fresh marker created"
                : "continuity: fresh installation initialized"
        case let .cleanupIncomplete(messages):
            statusText = "continuity cleanup incomplete: \(messages.joined(separator: "; "))"
        case let .failed(message):
            statusText = "continuity failed: \(message)"
        }
        refreshState()
    }

    func probeSigner() {
        guard !isBusy else { return }
        let message = DeviceProofTranscript.p03GoldenVector()
        switch harness.exerciseDeviceSigner(message: message) {
        case let .passed(publicKeyBytes, signatureBytes):
            statusText = "signer passed: X9.63=\(publicKeyBytes) bytes, P1363=\(signatureBytes) bytes"
        case .secureEnclaveUnavailable:
            statusText = "signer blocked: Secure Enclave unavailable"
        case .requiresPairing:
            statusText = "signer identity missing: Pairing must create a new identity"
        case let .failed(message):
            statusText = "signer failed: \(message)"
        }
        refreshState()
    }

    func prepare() {
        runAsync {
            let outcome = await self.harness.prepareSnapshotProtection(
                localizedReason: "Protect the offline portfolio snapshot"
            )
            self.statusText = Self.describe(outcome)
        }
    }

    func sealFixture() {
        guard !isBusy else { return }
        statusText = Self.describe(harness.sealSnapshot(fixture))
        refreshState()
    }

    func unlock() {
        runAsync {
            let outcome = await self.harness.unlockSnapshot(
                localizedReason: "Open the offline portfolio snapshot"
            )
            self.statusText = Self.describe(outcome)
        }
    }

    func lock(reason: String) {
        harness.lock()
        isBusy = false
        statusText = "locked: \(reason)"
        refreshState()
    }

    func setPrivacyCovered(_ covered: Bool) {
        isPrivacyCovered = covered
    }

    func destroy() {
        guard !isBusy else { return }
        switch harness.destroyAllSpikeMaterial() {
        case .destroyed:
            statusText = "destroyed snapshot, keys, and install markers; re-check continuity before Pairing"
        case let .cleanupIncomplete(messages):
            statusText = "cleanup incomplete: \(messages.joined(separator: "; "))"
        }
        refreshState()
    }

    private func runAsync(_ operation: @escaping @MainActor () async -> Void) {
        guard !isBusy else { return }
        isBusy = true
        Task { @MainActor in
            await operation()
            isBusy = false
            refreshState()
        }
    }

    private func refreshState() {
        stateText = String(describing: harness.state)
    }

    private static func describe(_ outcome: SnapshotPrepareOutcome) -> String {
        switch outcome {
        case .readyForFirstSnapshot: return "prepare: ready for first snapshot"
        case .lockedSnapshotAvailable: return "prepare: existing snapshot is locked"
        case .cancelled: return "prepare: cancelled"
        case .lockedOut: return "prepare: biometric lockout"
        case let .blocked(reason): return "prepare blocked: \(reason)"
        case let .requiresPairing(reason): return "prepare requires Pairing: \(reason)"
        case let .cleanupIncomplete(message): return "prepare cleanup incomplete: \(message)"
        case let .failed(message): return "prepare failed: \(message)"
        }
    }

    private static func describe(_ outcome: SnapshotSealOutcome) -> String {
        switch outcome {
        case .sealed: return "seal: known fixture stored"
        case .locked: return "seal blocked: authenticate first"
        case let .failed(message): return "seal failed: \(message)"
        }
    }

    private static func describe(_ outcome: SnapshotUnlockOutcome) -> String {
        switch outcome {
        case let .opened(data): return "unlock: authenticated \(data.count) fixture bytes"
        case .noSnapshot: return "unlock: no snapshot"
        case .cancelled: return "unlock: cancelled or superseded by lock"
        case .lockedOut: return "unlock: biometric lockout; material retained"
        case let .blocked(reason): return "unlock blocked: \(reason)"
        case let .requiresPairing(reason): return "unlock requires Pairing: \(reason)"
        case let .cleanupIncomplete(message): return "unlock cleanup incomplete: \(message)"
        case .corrupt: return "unlock: corrupt/tampered envelope"
        case let .failed(message): return "unlock failed: \(message)"
        }
    }
}
