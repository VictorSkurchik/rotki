import CryptoKit
import Foundation
@preconcurrency import LocalAuthentication
import Security

public enum SecureEnclaveWrappingError: Error, Equatable {
    case keyCreationFailed(String)
    case keyLookupFailed(Int32)
    case keyMissing
    case unsupportedAlgorithm
    case wrapFailed(String)
    case deleteFailed(Int32)
    case expectationMarkerReadFailed(Int32)
    case expectationMarkerWriteFailed(Int32)
    case cleanupFailed(String)
}

@MainActor
public final class BiometricSecureEnclaveKeyWrapper: SnapshotKeyWrapping {
    private static let algorithm = SecKeyAlgorithm.eciesEncryptionCofactorX963SHA256AESGCM

    private let applicationTag: Data
    private let expectationService: String
    private var activeAuthenticationContext: LAContext?

    public init(
        applicationTag: String = "org.rotki.p03.ios-security.snapshot-wrapping-key"
    ) {
        precondition(!applicationTag.isEmpty, "applicationTag must not be empty")
        self.applicationTag = Data(applicationTag.utf8)
        expectationService = "\(applicationTag).expected"
    }

    public func prepare(
        hasProtectedSnapshot: Bool,
        localizedReason: String
    ) async -> SnapshotKeyPreparationOutcome {
        guard SecureEnclave.isAvailable else {
            return .blocked(.secureEnclaveUnavailable)
        }

        let wrappingKeyExpected: Bool
        do {
            wrappingKeyExpected = try hasExpectationMarker()
        } catch {
            return .failed(String(describing: error))
        }

        switch await authenticate(localizedReason: localizedReason) {
        case let .authenticated(context):
            do {
                if try loadPrivateKey(authenticationContext: context) != nil {
                    // A key and marker are created as one logical operation. A
                    // missing marker here can only be legacy/interrupted spike
                    // state, so restore the non-secret expectation marker.
                    if !wrappingKeyExpected {
                        try writeExpectationMarker()
                    }
                    return .ready(created: false)
                }
                if Self.missingKeyRequiresInvalidation(
                    hasProtectedSnapshot: hasProtectedSnapshot,
                    wrappingKeyExpected: wrappingKeyExpected
                ) {
                    return .invalidated(.wrappingKeyMissing)
                }
                _ = try createPrivateKeyWithExpectationMarker()
                return .ready(created: true)
            } catch let SecureEnclaveWrappingError.keyLookupFailed(status)
                where Self.isBiometricInvalidationStatus(status)
            {
                return .invalidated(.biometricEnrollmentChanged)
            } catch {
                return .failed(String(describing: error))
            }
        case .cancelled:
            return .cancelled
        case .lockedOut:
            return .lockedOut
        case let .blocked(reason):
            if (hasProtectedSnapshot || wrappingKeyExpected), reason == .passcodeRequired {
                return .invalidated(.devicePasscodeRemoved)
            }
            if wrappingKeyExpected, reason == .biometryNotEnrolled {
                return .invalidated(.biometricEnrollmentChanged)
            }
            return .blocked(reason)
        case let .failed(message):
            return .failed(message)
        }
    }

    public func wrap(_ contentKey: Data) throws -> Data {
        guard contentKey.count == 32 else {
            throw SnapshotCipherError.invalidKeyLength(contentKey.count)
        }
        guard let privateKey = try loadPrivateKey(authenticationContext: nil),
              let publicKey = SecKeyCopyPublicKey(privateKey)
        else {
            throw SecureEnclaveWrappingError.keyMissing
        }
        guard SecKeyIsAlgorithmSupported(publicKey, .encrypt, Self.algorithm) else {
            throw SecureEnclaveWrappingError.unsupportedAlgorithm
        }

        var error: Unmanaged<CFError>?
        guard let wrapped = SecKeyCreateEncryptedData(
            publicKey,
            Self.algorithm,
            contentKey as CFData,
            &error
        ) else {
            let detail = error?.takeRetainedValue().localizedDescription
                ?? "unknown Security.framework error"
            throw SecureEnclaveWrappingError.wrapFailed(detail)
        }
        return wrapped as Data
    }

