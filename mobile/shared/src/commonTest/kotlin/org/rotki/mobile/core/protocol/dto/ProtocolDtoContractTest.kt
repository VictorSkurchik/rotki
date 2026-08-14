package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.generated.ProtocolCapability
import org.rotki.mobile.core.protocol.generated.SUPPORTED_PROTOCOL_VERSIONS
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolDtoContractTest {
    @Test
    fun `protocol discovery example decodes through handwritten DTOs`() {
        val protocol = decodeResponse("get_protocol", ProtocolDiscoveryEnvelopeDto.serializer())
        assertEquals(
            1,
            assertIs<ProtocolNegotiationOutcome.Compatible>(
                protocol.result.negotiate(SUPPORTED_PROTOCOL_VERSIONS),
            ).selectedVersion,
        )
        assertTrue(
            ProtocolCapability.DeviceSessions in
                assertIs<ProtocolNegotiationOutcome.Compatible>(
                    protocol.result.negotiate(SUPPORTED_PROTOCOL_VERSIONS),
                ).availableCapabilities,
        )
    }

    @Test
    fun `unknown additive response fields decode but missing required fields fail`() {
        val original =
            ProtocolFixtureData
                .successExample("get_protocol")
                .getValue("response")
                .toString()
        val additive = original.dropLast(1) + ",\"future_hint\":true}"
        val missing = original.replace(",\"message\":\"\"", "")

        ProtocolFixtureData.decodeCompanionJson(additive, ProtocolDiscoveryEnvelopeDto.serializer())
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(missing, ProtocolDiscoveryEnvelopeDto.serializer())
        }
    }

    @Test
    fun `quoted JSON scalars are rejected in protocol discovery`() {
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("get_protocol").replace("[1]", "[\"1\"]"),
                ProtocolDiscoveryEnvelopeDto.serializer(),
            )
        }
    }

    @Test
    fun `protocol negotiation fails closed on invalid versions and device-session capability`() {
        assertIs<ProtocolNegotiationOutcome.Incompatible>(
            ProtocolDiscoveryResultDto(listOf(2), mapOf("device_sessions" to 1))
                .negotiate(setOf(1)),
        )
        assertIs<ProtocolNegotiationOutcome.Incompatible>(
            ProtocolDiscoveryResultDto(listOf(1), mapOf("future" to 1))
                .negotiate(setOf(1)),
        )
        assertIs<ProtocolNegotiationOutcome.ContractFailure>(
            ProtocolDiscoveryResultDto(listOf(0, 1), mapOf("device_sessions" to 1))
                .negotiate(setOf(1)),
        )
    }

    private fun <T> decodeResponse(
        id: String,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val response: JsonObject =
            ProtocolFixtureData
                .successExample(id)
                .getValue("response")
                .jsonObject
        return CompanionJsonCodec.decodeFromJsonElement(deserializer, response)
    }

    private fun responseText(id: String): String =
        ProtocolFixtureData.successExample(id).getValue("response").toString()
}
