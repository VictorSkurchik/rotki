import Foundation
import Security

public protocol InstallationMarkerStoring: AnyObject {
    func readMarker() throws -> Data?
    func writeMarker(_ marker: Data) throws
    func deleteMarker() throws
}

public final class FileInstallationMarkerStore: InstallationMarkerStoring {
    public let fileURL: URL
    private let storage: AtomicSnapshotFileStorage

    public init(fileURL: URL) {
        self.fileURL = fileURL
        storage = AtomicSnapshotFileStorage(fileURL: fileURL)
    }

    public func readMarker() throws -> Data? {
        storage.exists ? try storage.read() : nil
    }

    public func writeMarker(_ marker: Data) throws {
        try storage.writeAtomically(marker)
    }

    public func deleteMarker() throws {
        try storage.delete()
    }
}

public final class KeychainInstallationMarkerStore: InstallationMarkerStoring {
    private let service: String
    private let account: String

    public init(service: String, account: String = "installation-continuity-marker") {
        self.service = service
        self.account = account
    }

    public func readMarker() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw InstallationContinuityError.keychain(status)
        }
        return data
    }

    public func writeMarker(_ marker: Data) throws {
        var attributes = baseQuery
        attributes[kSecValueData as String] = marker
        // This random marker is not key material. Keeping it device-only but
        // independent of passcode state lets the Secure Enclave key boundary
        // report the typed `passcodeRequired` prerequisite on a fresh device.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecSuccess { return }
        guard status == errSecDuplicateItem else {
            throw InstallationContinuityError.keychain(status)
        }
        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: marker] as CFDictionary
        )
        guard updateStatus == errSecSuccess else {
            throw InstallationContinuityError.keychain(updateStatus)
        }
    }

    public func deleteMarker() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw InstallationContinuityError.keychain(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }
}

public enum InstallationContinuityError: Error, Equatable {
    case keychain(OSStatus)
    case invalidMarkerLength(Int)
}

public enum InstallationContinuityOutcome: Equatable {
    case existingInstallation
    case initializedFreshInstallation(replacedDiscontinuousState: Bool)
    case cleanupIncomplete([String])
    case failed(String)
}

@MainActor
public final class InstallationContinuityGuard {
    private let containerMarkerStore: InstallationMarkerStoring
    private let keychainMarkerStore: InstallationMarkerStoring
    private let signer: DeviceProofSigning
    private let snapshotStore: SecureSnapshotStore
    private let makeMarker: () -> Data

    public init(
        containerMarkerStore: InstallationMarkerStoring,
        keychainMarkerStore: InstallationMarkerStoring,
        signer: DeviceProofSigning,
        snapshotStore: SecureSnapshotStore,
        makeMarker: @escaping () -> Data = { SnapshotCipher.makeContentKey() }
    ) {
        self.containerMarkerStore = containerMarkerStore
        self.keychainMarkerStore = keychainMarkerStore
        self.signer = signer
        self.snapshotStore = snapshotStore
        self.makeMarker = makeMarker
    }

    public func establish() -> InstallationContinuityOutcome {
        let containerMarker: Data?
        let keychainMarker: Data?
        do {
            containerMarker = try containerMarkerStore.readMarker()
            keychainMarker = try keychainMarkerStore.readMarker()
        } catch {
            return .failed("installation marker read failed: \(error)")
        }

        if let containerMarker, let keychainMarker,
           containerMarker.count == 32,
           containerMarker == keychainMarker
        {
            return .existingInstallation
        }

        let hadDiscontinuousState = containerMarker != nil || keychainMarker != nil
        var cleanupFailures = destroySurvivingState()
        guard cleanupFailures.isEmpty else {
            return .cleanupIncomplete(cleanupFailures)
        }

        let marker = makeMarker()
        guard marker.count == 32 else {
            return .failed(
                String(describing: InstallationContinuityError.invalidMarkerLength(marker.count))
            )
        }

        do {
            try keychainMarkerStore.writeMarker(marker)
            try containerMarkerStore.writeMarker(marker)
        } catch {
            cleanupFailures = []
            do {
                try containerMarkerStore.deleteMarker()
            } catch {
                cleanupFailures.append("container marker rollback failed: \(error)")
            }
            do {
                try keychainMarkerStore.deleteMarker()
            } catch {
                cleanupFailures.append("keychain marker rollback failed: \(error)")
            }
            if cleanupFailures.isEmpty {
                return .failed("installation marker creation failed: \(error)")
            }
            return .cleanupIncomplete(
                ["installation marker creation failed: \(error)"] + cleanupFailures
            )
        }

        return .initializedFreshInstallation(
            replacedDiscontinuousState: hadDiscontinuousState
        )
    }

    public func removeMarkers() -> [String] {
        var failures: [String] = []
        do {
            try containerMarkerStore.deleteMarker()
        } catch {
            failures.append("container marker deletion failed: \(error)")
        }
        do {
            try keychainMarkerStore.deleteMarker()
        } catch {
            failures.append("keychain marker deletion failed: \(error)")
        }
        return failures
    }

    private func destroySurvivingState() -> [String] {
        var failures: [String] = []
        switch snapshotStore.destroy() {
        case .destroyed:
            break
        case let .cleanupIncomplete(messages):
            failures.append(contentsOf: messages)
        }
        do {
            try signer.deleteKey()
        } catch {
            failures.append("device proof-key deletion failed: \(error)")
        }
        do {
            try containerMarkerStore.deleteMarker()
        } catch {
            failures.append("container marker deletion failed: \(error)")
        }
        do {
            try keychainMarkerStore.deleteMarker()
        } catch {
            failures.append("keychain marker deletion failed: \(error)")
        }
        return failures
    }
}
