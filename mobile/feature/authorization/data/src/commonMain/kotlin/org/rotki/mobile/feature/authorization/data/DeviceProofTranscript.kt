@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.feature.authorization.data

import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import org.rotki.mobile.feature.authorization.domain.DeviceProofTranscriptEncoder
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public fun createDeviceProofTranscriptEncoder(): DeviceProofTranscriptEncoder = DefaultDeviceProofTranscriptEncoder

private object DefaultDeviceProofTranscriptEncoder : DeviceProofTranscriptEncoder {
    override fun encode(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        challenge: AuthorizationChallenge,
    ): ByteArray = encodeDeviceProofTranscript(engineOrigin, deviceSessionId, challenge)
}

internal fun encodeDeviceProofTranscript(
    engineOrigin: EngineOrigin,
    deviceSessionId: DeviceSessionId,
    challenge: AuthorizationChallenge,
): ByteArray {
    val origin = engineOrigin.canonical.encodeToByteArray()
    require(origin.size <= U_SHORT_MAX) { "Engine origin is too long for the proof transcript" }
    val deviceBytes = deviceSessionId.bytesCopy()
    val challengeBytes = challenge.id.bytesCopy()
    val nonceBytes = challenge.nonce.bytesCopy()
    return try {
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
        result
    } finally {
        origin.fill(0)
        deviceBytes.fill(0)
        challengeBytes.fill(0)
        nonceBytes.fill(0)
    }
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