    public func unwrap(
        _ wrappedContentKey: Data,
        localizedReason: String
    ) async -> SnapshotKeyUnwrapOutcome {
        guard SecureEnclave.isAvailable else {
            return .blocked(.secureEnclaveUnavailable)
        }

        switch await authenticate(localizedReason: localizedReason) {
        case let .authenticated(context):
            do {
                guard let privateKey = try loadPrivateKey(authenticationContext: context) else {
                    return .invalidated(.wrappingKeyMissing)
                }
                guard SecKeyIsAlgorithmSupported(privateKey, .decrypt, Self.algorithm) else {
                    return .failed("Secure Enclave key does not support the required ECIES algorithm")
                }

                var error: Unmanaged<CFError>?
                guard let unwrapped = SecKeyCreateDecryptedData(
                    privateKey,
                    Self.algorithm,
                    wrappedContentKey as CFData,
                    &error
                ) else {
                    let detail = error?.takeRetainedValue().localizedDescription
                        ?? "unknown Security.framework error"
                    return .failed(detail)
                }
                return .opened(unwrapped as Data)
            } catch let error as SecureEnclaveWrappingError {
                if error == .keyMissing {
                    return .invalidated(.wrappingKeyMissing)
                }
                if case let .keyLookupFailed(status) = error,
                   Self.isBiometricInvalidationStatus(status)
                {
                    return .invalidated(.biometricEnrollmentChanged)
                }
                return .failed(String(describing: error))
            } catch {
                return .failed(String(describing: error))
            }
        case .cancelled:
            return .cancelled
        case .lockedOut:
            return .lockedOut
        case let .blocked(reason):
            if reason == .biometryNotEnrolled {
                return .invalidated(.biometricEnrollmentChanged)
            }
            if reason == .passcodeRequired {
                return .invalidated(.devicePasscodeRemoved)
            }
            return .blocked(reason)
        case let .failed(message):
            return .failed(message)
        }
    }

    public func deleteKey() throws {
        var failures: [String] = []
        let keyStatus = SecItemDelete(baseKeyQuery as CFDictionary)
        if keyStatus != errSecSuccess, keyStatus != errSecItemNotFound {
            failures.append("Secure Enclave key: \(keyStatus)")
        }
        let markerStatus = SecItemDelete(expectationMarkerQuery as CFDictionary)
        if markerStatus != errSecSuccess, markerStatus != errSecItemNotFound {
            failures.append("expectation marker: \(markerStatus)")
        }
        guard failures.isEmpty else {
            throw SecureEnclaveWrappingError.cleanupFailed(failures.joined(separator: "; "))
        }
    }

    public func cancelAuthentication() {
        activeAuthenticationContext?.invalidate()
        activeAuthenticationContext = nil
    }

