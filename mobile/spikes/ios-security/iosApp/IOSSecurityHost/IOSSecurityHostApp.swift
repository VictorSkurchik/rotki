import SwiftUI
import UIKit

@main
struct IOSSecurityHostApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = HostModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .task {
                    model.establishInstallationContinuity()
                }
                .onReceive(
                    NotificationCenter.default.publisher(
                        for: UIApplication.protectedDataWillBecomeUnavailableNotification
                    )
                ) { _ in
                    model.setPrivacyCovered(true)
                    model.lock(reason: "protected data became unavailable")
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // Do not cancel on `.inactive`: system biometric UI can transiently
            // resign active. The host hides via the system snapshot and locks
            // once it actually backgrounds.
            if phase == .inactive {
                model.setPrivacyCovered(true)
            } else if phase == .background {
                model.setPrivacyCovered(true)
                model.lock(reason: "scene became \(phase)")
            } else if phase == .active {
                model.setPrivacyCovered(false)
            }
        }
    }
}
