import Foundation

public enum DeviceProofTranscriptError: Error, Equatable {
    case invalidCanonicalHTTPSOrigin
    case invalidOriginLength(Int)
    case invalidDeviceSessionIDLength(Int)
    case invalidChallengeIDLength(Int)
    case invalidNonceLength(Int)
}

public enum DeviceProofTranscript {
    private static let domain = Data("rotki-companion-device-proof/v1".utf8)

    public static func encode(
        canonicalEngineOrigin: String,
        deviceSessionID: Data,
        challengeID: Data,
        nonce: Data,
        expiresAt: UInt64
    ) throws -> Data {
        guard isCanonicalHTTPSOrigin(canonicalEngineOrigin) else {
            throw DeviceProofTranscriptError.invalidCanonicalHTTPSOrigin
        }
        let origin = Data(canonicalEngineOrigin.utf8)
        guard !origin.isEmpty, origin.count <= Int(UInt16.max) else {
            throw DeviceProofTranscriptError.invalidOriginLength(origin.count)
        }
        guard deviceSessionID.count == 32 else {
            throw DeviceProofTranscriptError.invalidDeviceSessionIDLength(deviceSessionID.count)
        }
        guard challengeID.count == 16 else {
            throw DeviceProofTranscriptError.invalidChallengeIDLength(challengeID.count)
        }
        guard nonce.count == 32 else {
            throw DeviceProofTranscriptError.invalidNonceLength(nonce.count)
        }

        var result = Data(capacity: domain.count + 1 + 2 + origin.count + 32 + 16 + 32 + 8)
        result.append(domain)
        result.append(0)
        result.append(UInt8((origin.count >> 8) & 0xff))
        result.append(UInt8(origin.count & 0xff))
        result.append(origin)
        result.append(deviceSessionID)
        result.append(challengeID)
        result.append(nonce)
        for shift in stride(from: 56, through: 0, by: -8) {
            result.append(UInt8((expiresAt >> UInt64(shift)) & 0xff))
        }
        return result
    }

    private static func isCanonicalHTTPSOrigin(_ value: String) -> Bool {
        guard value.unicodeScalars.allSatisfy({ $0.isASCII }),
              value == value.lowercased(),
              !value.hasSuffix("/"),
              let components = URLComponents(string: value),
              components.scheme == "https",
              components.host != nil,
              components.user == nil,
              components.password == nil,
              components.path.isEmpty,
              components.query == nil,
              components.fragment == nil,
              components.port != 443
        else {
            return false
        }
        let canonicalPort = components.port.map { ":\($0)" } ?? ""
        return value == "https://\(components.host!)\(canonicalPort)"
    }

    public static func p03GoldenVector() -> Data {
        // Checked against mobile/protocol/v1/golden_vectors.json.
        try! encode(
            canonicalEngineOrigin: "https://rotki.example",
            deviceSessionID: Data(0x00...0x1f),
            challengeID: Data(0x20...0x2f),
            nonce: Data(0x30...0x4f),
            expiresAt: 1_786_550_400
        )
    }
}