    private func createPrivateKey() throws -> SecKey {
        var accessControlError: Unmanaged<CFError>?
        guard let accessControl = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            [.privateKeyUsage, .biometryCurrentSet],
            &accessControlError
        ) else {
            let detail = accessControlError?.takeRetainedValue().localizedDescription
                ?? "unknown Security.framework error"
            throw SecureEnclaveWrappingError.keyCreationFailed(detail)
        }

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: applicationTag,
                kSecAttrAccessControl as String: accessControl,
            ],
        ]

        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            let detail = error?.takeRetainedValue().localizedDescription
                ?? "unknown Security.framework error"
            throw SecureEnclaveWrappingError.keyCreationFailed(detail)
        }
        return key
    }

    private func createPrivateKeyWithExpectationMarker() throws -> SecKey {
        let key = try createPrivateKey()
        do {
            try writeExpectationMarker()
            return key
        } catch {
            let keyStatus = SecItemDelete(baseKeyQuery as CFDictionary)
            if keyStatus != errSecSuccess, keyStatus != errSecItemNotFound {
                throw SecureEnclaveWrappingError.cleanupFailed(
                    "expectation marker creation failed: \(error); "
                    + "Secure Enclave key rollback failed: \(keyStatus)"
                )
            }
            throw error
        }
    }

    private func hasExpectationMarker() throws -> Bool {
        var query = expectationMarkerQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else {
            throw SecureEnclaveWrappingError.expectationMarkerReadFailed(status)
        }
        return true
    }

    private func writeExpectationMarker() throws {
        var attributes = expectationMarkerQuery
        attributes[kSecValueData as String] = Data([1])
        // Non-secret, non-migrating evidence that a biometry-bound key must
        // exist. It deliberately survives passcode removal so key loss is not
        // mistaken for first setup.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecSuccess { return }
        guard status == errSecDuplicateItem else {
            throw SecureEnclaveWrappingError.expectationMarkerWriteFailed(status)
        }
        let updateStatus = SecItemUpdate(
            expectationMarkerQuery as CFDictionary,
            [kSecValueData as String: Data([1])] as CFDictionary
        )
        guard updateStatus == errSecSuccess else {
            throw SecureEnclaveWrappingError.expectationMarkerWriteFailed(updateStatus)
        }
    }

    private func loadPrivateKey(authenticationContext: LAContext?) throws -> SecKey? {
        var query = baseKeyQuery
        query[kSecReturnRef as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        if let authenticationContext {
            query[kSecUseAuthenticationContext as String] = authenticationContext
        }

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw SecureEnclaveWrappingError.keyLookupFailed(status)
        }
        guard let key = result else {
            throw SecureEnclaveWrappingError.keyMissing
        }
        return (key as! SecKey)
    }

    private var baseKeyQuery: [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag as String: applicationTag,
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    private var expectationMarkerQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: expectationService,
            kSecAttrAccount as String: "snapshot-wrapping-key-expected",
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    nonisolated static func isBiometricInvalidationStatus(_ status: OSStatus) -> Bool {
        status == errSecItemNotFound || status == errSecAuthFailed
    }

    nonisolated static func missingKeyRequiresInvalidation(
        hasProtectedSnapshot: Bool,
        wrappingKeyExpected: Bool
    ) -> Bool {
        hasProtectedSnapshot || wrappingKeyExpected
    }

    private enum AuthenticationOutcome {
        case authenticated(LAContext)
        case cancelled
        case lockedOut
        case blocked(SecurityAvailabilityBlock)
        case failed(String)
    }

    private func authenticate(localizedReason: String) async -> AuthenticationOutcome {
        let context = LAContext()
        activeAuthenticationContext?.invalidate()
        activeAuthenticationContext = context
        defer {
            if activeAuthenticationContext === context {
                activeAuthenticationContext = nil
            }
        }
        context.localizedFallbackTitle = ""

        var evaluationError: NSError?
        guard context.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &evaluationError
        ) else {
            return Self.classifyAuthenticationError(evaluationError)
        }

        return await withCheckedContinuation { continuation in
            context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: localizedReason
            ) { success, error in
                if success {
                    continuation.resume(returning: .authenticated(context))
                } else {
                    continuation.resume(
                        returning: Self.classifyAuthenticationError(error as NSError?)
                    )
                }
            }
        }
    }

    nonisolated private static func classifyAuthenticationError(
        _ error: NSError?
    ) -> AuthenticationOutcome {
        guard let error, error.domain == LAError.errorDomain,
              let code = LAError.Code(rawValue: error.code)
        else {
            return .failed(error?.localizedDescription ?? "biometric authentication failed")
        }

        switch code {
        case .biometryLockout:
            return .lockedOut
        case .biometryNotEnrolled:
            return .blocked(.biometryNotEnrolled)
        case .biometryNotAvailable:
            return .blocked(.biometryUnavailable)
        case .passcodeNotSet:
            return .blocked(.passcodeRequired)
        case .authenticationFailed, .userCancel, .systemCancel, .appCancel, .userFallback:
            return .cancelled
        default:
            return .failed(error.localizedDescription)
        }
    }
}
