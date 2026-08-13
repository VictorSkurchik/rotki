import Foundation

public enum DeviceSignerProbeOutcome: Equatable {
    case passed(publicKeyBytes: Int, signatureBytes: Int)
    case secureEnclaveUnavailable
    case requiresPairing
    case failed(String)
}

/// Thin, disposable API for an iOS host app used during physical acceptance.
/// It intentionally contains no UI and is not a production mobile boundary.
@MainActor
public final class IOSSecurityPhysicalHarness {
    private let signer: DeviceProofSigning
    private let snapshotStore: SecureSnapshotStore
    private let continuityGuard: InstallationContinuityGuard
    private var continuityEstablished = false

    public init(
        namespace: String = "org.rotki.p03.ios-security",
        snapshotFileURL: URL? = nil
    ) {
        signer = SecureEnclaveDeviceProofSigner(
            keychainService: namespace,
            keychainAccount: "device-proof-signing-key"
        )
        let wrapper = BiometricSecureEnclaveKeyWrapper(
            applicationTag: "\(namespace).snapshot-wrapping-key"
        )
        let resolvedSnapshotURL = snapshotFileURL
            ?? Self.defaultSnapshotURL(namespace: namespace)
        snapshotStore = SecureSnapshotStore(
            keyWrapper: wrapper,
            storage: AtomicSnapshotFileStorage(fileURL: resolvedSnapshotURL)
        )
        continuityGuard = InstallationContinuityGuard(
            containerMarkerStore: FileInstallationMarkerStore(
                fileURL: resolvedSnapshotURL
                    .deletingLastPathComponent()
                    .appendingPathComponent("installation.marker")
            ),
            keychainMarkerStore: KeychainInstallationMarkerStore(service: namespace),
            signer: signer,
            snapshotStore: snapshotStore
        )
    }

    init(
        signer: DeviceProofSigning,
        snapshotStore: SecureSnapshotStore,
        continuityGuard: InstallationContinuityGuard
    ) {
        self.signer = signer
        self.snapshotStore = snapshotStore
        self.continuityGuard = continuityGuard
    }

    public var state: IOSSecurityState {
        snapshotStore.state
    }

    public func establishInstallationContinuity() -> InstallationContinuityOutcome {
        let outcome = continuityGuard.establish()
        switch outcome {
        case .existingInstallation:
            continuityEstablished = true
            signer.setKeyCreationAuthorized(false)
        case .initializedFreshInstallation:
            continuityEstablished = true
            signer.setKeyCreationAuthorized(true)
        case .cleanupIncomplete, .failed:
            continuityEstablished = false
            signer.setKeyCreationAuthorized(false)
        }
        return outcome
    }

    public func exerciseDeviceSigner(message: Data) -> DeviceSignerProbeOutcome {
        guard continuityEstablished else {
            return .failed("installation continuity has not been established")
        }
        do {
            let proof = try signer.sign(message: message)
            guard DeviceProofFormat.verify(
                message: message,
                publicKeyX963: proof.publicKeyX963,
                signatureP1363: proof.signatureP1363
            ) else {
                return .failed("the generated P1363 signature did not verify")
            }
            return .passed(
                publicKeyBytes: proof.publicKeyX963.count,
                signatureBytes: proof.signatureP1363.count
            )
        } catch DeviceProofSignerError.secureEnclaveUnavailable {
            return .secureEnclaveUnavailable
        } catch DeviceProofSignerError.identityMissing {
            return .requiresPairing
        } catch {
            return .failed(String(describing: error))
        }
    }

    public func prepareSnapshotProtection(
        localizedReason: String
    ) async -> SnapshotPrepareOutcome {
        guard continuityEstablished else {
            return .failed("installation continuity has not been established")
        }
        let outcome = await snapshotStore.prepare(localizedReason: localizedReason)
        if case .requiresPairing = outcome {
            let failures = revokeInstallationAfterPairingLoss()
            if !failures.isEmpty {
                return .cleanupIncomplete(
                    failures.joined(separator: "; ")
                )
            }
        }
        if case let .cleanupIncomplete(message) = outcome {
            let failures = revokeInstallationAfterPairingLoss()
            return .cleanupIncomplete(
                ([message] + failures).joined(separator: "; ")
            )
        }
        return outcome
    }

    public func sealSnapshot(_ plaintext: Data) -> SnapshotSealOutcome {
        guard continuityEstablished else {
            return .failed("installation continuity has not been established")
        }
        return snapshotStore.seal(plaintext)
    }

    public func unlockSnapshot(
        localizedReason: String
    ) async -> SnapshotUnlockOutcome {
        guard continuityEstablished else {
            return .failed("installation continuity has not been established")
        }
        let outcome = await snapshotStore.unlock(localizedReason: localizedReason)
        if case .requiresPairing = outcome {
            let failures = revokeInstallationAfterPairingLoss()
            if !failures.isEmpty {
                return .cleanupIncomplete(
                    failures.joined(separator: "; ")
                )
            }
        }
        if case let .cleanupIncomplete(message) = outcome {
            let failures = revokeInstallationAfterPairingLoss()
            return .cleanupIncomplete(
                ([message] + failures).joined(separator: "; ")
            )
        }
        return outcome
    }

    public func lock() {
        snapshotStore.lock()
    }

    public func destroyAllSpikeMaterial() -> SnapshotDestroyOutcome {
        continuityEstablished = false
        signer.setKeyCreationAuthorized(false)
        let snapshotOutcome = snapshotStore.destroy()
        var failures: [String] = []
        if case let .cleanupIncomplete(messages) = snapshotOutcome {
            failures.append(contentsOf: messages)
        }
        do {
            try signer.deleteKey()
        } catch {
            failures.append("device proof-key deletion failed: \(error)")
        }
        failures.append(contentsOf: continuityGuard.removeMarkers())
        if failures.isEmpty {
            return .destroyed
        }
        return .cleanupIncomplete(failures)
    }

    private func revokeInstallationAfterPairingLoss() -> [String] {
        continuityEstablished = false
        signer.setKeyCreationAuthorized(false)
        var failures: [String] = []
        do {
            try signer.deleteKey()
        } catch {
            failures.append("device proof-key deletion failed: \(error)")
        }
        failures.append(contentsOf: continuityGuard.removeMarkers())
        return failures
    }

    private static func defaultSnapshotURL(namespace: String) -> URL {
        let baseURL = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        return baseURL
            .appendingPathComponent(namespace, isDirectory: true)
            .appendingPathComponent("snapshot.rkp03", isDirectory: false)
    }
}
