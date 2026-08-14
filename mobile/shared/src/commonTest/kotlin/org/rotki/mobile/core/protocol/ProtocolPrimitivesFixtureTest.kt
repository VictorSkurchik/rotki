package org.rotki.mobile.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolPrimitivesFixtureTest {
    private val proof = ProtocolFixtureData.golden.getValue("device_proof").jsonObject

    @Test
    fun `canonical device proof values parse and remain redacted`() {
        val deviceSessionId =
            assertIs<ProtocolValueParseOutcome.Accepted<DeviceSessionId>>(
                DeviceSessionId.parse(proof.string("device_session_id")),
            ).value
        val signature =
            assertIs<ProtocolValueParseOutcome.Accepted<P1363Signature>>(
                P1363Signature.parse(proof.string("signature")),
            ).value
        val publicKey =
            assertIs<ProtocolValueParseOutcome.Accepted<X963PublicKey>>(
                X963PublicKey.parse(proof.string("public_key")),
            ).value

        assertEquals(proof.string("device_session_id"), deviceSessionId.encoded)
        assertEquals(proof.string("signature"), signature.encoded)
        assertEquals(proof.string("public_key"), publicKey.encoded)
        assertEquals("DeviceSessionId(redacted)", deviceSessionId.toString())
        assertEquals("P1363Signature(redacted)", signature.toString())
        assertEquals("X963PublicKey(redacted)", publicKey.toString())
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
