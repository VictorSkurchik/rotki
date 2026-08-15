import RotkiShared
import SwiftUI
import XCTest
@testable import RotkiCompanion

final class IOSSecurityCompositionTests: XCTestCase {
    @MainActor
    func testBackgroundCancelsAuthenticationAndFencesLateSuccess() async {
        let facade = restoredFacade()
        let authenticator = DeferredDeviceAuthenticator()
        var deviceAuthenticationSucceededCalls = 0
        let composition = IOSSecurityComposition(
            facade: facade,
            deviceAuthenticator: authenticator,
            deviceAuthenticationSucceeded: {
                deviceAuthenticationSucceededCalls += 1
            }
        )
        defer { composition.close() }

        let authentication = Task { await composition.sceneBecameActive() }
        await authenticator.waitUntilStarted()

        composition.sceneEnteredBackground()
        authenticator.complete(with: true)
        await authentication.value

        XCTAssertEqual(rootStateCode(facade), "device_locked")
        XCTAssertEqual(deviceAuthenticationSucceededCalls, 0)
        XCTAssertEqual(authenticator.cancelCalls, 1)
        XCTAssertEqual(authenticator.authenticateCalls, 1)
        XCTAssertEqual(visibilityStateName(composition.visibility), "BACKGROUND_OR_LOCKED")
    }

    @MainActor
    func testInactiveDefersAcceptedAuthenticationUntilNextActiveWithoutRestarting() async {
        let facade = restoredFacade()
        let authenticator = DeferredDeviceAuthenticator()
        var deviceAuthenticationSucceededCalls = 0
        let composition = IOSSecurityComposition(
            facade: facade,
            deviceAuthenticator: authenticator,
            deviceAuthenticationSucceeded: {
                deviceAuthenticationSucceededCalls += 1
            }
        )
        defer { composition.close() }

        let authentication = Task { await composition.sceneBecameActive() }
        await authenticator.waitUntilStarted()

        composition.sceneBecameInactive()
        await composition.sceneBecameActive()
        composition.sceneBecameInactive()
        authenticator.complete(with: true)
        await authentication.value

        XCTAssertEqual(rootStateCode(facade), "device_locked")
        XCTAssertEqual(authenticator.cancelCalls, 0)
        XCTAssertEqual(authenticator.authenticateCalls, 1)
        XCTAssertEqual(visibilityStateName(composition.visibility), "INACTIVE")

        await composition.sceneBecameActive()

        XCTAssertEqual(rootStateCode(facade), "device_locked")
        XCTAssertEqual(deviceAuthenticationSucceededCalls, 1)
        XCTAssertEqual(authenticator.cancelCalls, 0)
        XCTAssertEqual(authenticator.authenticateCalls, 1)
        XCTAssertEqual(visibilityStateName(composition.visibility), "ACTIVE_FOREGROUND")
    }

    @MainActor
    func testCloseCancelsAuthenticationAndFencesLateSuccess() async {
        let facade = restoredFacade()
        let authenticator = DeferredDeviceAuthenticator()
        let composition = IOSSecurityComposition(
            facade: facade,
            deviceAuthenticator: authenticator
        )

        let authentication = Task { await composition.sceneBecameActive() }
        await authenticator.waitUntilStarted()

        composition.close()
        authenticator.complete(with: true)
        await authentication.value

        XCTAssertEqual(rootStateCode(facade), "device_locked")
        XCTAssertEqual(authenticator.cancelCalls, 1)
        XCTAssertEqual(authenticator.authenticateCalls, 1)
    }

    @MainActor
    func testScenePhaseDispatcherRoutesKnownPhasesWithoutViewIntrospection() {
        var events: [String] = []
        for phase in [ScenePhase.active, .inactive, .background] {
            dispatchCompanionScenePhase(
                phase,
                onActive: { events.append("active") },
                onInactive: { events.append("inactive") },
                onBackground: { events.append("background") }
            )
        }

        XCTAssertEqual(events, ["active", "inactive", "background"])
    }

    @MainActor
    private func restoredFacade() -> CompanionFacade {
        CompanionFacade.companion.restorePaired(
            snapshotCoverage: SnapshotCoverageAbsent.shared
        )
    }

    @MainActor
    private func rootStateCode(_ facade: CompanionFacade) -> String? {
        (facade.status.value as? CompanionStatus)?.rootState.code
    }

    @MainActor
    private func visibilityStateName(
        _ visibility: ApplicationVisibilityController
    ) -> String? {
        (visibility.state.value as? ApplicationVisibilityState)?.name
    }
}

@MainActor
private final class DeferredDeviceAuthenticator: IOSDeviceAuthenticating {
    private var didStart = false
    private var startWaiters: [CheckedContinuation<Void, Never>] = []
    private var resultContinuation: CheckedContinuation<Bool, Never>?

    private(set) var cancelCalls = 0
    private(set) var authenticateCalls = 0

    func authenticate() async -> Bool {
        authenticateCalls += 1
        didStart = true
        let waiters = startWaiters
        startWaiters.removeAll()
        waiters.forEach { $0.resume() }
        return await withCheckedContinuation { continuation in
            resultContinuation = continuation
        }
    }

    func cancel() {
        cancelCalls += 1
    }

    func waitUntilStarted() async {
        guard !didStart else { return }
        await withCheckedContinuation { continuation in
            startWaiters.append(continuation)
        }
    }

    func complete(with accepted: Bool) {
        guard let continuation = resultContinuation else {
            XCTFail("Expected a pending device-authentication result")
            return
        }
        resultContinuation = nil
        continuation.resume(returning: accepted)
    }
}
