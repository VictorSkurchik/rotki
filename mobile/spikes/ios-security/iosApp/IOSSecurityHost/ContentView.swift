import SwiftUI

struct ContentView: View {
    @ObservedObject var model: HostModel

    var body: some View {
        ZStack {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                    GroupBox("Current state") {
                        Text(model.stateText)
                            .font(.system(.body, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    GroupBox("Last outcome") {
                        Text(model.statusText)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    VStack(spacing: 10) {
                        actionButton("1. Device signer probe", action: model.probeSigner)
                        actionButton("2. Prepare biometric protection", action: model.prepare)
                        actionButton("3. Seal known fixture", action: model.sealFixture)
                        actionButton("4. Lock", action: { model.lock(reason: "button") })
                        actionButton("5. Unlock", action: model.unlock)
                        actionButton("6. Destroy spike material", role: .destructive, action: model.destroy)
                        actionButton(
                            "7. Re-check installation continuity",
                            action: model.establishInstallationContinuity
                        )
                    }
                    .disabled(model.isBusy)

                    Text(
                        "No snapshot plaintext or key bytes are shown. Inactive covers "
                        + "the UI; background and protected-data-unavailable invoke lock()."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    }
                    .padding()
                }
                .navigationTitle("iOS Security P0.3")
            }
            if model.isPrivacyCovered {
                Color(uiColor: .systemBackground)
                    .ignoresSafeArea()
                    .overlay {
                        Label("Security spike hidden", systemImage: "lock.fill")
                            .font(.headline)
                    }
                    .accessibilityIdentifier("privacy-cover")
            }
        }
    }

    private func actionButton(
        _ title: String,
        role: ButtonRole? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(title, role: role, action: action)
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity)
    }
}
