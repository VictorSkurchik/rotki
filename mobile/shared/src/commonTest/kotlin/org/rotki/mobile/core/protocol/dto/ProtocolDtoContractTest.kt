package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.auth.protocol.AuthContractOutcome
import org.rotki.mobile.auth.protocol.toDomain
import org.rotki.mobile.core.network.CompanionJson
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
    fun `six mobile-owned success examples decode through handwritten DTOs`() {
        val protocol = decodeResponse<ProtocolDiscoveryEnvelopeDto>("get_protocol")
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
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse<DeviceSessionEnvelopeDto>("register_device_session")
                .result.deviceSession.toDomain(),
        )
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse<DeviceSessionEnvelopeDto>("rename_current_device_session")
                .result.deviceSession.toDomain(),
        )
        assertTrue(decodeResponse<RevokedEnvelopeDto>("revoke_current_device_session").result.revoked)
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse<ChallengeEnvelopeDto>("create_challenge").result.toDomain(),
        )
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse<AccessSessionEnvelopeDto>("create_access_session").result.toDomain(),
        )
    }

    @Test
    fun `unknown additive response fields decode but missing required fields fail`() {
        val original = ProtocolFixtureData.successExample("get_protocol")
            .getValue("response")
            .toString()
        val additive = original.dropLast(1) + ",\"future_hint\":true}"
        val missing = original.replace(",\"message\":\"\"", "")

        CompanionJson.decodeFromString<ProtocolDiscoveryEnvelopeDto>(additive)
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<ProtocolDiscoveryEnvelopeDto>(missing)
        }
    }

    @Test
    fun `quoted JSON scalars are rejected across auth and control responses`() {
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<ProtocolDiscoveryEnvelopeDto>(
                responseText("get_protocol").replace("[1]", "[\"1\"]"),
            )
        }
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<DeviceSessionEnvelopeDto>(
                responseText("register_device_session")
                    .replace("\"paired_at\":1786550300", "\"paired_at\":\"1786550300\""),
            )
        }
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<RevokedEnvelopeDto>(
                responseText("revoke_current_device_session")
                    .replace("\"revoked\":true", "\"revoked\":\"true\""),
            )
        }
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<ChallengeEnvelopeDto>(
                responseText("create_challenge")
                    .replace("\"expires_at\":1786550400", "\"expires_at\":\"1786550400\""),
            )
        }
        assertFailsWith<SerializationException> {
            CompanionJson.decodeFromString<AccessSessionEnvelopeDto>(
                responseText("create_access_session")
                    .replace("\"expires_at\":1786551300", "\"expires_at\":\"1786551300\""),
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

    private inline fun <reified T> decodeResponse(id: String): T {
        val response: JsonObject = ProtocolFixtureData.successExample(id)
            .getValue("response")
            .jsonObject
        return CompanionJson.decodeFromJsonElement(response)
    }

    private fun responseText(id: String): String =
        ProtocolFixtureData.successExample(id).getValue("response").toString()
}
