import CryptoKit
import Foundation
import Security
import XCTest
@testable import IOSSecuritySpike

final class SnapshotEnvelopeTests: XCTestCase {
    func testEnvelopeRoundTripsExactly() throws {
        let envelope = SnapshotEnvelope(
            wrappedContentKey: Data((0..<80).map(UInt8.init)),
            sealedSnapshot: Data((0..<64).map { UInt8($0 ^ 0xa5) })
        )

        let encoded = try SnapshotEnvelopeCodec.encode(envelope)

        XCTAssertEqual(try SnapshotEnvelopeCodec.decode(encoded), envelope)
    }

    func testEnvelopeRejectsBadMagicVersionTruncationAndTrailingBytes() throws {
        let envelope = SnapshotEnvelope(
            wrappedContentKey: Data(repeating: 0x11, count: 80),
            sealedSnapshot: Data(repeating: 0x22, count: 28)
        )
        let encoded = try SnapshotEnvelopeCodec.encode(envelope)

        var badMagic = encoded
        badMagic[0] ^= 0xff
        XCTAssertThrowsError(try SnapshotEnvelopeCodec.decode(badMagic)) { error in
            XCTAssertEqual(error as? SnapshotEnvelopeCodecError, .invalidMagic)
        }

        var badVersion = encoded
        badVersion[8] = 2
        XCTAssertThrowsError(try SnapshotEnvelopeCodec.decode(badVersion)) { error in
            XCTAssertEqual(error as? SnapshotEnvelopeCodecError, .unsupportedVersion(2))
        }

        XCTAssertThrowsError(try SnapshotEnvelopeCodec.decode(encoded.dropLast())) { error in
            XCTAssertEqual(error as? SnapshotEnvelopeCodecError, .truncated)
        }

        var trailing = encoded
        trailing.append(0)
        XCTAssertThrowsError(try SnapshotEnvelopeCodec.decode(trailing)) { error in
            XCTAssertEqual(error as? SnapshotEnvelopeCodecError, .trailingBytes)
        }
    }

    func testAESGCMRoundTripUsesFreshNonce() throws {
        let key = Data(repeating: 0x42, count: 32)
        let plaintext = Data("offline snapshot".utf8)

        let first = try SnapshotCipher.seal(plaintext, contentKey: key)
        let second = try SnapshotCipher.seal(plaintext, contentKey: key)

        XCTAssertEqual(try SnapshotCipher.open(first, contentKey: key), plaintext)
        XCTAssertEqual(try SnapshotCipher.open(second, contentKey: key), plaintext)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(first.count, plaintext.count + 12 + 16)
    }

    func testAESGCMTamperAndWrongKeyFailClosed() throws {
        let key = Data(repeating: 0x42, count: 32)
        let sealed = try SnapshotCipher.seal(Data("snapshot".utf8), contentKey: key)

        var tampered = sealed
        tampered[tampered.count - 1] ^= 0x01
        XCTAssertThrowsError(try SnapshotCipher.open(tampered, contentKey: key)) { error in
            XCTAssertEqual(error as? SnapshotCipherError, .authenticationFailed)
        }

        XCTAssertThrowsError(
            try SnapshotCipher.open(sealed, contentKey: Data(repeating: 0x43, count: 32))
        ) { error in
            XCTAssertEqual(error as? SnapshotCipherError, .authenticationFailed)
        }
    }

    func testDeviceProofWireFormatWithSoftwareP256Key() throws {
        let privateKey = P256.Signing.PrivateKey(compactRepresentable: false)
        let message = Data("RKP1 device proof transcript".utf8)
        let publicKey = privateKey.publicKey.x963Representation
        let signature = try privateKey.signature(for: message).rawRepresentation

        XCTAssertEqual(publicKey.count, 65)
        XCTAssertEqual(publicKey.first, 0x04)
        XCTAssertEqual(signature.count, 64)
        XCTAssertTrue(
            DeviceProofFormat.verify(
                message: message,
                publicKeyX963: publicKey,
                signatureP1363: signature
            )
        )
    }

    func testBiometricACLInvalidationStatusesAreDistinguished() {
        XCTAssertTrue(
            BiometricSecureEnclaveKeyWrapper.isBiometricInvalidationStatus(
                errSecItemNotFound
            )
        )
        XCTAssertTrue(
            BiometricSecureEnclaveKeyWrapper.isBiometricInvalidationStatus(
                errSecAuthFailed
            )
        )
        XCTAssertFalse(
            BiometricSecureEnclaveKeyWrapper.isBiometricInvalidationStatus(
                errSecInteractionNotAllowed
            )
        )
    }

    func testExpectedWrapperLossBeforeFirstSnapshotIsInvalidation() {
        XCTAssertTrue(
            BiometricSecureEnclaveKeyWrapper.missingKeyRequiresInvalidation(
                hasProtectedSnapshot: false,
                wrappingKeyExpected: true
            )
        )
        XCTAssertTrue(
            BiometricSecureEnclaveKeyWrapper.missingKeyRequiresInvalidation(
                hasProtectedSnapshot: true,
                wrappingKeyExpected: false
            )
        )
        XCTAssertFalse(
            BiometricSecureEnclaveKeyWrapper.missingKeyRequiresInvalidation(
                hasProtectedSnapshot: false,
                wrappingKeyExpected: false
            )
        )
    }

