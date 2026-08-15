import CryptoKit
import Foundation
import RotkiShared
import Security

/// A persistent, non-exportable Secure Enclave P-256 Device Key.
final class IOSDeviceProofSigner: NSObject, DeviceProofSigner {
    private let queue = DispatchQueue(label: "com.rotki.companion.device-proof")
    private let keyStore = IOSDeviceProofKeyStore()

    var hasCurrentKey: Bool {
        queue.sync { keyStore.hasCurrentKey() }
    }

    func createKeyForPairing(
        completionHandler: @escaping ((any DeviceProofPublicKeyOutcome)?, Error?) -> Void
    ) {
        queue.async { [keyStore] in
            completionHandler(keyStore.createOrCurrentPublicKeyOutcome(), nil)
        }
    }

    func currentPublicKeyX963(
        completionHandler: @escaping ((any DeviceProofPublicKeyOutcome)?, Error?) -> Void
    ) {
        queue.async { [keyStore] in
            completionHandler(keyStore.currentPublicKeyOutcome(), nil)
        }
    }

    func sign(
        transcript: KotlinByteArray,
        completionHandler: @escaping ((any DeviceProofSigningOutcome)?, Error?) -> Void
    ) {
        let message = IOSMutableDataBox(transcript.dataCopy())
        queue.async { [keyStore] in
            defer { message.wipe() }
            completionHandler(keyStore.signingOutcome(message: message.data), nil)
        }
    }

    func deleteKey(
        completionHandler: @escaping ((any DeviceProofKeyDeleteOutcome)?, Error?) -> Void
    ) {
        queue.async { [keyStore] in
            completionHandler(
                keyStore.delete() ?
                    DeviceProofKeyDeleteOutcomeDeleted.shared :
                    DeviceProofKeyDeleteOutcomeUnavailable.shared,
                nil
            )
        }
    }

    override var description: String {
        "IOSDeviceProofSigner(redacted)"
    }
}

private final class IOSDeviceProofKeyStore {
    private let blobStore = IOSKeychainBlobStore(
        service: "com.rotki.companion.security",
        account: "device-proof-signing-key-v1",
        accessibility: kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
    )

    func createOrCurrentPublicKeyOutcome() -> any DeviceProofPublicKeyOutcome {
        do {
            let key = try loadOrCreate()
            return try publicKeyOutcome(key.publicKey.x963Representation)
        } catch {
            return DeviceProofPublicKeyOutcomeUnexpectedFailure.shared
        }
    }

    func hasCurrentKey() -> Bool {
        (try? loadCurrent()) != nil
    }

    func currentPublicKeyOutcome() -> any DeviceProofPublicKeyOutcome {
        do {
            guard let key = try loadCurrent() else {
                return DeviceProofPublicKeyOutcomePairingRequired.shared
            }
            return try publicKeyOutcome(key.publicKey.x963Representation)
        } catch {
            return DeviceProofPublicKeyOutcomeUnexpectedFailure.shared
        }
    }

    func signingOutcome(message: Data) -> any DeviceProofSigningOutcome {
        guard SecureEnclave.isAvailable else {
            return DeviceProofSigningOutcomeDeviceAuthenticationUnavailable.shared
        }
        do {
            guard let key = try loadCurrent() else {
                return DeviceProofSigningOutcomePairingRequired.shared
            }
            let rawSignature = try key.signature(for: message).rawRepresentation
            guard rawSignature.count == 64 else {
                return DeviceProofSigningOutcomeUnexpectedFailure.shared
            }
            let parsed = P1363Signature.companion.fromBytes(bytes: rawSignature.kotlinByteArray())
            guard let signature = (
                parsed as? ProtocolValueParseOutcomeAccepted<P1363Signature>
            )?.value else {
                return DeviceProofSigningOutcomeUnexpectedFailure.shared
            }
            return DeviceProofSigningOutcomeSigned(signature: signature)
        } catch {
            return DeviceProofSigningOutcomeUnexpectedFailure.shared
        }
    }

    func delete() -> Bool {
        do {
            try blobStore.delete()
            return true
        } catch {
            return false
        }
    }

    private func publicKeyOutcome(_ data: Data) throws -> any DeviceProofPublicKeyOutcome {
        guard data.count == 65, data.first == 0x04 else {
            throw IOSDeviceProofError.invalidPublicKey
        }
        let parsed = X963PublicKey.companion.fromBytes(bytes: data.kotlinByteArray())
        guard let publicKey = (
            parsed as? ProtocolValueParseOutcomeAccepted<X963PublicKey>
        )?.value else {
            throw IOSDeviceProofError.invalidPublicKey
        }
        return DeviceProofPublicKeyOutcomePublicKey(value: publicKey)
    }

    private func loadCurrent() throws -> SecureEnclave.P256.Signing.PrivateKey? {
        guard SecureEnclave.isAvailable else {
            throw IOSDeviceProofError.secureEnclaveUnavailable
        }
        guard let representation = try blobStore.load() else {
            return nil
        }
        do {
            return try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: representation)
        } catch {
            throw IOSDeviceProofError.invalidStoredKey
        }
    }

    private func loadOrCreate() throws -> SecureEnclave.P256.Signing.PrivateKey {
        if let current = try loadCurrent() {
            return current
        }
        var accessControlError: Unmanaged<CFError>?
        guard let accessControl = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            [.privateKeyUsage],
            &accessControlError
        ) else {
            _ = accessControlError?.takeRetainedValue()
            throw IOSDeviceProofError.accessControlUnavailable
        }
        do {
            let key = try SecureEnclave.P256.Signing.PrivateKey(
                compactRepresentable: false,
                accessControl: accessControl,
                authenticationContext: nil
            )
            try blobStore.save(key.dataRepresentation)
            return key
        } catch {
            try? blobStore.delete()
            throw IOSDeviceProofError.keyCreationFailed
        }
    }
}

private enum IOSDeviceProofError: Error {
    case secureEnclaveUnavailable
    case accessControlUnavailable
    case invalidStoredKey
    case invalidPublicKey
    case keyCreationFailed
}

private final class IOSMutableDataBox: @unchecked Sendable {
    var data: Data

    init(_ data: Data) {
        self.data = data
    }

    func wipe() {
        data.resetBytes(in: data.startIndex..<data.endIndex)
    }
}

extension KotlinByteArray {
    func dataCopy() -> Data {
        var result = Data(count: Int(size))
        result.withUnsafeMutableBytes { destination in
            guard let baseAddress = destination.baseAddress else { return }
            let bytes = baseAddress.assumingMemoryBound(to: UInt8.self)
            for index in 0..<Int(size) {
                bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
            }
        }
        return result
    }
}

extension Data {
    func kotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }
}
