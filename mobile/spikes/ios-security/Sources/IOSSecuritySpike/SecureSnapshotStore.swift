import Foundation

@MainActor
public final class SecureSnapshotStore {
    private let keyWrapper: SnapshotKeyWrapping
    private let storage: SnapshotStorage
    private var stateMachine: IOSSecurityStateMachine
    private var unlockedContentKey: Data?
    private var unlockedWrappedContentKey: Data?
    private var operationEpoch: UInt64 = 0

    public init(
        keyWrapper: SnapshotKeyWrapping,
        storage: SnapshotStorage,
        initialState: IOSSecurityState = .unprepared
    ) {
        self.keyWrapper = keyWrapper
        self.storage = storage
        stateMachine = IOSSecurityStateMachine(state: initialState)
    }

    public var state: IOSSecurityState {
        stateMachine.state
    }

    public func prepare(localizedReason: String) async -> SnapshotPrepareOutcome {
        let hasSnapshot = storage.exists
        let epoch = beginOperation()
        switch await keyWrapper.prepare(
            hasProtectedSnapshot: hasSnapshot,
            localizedReason: localizedReason
        ) {
        case .ready:
            guard epoch == operationEpoch else { return .cancelled }
            stateMachine.apply(.preparationReady(hasSnapshot: hasSnapshot))
            return hasSnapshot ? .lockedSnapshotAvailable : .readyForFirstSnapshot
        case .cancelled:
            guard epoch == operationEpoch else { return .cancelled }
            if hasSnapshot {
                stateMachine.apply(.unlockCancelled)
            }
            return .cancelled
        case .lockedOut:
            guard epoch == operationEpoch else { return .cancelled }
            if hasSnapshot {
                stateMachine.apply(.biometryLockedOut)
            }
            return .lockedOut
        case let .blocked(reason):
            guard epoch == operationEpoch else { return .cancelled }
            if hasSnapshot,
               reason == .biometryNotEnrolled || reason == .passcodeRequired
            {
                let failures = destroyMaterial()
                guard failures.isEmpty else {
                    return .cleanupIncomplete(failures.joined(separator: "; "))
                }
                let pairingLoss: PairingLossReason = reason == .passcodeRequired
                    ? .devicePasscodeRemoved
                    : .biometricEnrollmentChanged
                stateMachine.apply(.keyInvalidated(pairingLoss))
                return .requiresPairing(pairingLoss)
            }
            stateMachine.apply(.preparationBlocked(reason))
            return .blocked(reason)
        case let .invalidated(reason):
            guard epoch == operationEpoch else { return .cancelled }
            let failures = destroyMaterial()
            guard failures.isEmpty else {
                return .cleanupIncomplete(failures.joined(separator: "; "))
            }
            stateMachine.apply(.keyInvalidated(reason))
            return .requiresPairing(reason)
        case let .failed(message):
            guard epoch == operationEpoch else { return .cancelled }
            return .failed(message)
        }
    }

    public func seal(_ plaintext: Data) -> SnapshotSealOutcome {
        guard state == .readyForFirstSnapshot || state == .unlocked else {
            return .locked
        }

        do {
            let contentKey: Data
            let wrappedContentKey: Data
            if let existingContentKey = unlockedContentKey,
               let existingWrappedContentKey = unlockedWrappedContentKey
            {
                contentKey = existingContentKey
                wrappedContentKey = existingWrappedContentKey
            } else {
                contentKey = SnapshotCipher.makeContentKey()
                wrappedContentKey = try keyWrapper.wrap(contentKey)
            }

            let sealedSnapshot = try SnapshotCipher.seal(
                plaintext,
                contentKey: contentKey
            )
            let encoded = try SnapshotEnvelopeCodec.encode(
                SnapshotEnvelope(
                    wrappedContentKey: wrappedContentKey,
                    sealedSnapshot: sealedSnapshot
                )
            )
            try storage.writeAtomically(encoded)

            unlockedContentKey = contentKey
            unlockedWrappedContentKey = wrappedContentKey
            stateMachine.apply(.firstSnapshotSealed)
            return .sealed
        } catch {
            return .failed(String(describing: error))
        }
    }

