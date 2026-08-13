import CryptoKit
import Foundation
import Security

public enum DeviceProofSignerError: Error, Equatable {
    case secureEnclaveUnavailable
    case accessControlCreationFailed(String)
    case keychainFailure(Int32)
    case malformedPublicKey(Int)
    case malformedSignature(Int)
    case invalidSignatureScalar
    case identityMissing
    case cryptographicFailure(String)
}

public struct DeviceProof: Equatable {
    public let publicKeyX963: Data
    public let signatureP1363: Data

    public init(publicKeyX963: Data, signatureP1363: Data) {
        self.publicKeyX963 = publicKeyX963
        self.signatureP1363 = signatureP1363
    }
}

public protocol DeviceProofSigning: AnyObject {
    func sign(message: Data) throws -> DeviceProof
    func deleteKey() throws
    func setKeyCreationAuthorized(_ authorized: Bool)
}

public enum DeviceProofFormat {
    private static let p256Order: [UInt8] = [
        0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xbc, 0xe6, 0xfa, 0xad, 0xa7, 0x17, 0x9e, 0x84,
        0xf3, 0xb9, 0xca, 0xc2, 0xfc, 0x63, 0x25, 0x51,
    ]

    public static func validate(publicKeyX963: Data, signatureP1363: Data) throws {
        guard publicKeyX963.count == 65, publicKeyX963.first == 0x04 else {
            throw DeviceProofSignerError.malformedPublicKey(publicKeyX963.count)
        }
        guard signatureP1363.count == 64 else {
            throw DeviceProofSignerError.malformedSignature(signatureP1363.count)
        }
        let signatureBytes = [UInt8](signatureP1363)
        guard isValidScalar(Array(signatureBytes[0..<32])),
              isValidScalar(Array(signatureBytes[32..<64]))
        else {
            throw DeviceProofSignerError.invalidSignatureScalar
        }
    }

    public static func verify(
        message: Data,
        publicKeyX963: Data,
        signatureP1363: Data
    ) -> Bool {
        do {
            try validate(publicKeyX963: publicKeyX963, signatureP1363: signatureP1363)
            let publicKey = try P256.Signing.PublicKey(x963Representation: publicKeyX963)
            let signature = try P256.Signing.ECDSASignature(rawRepresentation: signatureP1363)
            return publicKey.isValidSignature(signature, for: message)
        } catch {
            return false
        }
    }

    private static func isValidScalar(_ scalar: [UInt8]) -> Bool {
        guard scalar.contains(where: { $0 != 0 }) else { return false }
        return scalar.lexicographicallyPrecedes(p256Order)
    }
}

private final class KeychainBlobStore {
    private let service: String
    private let account: String

    init(service: String, account: String) {
        self.service = service
        self.account = account
    }

    func load() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw DeviceProofSignerError.keychainFailure(status)
        }
        guard let data = result as? Data else {
            throw DeviceProofSignerError.keychainFailure(errSecDecode)
        }
        return data
    }

    func save(_ data: Data) throws {
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly

        let addStatus = SecItemAdd(attributes as CFDictionary, nil)
        if addStatus == errSecSuccess {
            return
        }
        guard addStatus == errSecDuplicateItem else {
            throw DeviceProofSignerError.keychainFailure(addStatus)
        }

        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        guard updateStatus == errSecSuccess else {
            throw DeviceProofSignerError.keychainFailure(updateStatus)
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DeviceProofSignerError.keychainFailure(status)
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

/// A device-bound P-256 signing key. CryptoKit's `dataRepresentation` is an
/// opaque, Secure-Enclave-bound representation; it is not raw private-key data.
public final class SecureEnclaveDeviceProofSigner: DeviceProofSigning {
    private let blobStore: KeychainBlobStore
    private var keyCreationAuthorized = false

    public init(
        keychainService: String = "org.rotki.p03.ios-security",
        keychainAccount: String = "device-proof-signing-key"
    ) {
        blobStore = KeychainBlobStore(
            service: keychainService,
            account: keychainAccount
        )
    }

    public func sign(message: Data) throws -> DeviceProof {
        let privateKey = try loadOrCreateKey()
        do {
            let publicKey = privateKey.publicKey.x963Representation
            let signature = try privateKey.signature(for: message).rawRepresentation
            try DeviceProofFormat.validate(
                publicKeyX963: publicKey,
                signatureP1363: signature
            )
            return DeviceProof(
                publicKeyX963: publicKey,
                signatureP1363: signature
            )
        } catch let error as DeviceProofSignerError {
            throw error
        } catch {
            throw DeviceProofSignerError.cryptographicFailure(
                String(describing: error)
            )
        }
    }

    public func deleteKey() throws {
        try blobStore.delete()
    }

    public func setKeyCreationAuthorized(_ authorized: Bool) {
        keyCreationAuthorized = authorized
    }

    private func loadOrCreateKey() throws -> SecureEnclave.P256.Signing.PrivateKey {
        guard SecureEnclave.isAvailable else {
            throw DeviceProofSignerError.secureEnclaveUnavailable
        }

        if let representation = try blobStore.load() {
            keyCreationAuthorized = false
            do {
                return try SecureEnclave.P256.Signing.PrivateKey(
                    dataRepresentation: representation
                )
            } catch {
                throw DeviceProofSignerError.cryptographicFailure(
                    "stored Secure Enclave signing key could not be restored"
                )
            }
        }

        guard keyCreationAuthorized else {
            throw DeviceProofSignerError.identityMissing
        }

        var accessControlError: Unmanaged<CFError>?
        guard let accessControl = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            [.privateKeyUsage],
            &accessControlError
        ) else {
            let detail = accessControlError?.takeRetainedValue().localizedDescription
                ?? "unknown Security.framework error"
            throw DeviceProofSignerError.accessControlCreationFailed(detail)
        }

        do {
            let privateKey = try SecureEnclave.P256.Signing.PrivateKey(
                compactRepresentable: false,
                accessControl: accessControl,
                authenticationContext: nil
            )
            try blobStore.save(privateKey.dataRepresentation)
            keyCreationAuthorized = false
            return privateKey
        } catch let error as DeviceProofSignerError {
            throw error
        } catch {
            throw DeviceProofSignerError.cryptographicFailure(
                String(describing: error)
            )
        }
    }
}
