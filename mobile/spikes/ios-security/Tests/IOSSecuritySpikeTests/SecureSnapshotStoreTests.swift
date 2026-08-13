import Foundation
import XCTest
@testable import IOSSecuritySpike

private final class MemorySnapshotStorage: SnapshotStorage {
    var data: Data?
    var deleteError: Error?

    var exists: Bool { data != nil }

    func read() throws -> Data {
        guard let data else { throw CocoaError(.fileNoSuchFile) }
        return data
    }

    func writeAtomically(_ data: Data) throws {
        self.data = data
    }

    func delete() throws {
        if let deleteError { throw deleteError }
        data = nil
    }
}

private enum FakeSignerError: Error {
    case notImplemented
    case deletionFailed
    case markerDeletionFailed
}

private final class FakeDeviceProofSigner: DeviceProofSigning {
    var deleteCount = 0
    var creationAuthorizations: [Bool] = []
    var deleteError: Error?

    func sign(message: Data) throws -> DeviceProof {
        throw FakeSignerError.notImplemented
    }

    func deleteKey() throws {
        deleteCount += 1
        if let deleteError { throw deleteError }
    }

    func setKeyCreationAuthorized(_ authorized: Bool) {
        creationAuthorizations.append(authorized)
    }
}

@MainActor
private final class FakeSnapshotKeyWrapper: SnapshotKeyWrapping {
    var preparation: SnapshotKeyPreparationOutcome = .ready(created: true)
    var unwrapOverride: SnapshotKeyUnwrapOutcome?
    var deleteCount = 0
    var lastPrepareHadProtectedSnapshot: Bool?
    var cancelCount = 0
    var deleteError: Error?
    var prepareContinuation: CheckedContinuation<SnapshotKeyPreparationOutcome, Never>?
    var unwrapContinuation: CheckedContinuation<SnapshotKeyUnwrapOutcome, Never>?

    func prepare(
        hasProtectedSnapshot: Bool,
        localizedReason: String
    ) async -> SnapshotKeyPreparationOutcome {
        lastPrepareHadProtectedSnapshot = hasProtectedSnapshot
        if preparation == .failed("suspend") {
            return await withCheckedContinuation { prepareContinuation = $0 }
        }
        return preparation
    }

    func wrap(_ contentKey: Data) throws -> Data {
        Data(contentKey.reversed())
    }

    func unwrap(
        _ wrappedContentKey: Data,
        localizedReason: String
    ) async -> SnapshotKeyUnwrapOutcome {
        if unwrapOverride == .failed("suspend") {
            return await withCheckedContinuation { unwrapContinuation = $0 }
        }
        return unwrapOverride ?? .opened(Data(wrappedContentKey.reversed()))
    }

    func cancelAuthentication() {
        cancelCount += 1
    }

    func deleteKey() throws {
        deleteCount += 1
        if let deleteError { throw deleteError }
    }
}

private final class MemoryMarkerStore: InstallationMarkerStoring {
    var marker: Data?
    var deleteError: Error?

    func readMarker() throws -> Data? { marker }
    func writeMarker(_ marker: Data) throws { self.marker = marker }
    func deleteMarker() throws {
        if let deleteError { throw deleteError }
        marker = nil
    }
}

@MainActor
private func makeHarness(
    signer: FakeDeviceProofSigner,
    wrapper: FakeSnapshotKeyWrapper,
    storage: MemorySnapshotStorage,
    containerMarker: MemoryMarkerStore = MemoryMarkerStore(),
    keychainMarker: MemoryMarkerStore = MemoryMarkerStore()
) -> IOSSecurityPhysicalHarness {
    let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)
    let guardObject = InstallationContinuityGuard(
        containerMarkerStore: containerMarker,
        keychainMarkerStore: keychainMarker,
        signer: signer,
        snapshotStore: store,
        makeMarker: { Data(repeating: 0x44, count: 32) }
    )
    return IOSSecurityPhysicalHarness(
        signer: signer,
        snapshotStore: store,
        continuityGuard: guardObject
    )
}

@MainActor
final class SecureSnapshotStoreTests: XCTestCase {
    func testSealLockAndUnlockRoundTrip() async {
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)
        let plaintext = Data("private offline snapshot".utf8)

        let preparation = await store.prepare(localizedReason: "Prepare test key")
        XCTAssertEqual(preparation, .readyForFirstSnapshot)
        XCTAssertEqual(store.seal(plaintext), .sealed)
        XCTAssertEqual(store.state, .unlocked)

