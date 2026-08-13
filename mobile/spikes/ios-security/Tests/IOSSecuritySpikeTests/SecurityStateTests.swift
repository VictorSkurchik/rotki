import XCTest
@testable import IOSSecuritySpike

final class SecurityStateTests: XCTestCase {
    func testHappyPathTransitionsThroughLockAndUnlock() {
        var machine = IOSSecurityStateMachine()

        XCTAssertEqual(
            machine.apply(.preparationReady(hasSnapshot: false)),
            .readyForFirstSnapshot
        )
        XCTAssertEqual(machine.apply(.firstSnapshotSealed), .unlocked)
        XCTAssertEqual(machine.apply(.applicationLocked), .locked(.applicationLocked))
        XCTAssertEqual(machine.apply(.unlockSucceeded), .unlocked)
    }

    func testCancellationAndLockoutRetainLockedState() {
        var machine = IOSSecurityStateMachine()
        machine.apply(.preparationReady(hasSnapshot: true))

        XCTAssertEqual(machine.apply(.unlockCancelled), .locked(.userCancelled))
        XCTAssertEqual(machine.apply(.biometryLockedOut), .locked(.biometryLockout))
    }

    func testInvalidationAndTamperAreDistinctTerminalStates() {
        var invalidated = IOSSecurityStateMachine(state: .locked(.authenticationRequired))
        var tampered = invalidated

        XCTAssertEqual(
            invalidated.apply(.keyInvalidated(.biometricEnrollmentChanged)),
            .requiresPairing(.biometricEnrollmentChanged)
        )
        XCTAssertEqual(tampered.apply(.snapshotTampered), .corruptSnapshot)
    }

    func testBlockedAndDestroyedTransitionsAreTyped() {
        var machine = IOSSecurityStateMachine()

        XCTAssertEqual(
            machine.apply(.preparationBlocked(.biometryNotEnrolled)),
            .blocked(.biometryNotEnrolled)
        )
        XCTAssertEqual(machine.apply(.destroyed), .unprepared)
    }
}