    public func unlock(localizedReason: String) async -> SnapshotUnlockOutcome {
        let epoch = beginOperation()
        guard storage.exists else {
            return .noSnapshot
        }

        let envelope: SnapshotEnvelope
        do {
            envelope = try SnapshotEnvelopeCodec.decode(storage.read())
        } catch {
            clearUnlockedMaterial()
            stateMachine.apply(.snapshotTampered)
            return .corrupt
        }

        switch await keyWrapper.unwrap(
            envelope.wrappedContentKey,
            localizedReason: localizedReason
        ) {
        case var .opened(contentKey):
            guard epoch == operationEpoch else {
                Self.bestEffortZeroize(&contentKey)
                return .cancelled
            }
            do {
                let plaintext = try SnapshotCipher.open(
                    envelope.sealedSnapshot,
                    contentKey: contentKey
                )
                unlockedContentKey = contentKey
                unlockedWrappedContentKey = envelope.wrappedContentKey
                stateMachine.apply(.unlockSucceeded)
                return .opened(plaintext)
            } catch {
                clearUnlockedMaterial()
                stateMachine.apply(.snapshotTampered)
                return .corrupt
            }
        case .cancelled:
            guard epoch == operationEpoch else { return .cancelled }
            clearUnlockedMaterial()
            stateMachine.apply(.unlockCancelled)
            return .cancelled
        case .lockedOut:
            guard epoch == operationEpoch else { return .cancelled }
            clearUnlockedMaterial()
            stateMachine.apply(.biometryLockedOut)
            return .lockedOut
        case let .blocked(reason):
            guard epoch == operationEpoch else { return .cancelled }
            clearUnlockedMaterial()
            stateMachine.apply(.preparationBlocked(reason))
            return .blocked(reason)
        case let .invalidated(reason):
            guard epoch == operationEpoch else { return .cancelled }
            let failures = destroyMaterial()
            guard failures.isEmpty else {
                return .cleanupIncomplete(failures.joined(separator: "; "))
            }
            stateMachine.apply(.keyInvalidated(reason))
            return .requiresPairing(reason)
        case let .failed(message):
            guard epoch == operationEpoch else { return .cancelled }
            clearUnlockedMaterial()
            return .failed(message)
        }
    }

    /// Call on backgrounding, system lock, account switch, and explicit lock.
    public func lock() {
        operationEpoch &+= 1
        keyWrapper.cancelAuthentication()
        clearUnlockedMaterial()
        stateMachine.apply(.applicationLocked)
    }

    public func destroy() -> SnapshotDestroyOutcome {
        operationEpoch &+= 1
        keyWrapper.cancelAuthentication()
        clearUnlockedMaterial()
        let failures = destroyMaterial()
        if failures.isEmpty {
            stateMachine.apply(.destroyed)
            return .destroyed
        } else {
            return .cleanupIncomplete(failures)
        }
    }

    private func destroyMaterial() -> [String] {
        var failures: [String] = []
        do {
            try storage.delete()
        } catch {
            failures.append("snapshot deletion failed: \(error)")
        }
        do {
            try keyWrapper.deleteKey()
        } catch {
            failures.append("wrapping-key deletion failed: \(error)")
        }
        clearUnlockedMaterial()
        return failures
    }

    private func clearUnlockedMaterial() {
        // Best effort: Data/Swift do not guarantee that historical CoW buffers
        // or compiler temporaries are zeroized. Mutate the currently retained
        // buffer in place before releasing it.
        if unlockedContentKey != nil {
            Self.bestEffortZeroize(&unlockedContentKey!)
        }
        unlockedContentKey = nil
        unlockedWrappedContentKey = nil
    }

    private func beginOperation() -> UInt64 {
        operationEpoch &+= 1
        keyWrapper.cancelAuthentication()
        return operationEpoch
    }

    static func bestEffortZeroize(_ data: inout Data) {
        data.resetBytes(in: data.startIndex..<data.endIndex)
    }
}
