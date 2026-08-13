package org.rotki.mobile.auth.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceProofTranscriptTest {
    private val proof = ProtocolFixtureData.golden.getValue("device_proof").jsonObject

    @Test
    fun `golden proof transcript is assembled byte for byte`() {
        val origin = assertIs<EngineOriginParseOutcome.Accepted>(
            EngineOrigin.parse(proof.string("canonical_engine_origin")),
        ).origin
        val deviceSessionId = assertIs<ProtocolValueParseOutcome.Accepted<DeviceSessionId>>(
            DeviceSessionId.parse(proof.string("device_session_id")),
        ).value
        val challenge = assertIs<AuthContractOutcome.Accepted<AuthorizationChallenge>>(
            ChallengeResultDto(
                challengeId = proof.string("challenge_id"),
                nonce = proof.string("nonce"),
                expiresAt = proof.long("expires_at"),
            ).toDomain(),
        ).value

        val transcript = encodeDeviceProofTranscript(origin, deviceSessionId, challenge)

        assertContentEquals(proof.string("transcript_hex").hexToByteArray(), transcript)
        assertEquals(proof.string("transcript_hex").length / 2, transcript.size)
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.long(name: String): Long = string(name).toLong()

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
