package org.rotki.mobile.spikes.security

object ProofVector {
    const val ENGINE_ORIGIN = "https://rotki.example"
    const val EXPIRES_AT: ULong = 1_786_550_400uL
    const val TRANSCRIPT_SHA256 = "f58760e2c57f148ba4b715d689f5648688c9d74f1e3d5d357fd81a64591cf4e0"

    fun transcript(): ByteArray = ProtocolEncoding.encodeDeviceProofTranscript(
        canonicalEngineOrigin = ENGINE_ORIGIN,
        deviceSessionId = ProtocolEncoding.decodeCanonicalBase64Url(
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        ),
        challengeId = ProtocolEncoding.decodeCanonicalBase64Url("ICEiIyQlJicoKSorLC0uLw"),
        nonce = ProtocolEncoding.decodeCanonicalBase64Url(
            "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8",
        ),
        expiresAt = EXPIRES_AT,
    )
}
