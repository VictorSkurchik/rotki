import CryptoKit
import Foundation

public struct SnapshotEnvelope: Equatable {
    public let wrappedContentKey: Data
    public let sealedSnapshot: Data

    public init(wrappedContentKey: Data, sealedSnapshot: Data) {
        self.wrappedContentKey = wrappedContentKey
        self.sealedSnapshot = sealedSnapshot
    }
}

public enum SnapshotEnvelopeCodecError: Error, Equatable {
    case invalidMagic
    case unsupportedVersion(UInt8)
    case invalidWrappedKeyLength(Int)
    case invalidSealedSnapshotLength(Int)
    case truncated
    case trailingBytes
}

public enum SnapshotEnvelopeCodec {
    // RKP03IOS | version:u8 | wrapped-key-length:u16be | sealed-length:u32be | payloads
    private static let magic = Data("RKP03IOS".utf8)
    private static let version: UInt8 = 1
    private static let headerLength = 8 + 1 + 2 + 4
    private static let maximumWrappedKeyLength = 16 * 1024
    private static let maximumSealedSnapshotLength = 16 * 1024 * 1024

    public static func encode(_ envelope: SnapshotEnvelope) throws -> Data {
        let wrappedLength = envelope.wrappedContentKey.count
        guard (1...maximumWrappedKeyLength).contains(wrappedLength),
              wrappedLength <= Int(UInt16.max)
        else {
            throw SnapshotEnvelopeCodecError.invalidWrappedKeyLength(wrappedLength)
        }

        let sealedLength = envelope.sealedSnapshot.count
        guard (28...maximumSealedSnapshotLength).contains(sealedLength),
              sealedLength <= Int(UInt32.max)
        else {
            throw SnapshotEnvelopeCodecError.invalidSealedSnapshotLength(sealedLength)
        }

        var encoded = Data(capacity: headerLength + wrappedLength + sealedLength)
        encoded.append(magic)
        encoded.append(version)
        encoded.append(UInt8((wrappedLength >> 8) & 0xff))
        encoded.append(UInt8(wrappedLength & 0xff))
        encoded.append(UInt8((sealedLength >> 24) & 0xff))
        encoded.append(UInt8((sealedLength >> 16) & 0xff))
        encoded.append(UInt8((sealedLength >> 8) & 0xff))
        encoded.append(UInt8(sealedLength & 0xff))
        encoded.append(envelope.wrappedContentKey)
        encoded.append(envelope.sealedSnapshot)
        return encoded
    }

    public static func decode(_ encoded: Data) throws -> SnapshotEnvelope {
        guard encoded.count >= headerLength else {
            throw SnapshotEnvelopeCodecError.truncated
        }
        guard encoded.prefix(magic.count) == magic else {
            throw SnapshotEnvelopeCodecError.invalidMagic
        }

        let bytes = [UInt8](encoded.prefix(headerLength))
        let encodedVersion = bytes[8]
        guard encodedVersion == version else {
            throw SnapshotEnvelopeCodecError.unsupportedVersion(encodedVersion)
        }

        let wrappedLength = (Int(bytes[9]) << 8) | Int(bytes[10])
        guard (1...maximumWrappedKeyLength).contains(wrappedLength) else {
            throw SnapshotEnvelopeCodecError.invalidWrappedKeyLength(wrappedLength)
        }

        let sealedLength = (Int(bytes[11]) << 24)
            | (Int(bytes[12]) << 16)
            | (Int(bytes[13]) << 8)
            | Int(bytes[14])
        guard (28...maximumSealedSnapshotLength).contains(sealedLength) else {
            throw SnapshotEnvelopeCodecError.invalidSealedSnapshotLength(sealedLength)
        }

        let expectedLength = headerLength + wrappedLength + sealedLength
        guard encoded.count >= expectedLength else {
            throw SnapshotEnvelopeCodecError.truncated
        }
        guard encoded.count == expectedLength else {
            throw SnapshotEnvelopeCodecError.trailingBytes
        }

        let wrappedStart = headerLength
        let sealedStart = wrappedStart + wrappedLength
        return SnapshotEnvelope(
            wrappedContentKey: encoded.subdata(in: wrappedStart..<sealedStart),
            sealedSnapshot: encoded.subdata(in: sealedStart..<expectedLength)
        )
    }
}

public enum SnapshotCipherError: Error, Equatable {
    case invalidKeyLength(Int)
    case encryptionFailed
    case authenticationFailed
}

public enum SnapshotCipher {
    public static let authenticatedData = Data("rotki:p0.3:ios-snapshot:v1".utf8)

    public static func makeContentKey() -> Data {
        let key = SymmetricKey(size: .bits256)
        return key.withUnsafeBytes { Data($0) }
    }

    public static func seal(_ plaintext: Data, contentKey: Data) throws -> Data {
        guard contentKey.count == 32 else {
            throw SnapshotCipherError.invalidKeyLength(contentKey.count)
        }

        do {
            let box = try AES.GCM.seal(
                plaintext,
                using: SymmetricKey(data: contentKey),
                authenticating: authenticatedData
            )
            guard let combined = box.combined else {
                throw SnapshotCipherError.encryptionFailed
            }
            return combined
        } catch let error as SnapshotCipherError {
            throw error
        } catch {
            throw SnapshotCipherError.encryptionFailed
        }
    }

    public static func open(_ sealedSnapshot: Data, contentKey: Data) throws -> Data {
        guard contentKey.count == 32 else {
            throw SnapshotCipherError.invalidKeyLength(contentKey.count)
        }

        do {
            let box = try AES.GCM.SealedBox(combined: sealedSnapshot)
            return try AES.GCM.open(
                box,
                using: SymmetricKey(data: contentKey),
                authenticating: authenticatedData
            )
        } catch {
            throw SnapshotCipherError.authenticationFailed
        }
    }
}
