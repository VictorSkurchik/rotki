package org.rotki.mobile.auth.protocol

import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin

internal fun encodeDeviceProofTranscript(
    engineOrigin: EngineOrigin,
    deviceSessionId: DeviceSessionId,
    challenge: AuthorizationChallenge,
): ByteArray {
    require(challenge.expiresAtEpochSeconds >= 0) { "Challenge expiry must be non-negative" }
    val origin = engineOrigin.canonical.encodeToByteArray()
    require(origin.size <= U_SHORT_MAX) { "Engine origin is too long for the proof transcript" }
    val deviceBytes = deviceSessionId.bytesCopy()
    val challengeBytes = challenge.id.bytesCopy()
    val nonceBytes = challenge.nonce.bytesCopy()
    val result =
        ByteArray(
            DEVICE_PROOF_DOMAIN.size + 1 + 2 + origin.size +
                deviceBytes.size + challengeBytes.size + nonceBytes.size + Long.SIZE_BYTES,
        )
    var offset = 0
    offset = result.put(DEVICE_PROOF_DOMAIN, offset)
    result[offset++] = 0
    result[offset++] = (origin.size ushr 8).toByte()
    result[offset++] = origin.size.toByte()
    offset = result.put(origin, offset)
    offset = result.put(deviceBytes, offset)
    offset = result.put(challengeBytes, offset)
    offset = result.put(nonceBytes, offset)
    for (shift in 56 downTo 0 step 8) {
        result[offset++] = (challenge.expiresAtEpochSeconds ushr shift).toByte()
    }
    check(offset == result.size)
    return result
}

private fun ByteArray.put(
    value: ByteArray,
    at: Int,
): Int {
    value.copyInto(this, destinationOffset = at)
    return at + value.size
}

private val DEVICE_PROOF_DOMAIN: ByteArray =
    "rotki-companion-device-proof/v1".encodeToByteArray()
private const val U_SHORT_MAX: Int = 65_535
