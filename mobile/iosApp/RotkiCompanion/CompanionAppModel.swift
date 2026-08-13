import Combine
import RotkiShared

enum CompanionLaunchMode {
    static let tabShellArgument = "--rotki-ui-tabs"

    case sharedState
    case tabShell
}

enum CompanionPairingShellState: Equatable {
    case preparingScanner
    case connecting
    case needsAttention
}

enum CompanionRecoveryShellState: Equatable {
    case deviceLocked
    case engineLocked
    case profileMismatch
    case incompatible
    case revoked
    case unreachable
    case unavailable
}

enum CompanionRoute: Equatable {
    case unpaired
    case pairing(CompanionPairingShellState)
    case recovery(CompanionRecoveryShellState)
    case tabShell
}

/// A deliberately small SwiftUI boundary around the shared KMP state machines.
///
/// StateFlow's current value is available to Swift without an extra dependency. The app samples
/// it after local actions and when the scene becomes active. A cancellable collector can replace
/// this sampling seam when the planned iOS interop layer is introduced.
@MainActor
final class CompanionAppModel: ObservableObject {
    @Published private(set) var route: CompanionRoute

    private(set) var sharedRootStateCode: String?
    private(set) var sharedPairingStateCode: String?

    private let launchMode: CompanionLaunchMode
    private let facade: CompanionFacade
    private let pairingFlow: PairingFlow

    init(
        launchMode: CompanionLaunchMode = .sharedState,
        facade: CompanionFacade = CompanionFacade()
    ) {
        self.launchMode = launchMode
        self.facade = facade
        self.pairingFlow = facade.pairingFlow()
        self.route = launchMode == .tabShell ? .tabShell : .unpaired
        refreshFromShared()
    }

    func beginPairingSetup() {
        guard launchMode == .sharedState else { return }
        pairingFlow.startScanning()
        refreshFromShared()
    }

    func cancelPairingSetup() {
        guard launchMode == .sharedState else { return }
        pairingFlow.reset()
        refreshFromShared()
    }

    func sceneBecameActive() {
        refreshFromShared()
    }

    private func refreshFromShared() {
        sharedRootStateCode = (facade.status.value as? CompanionStatus)?.rootState.code
        sharedPairingStateCode = (pairingFlow.presentation.value as? PairingPresentation)?.state.code

        guard launchMode == .sharedState else {
            route = .tabShell
            return
        }

        route = Self.route(
            rootStateCode: sharedRootStateCode,
            pairingStateCode: sharedPairingStateCode
        )
    }

    static func route(
        rootStateCode: String?,
        pairingStateCode: String?
    ) -> CompanionRoute {
        switch rootStateCode {
        case "unpaired":
            switch pairingStateCode {
            case "scanning":
                .pairing(.preparingScanner)
            case "connecting":
                .pairing(.connecting)
            case "camera_denied", "scanner_unavailable", "invalid_qr", "expired_qr":
                .pairing(.needsAttention)
            default:
                .unpaired
            }
        case "connecting":
            .pairing(.connecting)
        case "online", "refreshing", "degraded":
            .tabShell
        case "device_locked":
            .recovery(.deviceLocked)
        case "engine_locked":
            .recovery(.engineLocked)
        case "profile_mismatch":
            .recovery(.profileMismatch)
        case "incompatible":
            .recovery(.incompatible)
        case "revoked":
            .recovery(.revoked)
        case "unreachable":
            .recovery(.unreachable)
        default:
            .recovery(.unavailable)
        }
    }
}
