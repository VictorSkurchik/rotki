package org.rotki.mobile.core.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ProtocolPrimitivesTest {
    private val proof = ProtocolFixtureData.golden.getValue("device_proof").jsonObject

    @Test
    fun `fixed-width values accept canonical vectors and redact themselves`() {
        val device = assertIs<ProtocolValueParseOutcome.Accepted<DeviceSessionId>>(
            DeviceSessionId.parse(proof.string("device_session_id")),
        ).value
        val signature = assertIs<ProtocolValueParseOutcome.Accepted<P1363Signature>>(
            P1363Signature.parse(proof.string("signature")),
        ).value
        val publicKey = assertIs<ProtocolValueParseOutcome.Accepted<X963PublicKey>>(
            X963PublicKey.parse(proof.string("public_key")),
        ).value

        assertEquals("DeviceSessionId(redacted)", device.toString())
        assertEquals("P1363Signature(redacted)", signature.toString())
        assertEquals("X963PublicKey(redacted)", publicKey.toString())
        assertEquals(32, device.bytesCopy().size)
        assertEquals(64, signature.bytesCopy().size)
        assertEquals(65, publicKey.bytesCopy().size)
    }

    @Test
    fun `noncanonical alternate and malformed forms fail closed`() {
        val canonicalDevice = proof.string("device_session_id")
        assertIs<ProtocolValueParseOutcome.Rejected>(DeviceSessionId.parse("$canonicalDevice="))
        assertIs<ProtocolValueParseOutcome.Rejected>(DeviceSessionId.parse(canonicalDevice.dropLast(1)))
        assertIs<ProtocolValueParseOutcome.Rejected>(
            DeviceSessionId.parse(canonicalDevice.dropLast(1) + "/"),
        )
        assertIs<ProtocolValueParseOutcome.Rejected>(P1363Signature.fromBytes(ByteArray(64)))
        assertEquals(
            ProtocolValueRejection.INVALID_LENGTH,
            assertIs<ProtocolValueParseOutcome.Rejected>(
                P1363Signature.fromBytes(ByteArray(1_000_000)),
            ).reason,
        )
        assertIs<ProtocolValueParseOutcome.Rejected>(X963PublicKey.fromBytes(ByteArray(65)))
        assertIs<ProtocolValueParseOutcome.Rejected>(
            X963PublicKey.fromBytes(ByteArray(65).also { bytes -> bytes[0] = 0x04 }),
        )
        assertIs<ProtocolValueParseOutcome.Rejected>(IdempotencyKey.fromBytes(ByteArray(15)))
        assertEquals(
            ProtocolValueRejection.INVALID_LENGTH,
            assertIs<ProtocolValueParseOutcome.Rejected>(
                X963PublicKey.fromBytes(ByteArray(1_000_000)),
            ).reason,
        )
    }

    @Test
    fun `byte factories encode defensively and round trip`() {
        val source = ByteArray(16) { index -> index.toByte() }
        val key = assertIs<ProtocolValueParseOutcome.Accepted<IdempotencyKey>>(
            IdempotencyKey.fromBytes(source),
        ).value
        val beforeMutation = key.bytesCopy()
        source.fill(0x7f)

        assertContentEquals(beforeMutation, key.bytesCopy())
        assertNotEquals(source.toList(), key.bytesCopy().toList())
        assertEquals(key, assertIs<ProtocolValueParseOutcome.Accepted<IdempotencyKey>>(
            IdempotencyKey.parse(key.encoded),
        ).value)
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    getValue(name).jsonPrimitive.content
