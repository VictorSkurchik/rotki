import SwiftUI

@main
@MainActor
struct RotkiCompanionApp: App {
    @StateObject private var model: CompanionAppModel

    init() {
        #if DEBUG
        let launchMode: CompanionLaunchMode = ProcessInfo.processInfo.arguments.contains(
            CompanionLaunchMode.tabShellArgument
        ) ? .tabShell : .sharedState
        #else
        let launchMode: CompanionLaunchMode = .sharedState
        #endif
        _model = StateObject(wrappedValue: CompanionAppModel(launchMode: launchMode))
    }

    var body: some Scene {
        WindowGroup {
            CompanionRootView(model: model)
        }
    }
}
