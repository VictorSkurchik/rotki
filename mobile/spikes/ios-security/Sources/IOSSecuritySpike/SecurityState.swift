import Foundation

public enum SecurityAvailabilityBlock: Equatable {
    case secureEnclaveUnavailable
    case passcodeRequired
    case biometryUnavailable
    case biometryNotEnrolled
}

public enum SnapshotLockReason: Equatable {
    case authenticationRequired
    case applicationLocked
    case userCancelled
    case biometryLockout
}

public enum PairingLossReason: Equatable {
    case wrappingKeyMissing
    case biometricEnrollmentChanged
    case devicePasscodeRemoved
}

public enum IOSSecurityState: Equatable {
    case unprepared
    case readyForFirstSnapshot
    case locked(SnapshotLockReason)
    case unlocked
    case blocked(SecurityAvailabilityBlock)
    case requiresPairing(PairingLossReason)
    case corruptSnapshot
}

public enum IOSSecurityEvent: Equatable {
    case preparationReady(hasSnapshot: Bool)
    case preparationBlocked(SecurityAvailabilityBlock)
    case firstSnapshotSealed
    case unlockSucceeded
    case unlockCancelled
    case biometryLockedOut
    case applicationLocked
    case keyInvalidated(PairingLossReason)
    case snapshotTampered
    case destroyed
}

public struct IOSSecurityStateMachine: Equatable {
    public private(set) var state: IOSSecurityState

    public init(state: IOSSecurityState = .unprepared) {
        self.state = state
    }

    @discardableResult
    public mutating func apply(_ event: IOSSecurityEvent) -> IOSSecurityState {
        switch event {
        case let .preparationReady(hasSnapshot):
            state = hasSnapshot
                ? .locked(.authenticationRequired)
                : .readyForFirstSnapshot
        case let .preparationBlocked(reason):
            state = .blocked(reason)
        case .firstSnapshotSealed, .unlockSucceeded:
            state = .unlocked
        case .unlockCancelled:
            state = .locked(.userCancelled)
        case .biometryLockedOut:
            state = .locked(.biometryLockout)
        case .applicationLocked:
            if state == .unlocked || state == .readyForFirstSnapshot {
                state = .locked(.applicationLocked)
            }
        case let .keyInvalidated(reason):
            state = .requiresPairing(reason)
        case .snapshotTampered:
            state = .corruptSnapshot
        case .destroyed:
            state = .unprepared
        }
        return state
    }
}

public enum SnapshotKeyPreparationOutcome: Equatable {
    case ready(created: Bool)
    case cancelled
    case lockedOut
    case blocked(SecurityAvailabilityBlock)
    case invalidated(PairingLossReason)
    case failed(String)
}

public enum SnapshotKeyUnwrapOutcome: Equatable {
    case opened(Data)
    case cancelled
    case lockedOut
    case blocked(SecurityAvailabilityBlock)
    case invalidated(PairingLossReason)
    case failed(String)
}

@MainActor
public protocol SnapshotKeyWrapping: AnyObject {
    func prepare(
        hasProtectedSnapshot: Bool,
        localizedReason: String
    ) async -> SnapshotKeyPreparationOutcome
    func wrap(_ contentKey: Data) throws -> Data
    func unwrap(_ wrappedContentKey: Data, localizedReason: String) async -> SnapshotKeyUnwrapOutcome
    func cancelAuthentication()
    func deleteKey() throws
}

public enum SnapshotPrepareOutcome: Equatable {
    case readyForFirstSnapshot
    case lockedSnapshotAvailable
    case cancelled
    case lockedOut
    case blocked(SecurityAvailabilityBlock)
    case requiresPairing(PairingLossReason)
    case cleanupIncomplete(String)
    case failed(String)
}

public enum SnapshotSealOutcome: Equatable {
    case sealed
    case locked
    case failed(String)
}

public enum SnapshotUnlockOutcome: Equatable {
    case opened(Data)
    case noSnapshot
    case cancelled
    case lockedOut
    case blocked(SecurityAvailabilityBlock)
    case requiresPairing(PairingLossReason)
    case cleanupIncomplete(String)
    case corrupt
    case failed(String)
}

public enum SnapshotDestroyOutcome: Equatable {
    case destroyed
    case cleanupIncomplete([String])
}