        store.lock()
        XCTAssertEqual(store.state, .locked(.applicationLocked))
        let unlock = await store.unlock(localizedReason: "Unlock test snapshot")
        XCTAssertEqual(unlock, .opened(plaintext))
        XCTAssertEqual(store.state, .unlocked)
    }

    func testTamperedCiphertextFailsClosedAsCorrupt() async throws {
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)

        _ = await store.prepare(localizedReason: "Prepare")
        XCTAssertEqual(store.seal(Data("snapshot".utf8)), .sealed)
        store.lock()

        var tampered = try XCTUnwrap(storage.data)
        tampered[tampered.count - 1] ^= 0x01
        storage.data = tampered

        let unlock = await store.unlock(localizedReason: "Unlock")
        XCTAssertEqual(unlock, .corrupt)
        XCTAssertEqual(store.state, .corruptSnapshot)
    }

    func testBiometryLockoutKeepsWrappedMaterial() async {
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)

        _ = await store.prepare(localizedReason: "Prepare")
        _ = store.seal(Data("snapshot".utf8))
        store.lock()
        let before = storage.data
        wrapper.unwrapOverride = .lockedOut

        let unlock = await store.unlock(localizedReason: "Unlock")
        XCTAssertEqual(unlock, .lockedOut)
        XCTAssertEqual(store.state, .locked(.biometryLockout))
        XCTAssertEqual(storage.data, before)
        XCTAssertEqual(wrapper.deleteCount, 0)
    }

    func testInvalidatedWrappingKeyPurgesSnapshotAndRequiresPairing() async {
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)

        _ = await store.prepare(localizedReason: "Prepare")
        _ = store.seal(Data("snapshot".utf8))
        store.lock()
        wrapper.unwrapOverride = .invalidated(.biometricEnrollmentChanged)

        let unlock = await store.unlock(localizedReason: "Unlock")
        XCTAssertEqual(unlock, .requiresPairing(.biometricEnrollmentChanged))
        XCTAssertEqual(store.state, .requiresPairing(.biometricEnrollmentChanged))
        XCTAssertFalse(storage.exists)
        XCTAssertEqual(wrapper.deleteCount, 1)
    }

    func testPhysicalHarnessDeletesProofIdentityWhenSnapshotKeyIsInvalidated() async {
        let signer = FakeDeviceProofSigner()
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let continuityMarker = Data(repeating: 0x22, count: 32)
        let containerMarker = MemoryMarkerStore()
        let keychainMarker = MemoryMarkerStore()
        containerMarker.marker = continuityMarker
        keychainMarker.marker = continuityMarker
        let harness = makeHarness(
            signer: signer,
            wrapper: wrapper,
            storage: storage,
            containerMarker: containerMarker,
            keychainMarker: keychainMarker
        )

        _ = harness.establishInstallationContinuity()
        _ = await harness.prepareSnapshotProtection(localizedReason: "Prepare")
        _ = harness.sealSnapshot(Data("snapshot".utf8))
        harness.lock()
        wrapper.unwrapOverride = .invalidated(.biometricEnrollmentChanged)

        let unlock = await harness.unlockSnapshot(localizedReason: "Unlock")

        XCTAssertEqual(unlock, .requiresPairing(.biometricEnrollmentChanged))
        XCTAssertEqual(signer.deleteCount, 1)
        XCTAssertFalse(storage.exists)
        XCTAssertNil(containerMarker.marker)
        XCTAssertNil(keychainMarker.marker)
        XCTAssertEqual(signer.creationAuthorizations.last, false)
        let retry = await harness.prepareSnapshotProtection(localizedReason: "Retry")
        XCTAssertEqual(
            retry,
            .failed("installation continuity has not been established")
        )
        XCTAssertEqual(
            harness.exerciseDeviceSigner(message: Data("retry".utf8)),
            .failed("installation continuity has not been established")
        )
        XCTAssertEqual(
            harness.establishInstallationContinuity(),
            .initializedFreshInstallation(replacedDiscontinuousState: false)
        )
        XCTAssertEqual(signer.creationAuthorizations.last, true)
    }

    func testPhysicalHarnessDeletesProofIdentityWhenPreparationFindsLostKey() async {
        let signer = FakeDeviceProofSigner()
        let wrapper = FakeSnapshotKeyWrapper()
        wrapper.preparation = .invalidated(.wrappingKeyMissing)
        let storage = MemorySnapshotStorage()
        storage.data = Data("protected material marker".utf8)
        let marker = Data(repeating: 0x22, count: 32)
        let containerMarker = MemoryMarkerStore()
        let keychainMarker = MemoryMarkerStore()
        containerMarker.marker = marker
        keychainMarker.marker = marker
        let harness = makeHarness(
            signer: signer,
            wrapper: wrapper,
            storage: storage,
            containerMarker: containerMarker,
            keychainMarker: keychainMarker
        )

        _ = harness.establishInstallationContinuity()
        let preparation = await harness.prepareSnapshotProtection(
            localizedReason: "Prepare"
        )

        XCTAssertEqual(preparation, .requiresPairing(.wrappingKeyMissing))
        XCTAssertEqual(wrapper.lastPrepareHadProtectedSnapshot, true)
        XCTAssertEqual(signer.deleteCount, 1)
        XCTAssertFalse(storage.exists)
    }

    func testPasscodeRemovalWithSnapshotPurgesAndRequiresPairing() async {
        let wrapper = FakeSnapshotKeyWrapper()
        wrapper.preparation = .blocked(.passcodeRequired)
        let storage = MemorySnapshotStorage()
        storage.data = Data("protected material marker".utf8)
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)

        let preparation = await store.prepare(localizedReason: "Prepare")

        XCTAssertEqual(preparation, .requiresPairing(.devicePasscodeRemoved))
        XCTAssertFalse(storage.exists)
        XCTAssertEqual(wrapper.deleteCount, 1)
    }

    func testExpectedWrappingKeyLossBeforeFirstSnapshotRequiresPairing() async {
        let signer = FakeDeviceProofSigner()
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let marker = Data(repeating: 0x22, count: 32)
        let containerMarker = MemoryMarkerStore()
        let keychainMarker = MemoryMarkerStore()
        containerMarker.marker = marker
        keychainMarker.marker = marker
        let harness = makeHarness(
            signer: signer,
            wrapper: wrapper,
            storage: storage,
            containerMarker: containerMarker,
            keychainMarker: keychainMarker
        )

        _ = harness.establishInstallationContinuity()
        let initialPreparation = await harness.prepareSnapshotProtection(
            localizedReason: "Create wrapper"
        )
        XCTAssertEqual(initialPreparation, .readyForFirstSnapshot)
        harness.lock()
        wrapper.preparation = .invalidated(.wrappingKeyMissing)

        let retry = await harness.prepareSnapshotProtection(
            localizedReason: "Find expected wrapper"
        )

        XCTAssertEqual(retry, .requiresPairing(.wrappingKeyMissing))
        XCTAssertFalse(storage.exists)
        XCTAssertEqual(wrapper.deleteCount, 1)
        XCTAssertEqual(signer.deleteCount, 1)
        XCTAssertNil(containerMarker.marker)
        XCTAssertNil(keychainMarker.marker)
        XCTAssertEqual(signer.creationAuthorizations.last, false)
    }

    func testLockRevokesFirstSnapshotAuthorization() async {
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)

        let preparation = await store.prepare(localizedReason: "Prepare")
        XCTAssertEqual(preparation, .readyForFirstSnapshot)
        store.lock()

        XCTAssertEqual(store.state, .locked(.applicationLocked))
        XCTAssertEqual(store.seal(Data("must not seal".utf8)), .locked)
        XCTAssertGreaterThan(wrapper.cancelCount, 0)
    }

    func testLateUnlockCompletionCannotReopenAfterLock() async {
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)
        _ = await store.prepare(localizedReason: "Prepare")
        _ = store.seal(Data("snapshot".utf8))
        store.lock()
        wrapper.unwrapOverride = .failed("suspend")

        let task = Task { await store.unlock(localizedReason: "Unlock") }
        await Task.yield()
        store.lock()
        wrapper.unwrapContinuation?.resume(
            returning: .opened(Data(repeating: 0, count: 32))
        )

        let outcome = await task.value
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertEqual(store.state, .locked(.applicationLocked))
    }

    func testLatePrepareCompletionCannotAuthorizeSealAfterLock() async {
        let wrapper = FakeSnapshotKeyWrapper()
        wrapper.preparation = .failed("suspend")
        let store = SecureSnapshotStore(
            keyWrapper: wrapper,
            storage: MemorySnapshotStorage()
        )

        let task = Task { await store.prepare(localizedReason: "Prepare") }
        await Task.yield()
        store.lock()
        wrapper.prepareContinuation?.resume(returning: .ready(created: true))

        let outcome = await task.value
        XCTAssertEqual(outcome, .cancelled)
        XCTAssertEqual(store.state, .unprepared)
        XCTAssertEqual(store.seal(Data("must not seal".utf8)), .locked)
    }

    func testCleanupFailureIsSurfacedAndAllDeletesAreAttempted() async {
        let wrapper = FakeSnapshotKeyWrapper()
        wrapper.unwrapOverride = .invalidated(.wrappingKeyMissing)
        wrapper.deleteError = FakeSignerError.deletionFailed
        let storage = MemorySnapshotStorage()
        let store = SecureSnapshotStore(keyWrapper: wrapper, storage: storage)
        _ = await store.prepare(localizedReason: "Prepare")
        _ = store.seal(Data("snapshot".utf8))
        store.lock()

        let unlock = await store.unlock(localizedReason: "Unlock")

        guard case let .cleanupIncomplete(message) = unlock else {
            return XCTFail("expected cleanupIncomplete, got \(unlock)")
        }
        XCTAssertTrue(message.contains("wrapping-key deletion failed"))
        XCTAssertFalse(storage.exists)
        XCTAssertEqual(wrapper.deleteCount, 1)
    }

    func testContinuityExistingMarkersPreserveInstallation() {
        let marker = Data(repeating: 0x22, count: 32)
        let container = MemoryMarkerStore()
        let keychain = MemoryMarkerStore()
        container.marker = marker
        keychain.marker = marker
        let signer = FakeDeviceProofSigner()
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let harness = makeHarness(
            signer: signer,
            wrapper: wrapper,
            storage: storage,
            containerMarker: container,
            keychainMarker: keychain
        )

        XCTAssertEqual(harness.establishInstallationContinuity(), .existingInstallation)
        XCTAssertEqual(signer.deleteCount, 0)
        XCTAssertEqual(signer.creationAuthorizations.last, false)
    }

    func testMissingContainerMarkerPurgesSurvivingKeychainState() {
        let container = MemoryMarkerStore()
        let keychain = MemoryMarkerStore()
        keychain.marker = Data(repeating: 0x22, count: 32)
        let signer = FakeDeviceProofSigner()
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        storage.data = Data("old snapshot".utf8)
        let harness = makeHarness(
            signer: signer,
            wrapper: wrapper,
            storage: storage,
            containerMarker: container,
            keychainMarker: keychain
        )

        XCTAssertEqual(
            harness.establishInstallationContinuity(),
            .initializedFreshInstallation(replacedDiscontinuousState: true)
        )
        XCTAssertEqual(container.marker, Data(repeating: 0x44, count: 32))
        XCTAssertEqual(keychain.marker, container.marker)
        XCTAssertEqual(signer.deleteCount, 1)
        XCTAssertEqual(wrapper.deleteCount, 1)
        XCTAssertFalse(storage.exists)
        XCTAssertEqual(signer.creationAuthorizations.last, true)
    }

    func testExplicitDestroyRemovesMarkersAndNextLaunchIsFresh() {
        let marker = Data(repeating: 0x22, count: 32)
        let container = MemoryMarkerStore()
        let keychain = MemoryMarkerStore()
        container.marker = marker
        keychain.marker = marker
        let signer = FakeDeviceProofSigner()
        let wrapper = FakeSnapshotKeyWrapper()
        let storage = MemorySnapshotStorage()
        let harness = makeHarness(
            signer: signer,
            wrapper: wrapper,
            storage: storage,
            containerMarker: container,
            keychainMarker: keychain
        )
        XCTAssertEqual(harness.establishInstallationContinuity(), .existingInstallation)

        XCTAssertEqual(harness.destroyAllSpikeMaterial(), .destroyed)
        XCTAssertNil(container.marker)
        XCTAssertNil(keychain.marker)
        XCTAssertEqual(
            harness.establishInstallationContinuity(),
            .initializedFreshInstallation(replacedDiscontinuousState: false)
        )
        XCTAssertEqual(signer.creationAuthorizations.last, true)
    }

    func testExplicitDestroySurfacesMarkerDeletionFailure() {
        let container = MemoryMarkerStore()
        let keychain = MemoryMarkerStore()
        container.marker = Data(repeating: 0x22, count: 32)
        keychain.marker = container.marker
        container.deleteError = FakeSignerError.markerDeletionFailed
        let harness = makeHarness(
            signer: FakeDeviceProofSigner(),
            wrapper: FakeSnapshotKeyWrapper(),
            storage: MemorySnapshotStorage(),
            containerMarker: container,
            keychainMarker: keychain
        )
        _ = harness.establishInstallationContinuity()

        guard case let .cleanupIncomplete(messages) = harness.destroyAllSpikeMaterial() else {
            return XCTFail("expected marker cleanup failure")
        }
        XCTAssertTrue(messages.contains(where: { $0.contains("container marker deletion") }))
        XCTAssertNil(keychain.marker)
    }

    func testBestEffortZeroizeOverwritesPassedBuffer() {
        var key = Data(repeating: 0xa5, count: 32)

        SecureSnapshotStore.bestEffortZeroize(&key)

        XCTAssertEqual(key, Data(repeating: 0, count: 32))
    }

    func testAtomicFileStorageReplacesAndExcludesFileFromBackup() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("rotki-ios-security-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("snapshot.rkp03")
        let storage = AtomicSnapshotFileStorage(fileURL: url)

        try storage.writeAtomically(Data("one".utf8))
        try storage.writeAtomically(Data("two".utf8))

        XCTAssertEqual(try storage.read(), Data("two".utf8))
        XCTAssertEqual(
            try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup,
            true
        )
    }
}