    func testStrictP1363ScalarBounds() throws {
        let privateKey = P256.Signing.PrivateKey(compactRepresentable: false)
        let publicKey = privateKey.publicKey.x963Representation
        let order = Data([
            0xff, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x00,
            0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
            0xbc, 0xe6, 0xfa, 0xad, 0xa7, 0x17, 0x9e, 0x84,
            0xf3, 0xb9, 0xca, 0xc2, 0xfc, 0x63, 0x25, 0x51,
        ])

        XCTAssertThrowsError(
            try DeviceProofFormat.validate(
                publicKeyX963: publicKey,
                signatureP1363: Data(repeating: 0, count: 64)
            )
        ) { error in
            XCTAssertEqual(error as? DeviceProofSignerError, .invalidSignatureScalar)
        }

        var rZero = Data(repeating: 0, count: 32)
        rZero.append(Data(repeating: 0, count: 31))
        rZero.append(1)
        XCTAssertThrowsError(
            try DeviceProofFormat.validate(
                publicKeyX963: publicKey,
                signatureP1363: rZero
            )
        ) { error in
            XCTAssertEqual(error as? DeviceProofSignerError, .invalidSignatureScalar)
        }

        var sZero = Data(repeating: 0, count: 31)
        sZero.append(1)
        sZero.append(Data(repeating: 0, count: 32))
        XCTAssertThrowsError(
            try DeviceProofFormat.validate(
                publicKeyX963: publicKey,
                signatureP1363: sZero
            )
        ) { error in
            XCTAssertEqual(error as? DeviceProofSignerError, .invalidSignatureScalar)
        }

        var rAtOrder = Data()
        rAtOrder.append(order)
        rAtOrder.append(Data(repeating: 0, count: 31))
        rAtOrder.append(1)
        XCTAssertThrowsError(
            try DeviceProofFormat.validate(
                publicKeyX963: publicKey,
                signatureP1363: rAtOrder
            )
        ) { error in
            XCTAssertEqual(error as? DeviceProofSignerError, .invalidSignatureScalar)
        }

        var sAtOrder = Data(repeating: 0, count: 31)
        sAtOrder.append(1)
        sAtOrder.append(order)
        XCTAssertThrowsError(
            try DeviceProofFormat.validate(
                publicKeyX963: publicKey,
                signatureP1363: sAtOrder
            )
        ) { error in
            XCTAssertEqual(error as? DeviceProofSignerError, .invalidSignatureScalar)
        }
    }

    func testDeviceProofTranscriptMatchesRepositoryGoldenVectorExactly() {
        let expected = Data(hex: "726f746b692d636f6d70616e696f6e2d6465766963652d70726f6f662f763100001568747470733a2f2f726f746b692e6578616d706c65000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f000000006a7c9880")

        XCTAssertEqual(DeviceProofTranscript.p03GoldenVector(), expected)
        XCTAssertEqual(
            SHA256.hash(data: DeviceProofTranscript.p03GoldenVector())
                .map { String(format: "%02x", $0) }
                .joined(),
            "f58760e2c57f148ba4b715d689f5648688c9d74f1e3d5d357fd81a64591cf4e0"
        )
    }

    func testDeviceProofTranscriptRejectsNonCanonicalOrigins() {
        let device = Data(0x00...0x1f)
        let challenge = Data(0x20...0x2f)
        let nonce = Data(0x30...0x4f)
        for origin in [
            "http://rotki.example",
            "https://ROTki.example",
            "https://rotki.example:443",
            "https://rötki.example",
            "https://rotki.example/",
            "https://user@rotki.example",
            "https://rotki.example/path",
            "https://rotki.example?query=1",
            "https://rotki.example#fragment",
        ] {
            XCTAssertThrowsError(
                try DeviceProofTranscript.encode(
                    canonicalEngineOrigin: origin,
                    deviceSessionID: device,
                    challengeID: challenge,
                    nonce: nonce,
                    expiresAt: 1_786_550_400
                ),
                "accepted non-canonical origin \(origin)"
            ) { error in
                XCTAssertEqual(
                    error as? DeviceProofTranscriptError,
                    .invalidCanonicalHTTPSOrigin
                )
            }
        }

        XCTAssertNoThrow(
            try DeviceProofTranscript.encode(
                canonicalEngineOrigin: "https://192.168.1.2:50443",
                deviceSessionID: device,
                challengeID: challenge,
                nonce: nonce,
                expiresAt: 1_786_550_400
            )
        )
    }

    func testDeviceProofVerificationRejectsAlteredAndDoubleHashedMessages() throws {
        let privateKey = P256.Signing.PrivateKey(compactRepresentable: false)
        let transcript = DeviceProofTranscript.p03GoldenVector()
        let publicKey = privateKey.publicKey.x963Representation
        let signature = try privateKey.signature(for: transcript).rawRepresentation
        let digest = Data(SHA256.hash(data: transcript))

        var altered = transcript
        altered[altered.count - 1] ^= 1
        XCTAssertFalse(
            DeviceProofFormat.verify(
                message: altered,
                publicKeyX963: publicKey,
                signatureP1363: signature
            )
        )
        XCTAssertFalse(
            DeviceProofFormat.verify(
                message: digest,
                publicKeyX963: publicKey,
                signatureP1363: signature
            )
        )
    }
}

private extension Data {
    init(hex: String) {
        precondition(hex.count.isMultiple(of: 2))
        self.init()
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
    }
}
