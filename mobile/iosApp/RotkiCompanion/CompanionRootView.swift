import SwiftUI

struct CompanionRootView: View {
    @ObservedObject var model: CompanionAppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.route {
            case .unpaired:
                UnpairedView(onBeginPairing: model.beginPairingSetup)
            case let .pairing(state):
                PairingSkeletonView(
                    state: state,
                    onCancel: model.cancelPairingSetup
                )
            case let .recovery(state):
                RecoveryShellView(state: state)
            case .tabShell:
                CompanionTabShell()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                model.sceneBecameActive()
            case .inactive:
                model.sceneBecameInactive()
            case .background:
                model.sceneEnteredBackground()
            @unknown default:
                model.sceneEnteredBackground()
            }
        }
    }
}

private struct RecoveryShellView: View {
    let state: CompanionRecoveryShellState

    var body: some View {
        ContentUnavailableView(
            title,
            systemImage: symbolName,
            description: Text(message)
        )
        .accessibilityIdentifier("recovery.screen")
    }

    private var title: String {
        switch state {
        case .deviceLocked: "Portfolio locked"
        case .engineLocked: "Open your Profile in rotki"
        case .profileMismatch: "A different Profile is open"
        case .incompatible: "rotki needs an update"
        case .revoked: "Device access was revoked"
        case .unreachable: "rotki is out of reach"
        case .unavailable: "Portfolio unavailable"
        }
    }

    private var message: String {
        switch state {
        case .deviceLocked:
            "Device authentication is required before portfolio data can be shown."
        case .engineLocked:
            "Open the paired Profile on your rotki Engine, then return here."
        case .profileMismatch:
            "Open the Profile paired with this device before trying again."
        case .incompatible:
            "This Engine does not support the Companion contract required by this app."
        case .revoked:
            "Pair this installation again from the Profile that should authorize it."
        case .unreachable:
            "Reconnect to your Engine before continuing in this shell build."
        case .unavailable:
            "The shared state could not be read safely. No portfolio content is shown."
        }
    }

    private var symbolName: String {
        switch state {
        case .deviceLocked: "lock.fill"
        case .engineLocked: "desktopcomputer"
        case .profileMismatch: "person.2.slash"
        case .incompatible: "arrow.up.circle"
        case .revoked: "xmark.shield"
        case .unreachable: "wifi.slash"
        case .unavailable: "exclamationmark.shield"
        }
    }
}

private struct UnpairedView: View {
    let onBeginPairing: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 28) {
                Spacer()

                Image(systemName: "lock.shield")
                    .font(.system(size: 58, weight: .medium))
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)

                VStack(spacing: 10) {
                    Text("Your portfolio, close at hand")
                        .font(.largeTitle.bold())
                        .multilineTextAlignment(.center)

                    Text("Pair this iPhone with your own rotki instance to open a read-only companion view.")
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                Button(action: onBeginPairing) {
                    Text("Set up pairing")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("pairing.start")

                Text("This build does not connect or store portfolio data yet.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                Spacer()
            }
            .padding(24)
            .background(Color(uiColor: .systemBackground))
            .navigationTitle("rotki")
            .navigationBarTitleDisplayMode(.inline)
        }
        .accessibilityIdentifier("unpaired.screen")
    }
}

private struct PairingSkeletonView: View {
    let state: CompanionPairingShellState
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemGroupedBackground))
                        .frame(height: 280)
                        .overlay {
                            Image(systemName: symbolName)
                                .font(.system(size: 64, weight: .regular))
                                .foregroundStyle(.secondary)
                                .accessibilityHidden(true)
                        }

                    VStack(spacing: 8) {
                        Text(title)
                            .font(.title2.bold())
                            .multilineTextAlignment(.center)

                        Text(message)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }

                    Label(
                        "No camera, network, or device security is activated by this screen.",
                        systemImage: "info.circle"
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
                .padding(24)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Pair with rotki")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if state != .connecting {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Cancel", action: onCancel)
                            .accessibilityIdentifier("pairing.cancel")
                    }
                }
            }
        }
        .accessibilityIdentifier("pairing.skeleton")
    }

    private var symbolName: String {
        switch state {
        case .preparingScanner:
            "qrcode.viewfinder"
        case .connecting:
            "arrow.triangle.2.circlepath"
        case .needsAttention:
            "exclamationmark.triangle"
        }
    }

    private var title: String {
        switch state {
        case .preparingScanner:
            "Keep your pairing code ready"
        case .connecting:
            "Connection setup"
        case .needsAttention:
            "Pairing needs attention"
        }
    }

    private var message: String {
        switch state {
        case .preparingScanner:
            "Open rotki on your computer and create a pairing QR code. QR scanning is not included in this iOS build yet."
        case .connecting:
            "The shared flow accepted a pairing code. Network connection is not included in this iOS build yet."
        case .needsAttention:
            "Return to the start and try again when iOS QR scanning is available."
        }
    }
}

private enum CompanionDestination: Hashable, CaseIterable {
    case overview
    case portfolio
    case history
    case sources

    var title: String {
        switch self {
        case .overview: "Overview"
        case .portfolio: "Portfolio"
        case .history: "History"
        case .sources: "Sources"
        }
    }

    var symbolName: String {
        switch self {
        case .overview: "house"
        case .portfolio: "chart.pie"
        case .history: "clock.arrow.circlepath"
        case .sources: "externaldrive.connected.to.line.below"
        }
    }

    var explanation: String {
        switch self {
        case .overview:
            "Your high-level portfolio summary will appear here."
        case .portfolio:
            "Assets and liabilities will appear here."
        case .history:
            "Recent portfolio activity will appear here."
        case .sources:
            "Connection health for your portfolio sources will appear here."
        }
    }
}

private struct CompanionTabShell: View {
    @State private var selection: CompanionDestination = .overview

    var body: some View {
        TabView(selection: $selection) {
            ForEach(CompanionDestination.allCases, id: \.self) { destination in
                NavigationStack {
                    ContentUnavailableView(
                        destination.title,
                        systemImage: destination.symbolName,
                        description: Text(destination.explanation)
                    )
                    .navigationTitle(destination.title)
                }
                .tag(destination)
                .tabItem {
                    Label(destination.title, systemImage: destination.symbolName)
                }
            }
        }
        .accessibilityIdentifier("companion.tabs")
    }
}

#Preview("Unpaired") {
    CompanionRootView(model: CompanionAppModel())
}

#Preview("Four destinations") {
    CompanionRootView(model: CompanionAppModel(launchMode: .tabShell))
}
