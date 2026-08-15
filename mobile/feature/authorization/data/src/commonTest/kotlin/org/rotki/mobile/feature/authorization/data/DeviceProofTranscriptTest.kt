package org.rotki.mobile.feature.authorization.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class DeviceProofTranscriptTest {
    private val proof: JsonObject = ProtocolFixtureData.golden.getValue("device_proof").jsonObject

    @Test
    fun `golden proof transcript is assembled byte for byte`() {
        val transcript =
            encodeDeviceProofTranscript(
                engineOrigin = TestAuthorizationValues.origin(),
                deviceSessionId = TestAuthorizationValues.deviceSessionId(),
                challenge =
                    TestAuthorizationValues.challenge(
                        proof
                            .getValue("expires_at")
                            .jsonPrimitive.content
                            .toLong(),
                    ),
            )

        assertContentEquals(proof.string("transcript_hex").hexToByteArray(), transcript)
    }

    @Test
    fun `each bound transcript field changes the golden bytes`() {
        val golden =
            encodeDeviceProofTranscript(
                TestAuthorizationValues.origin(),
                TestAuthorizationValues.deviceSessionId(),
                TestAuthorizationValues.challenge(),
            )
        val alteredOrigin =
            encodeDeviceProofTranscript(
                acceptedOrigin("https://other.example"),
                TestAuthorizationValues.deviceSessionId(),
                TestAuthorizationValues.challenge(),
            )
        val alteredDeviceSession =
            encodeDeviceProofTranscript(
                TestAuthorizationValues.origin(),
                TestAuthorizationValues.alternateDeviceSessionId(),
                TestAuthorizationValues.challenge(),
            )
        val alteredChallengeId =
            encodeDeviceProofTranscript(
                TestAuthorizationValues.origin(),
                TestAuthorizationValues.deviceSessionId(),
                TestAuthorizationValues.challenge(
                    challengeId = TestAuthorizationValues.alternateChallengeId(),
                ),
            )
        val alteredNonce =
            encodeDeviceProofTranscript(
                TestAuthorizationValues.origin(),
                TestAuthorizationValues.deviceSessionId(),
                TestAuthorizationValues.challenge(
                    nonce = TestAuthorizationValues.alternateNonce(),
                ),
            )
        val alteredExpiry =
            encodeDeviceProofTranscript(
                TestAuthorizationValues.origin(),
                TestAuthorizationValues.deviceSessionId(),
                TestAuthorizationValues.challenge(1_786_550_401),
            )

        assertFalse(golden.contentEquals(alteredOrigin))
        assertFalse(golden.contentEquals(alteredDeviceSession))
        assertFalse(golden.contentEquals(alteredChallengeId))
        assertFalse(golden.contentEquals(alteredNonce))
        assertFalse(golden.contentEquals(alteredExpiry))
    }
}

private fun acceptedOrigin(value: String) =
    requireNotNull(
        (
            org.rotki.mobile.core.protocol.EngineOrigin
                .parse(value) as?
                org.rotki.mobile.core.protocol.EngineOriginParseOutcome.Accepted
        )?.origin,
    )

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
