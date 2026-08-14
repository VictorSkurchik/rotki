package org.rotki.mobile.auth.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.rotki.mobile.core.protocol.CompanionJson
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthDtoContractTest {
    @Test
    fun `five auth success examples decode through auth-owned envelopes`() {
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse<DeviceSessionEnvelopeDto>("register_device_session")
                .result.deviceSession
                .toDomain(),
        )
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse<DeviceSessionEnvelopeDto>("rename_current_device_session")
                .result.deviceSession
                .toDomain(),
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
    fun `quoted JSON scalars are rejected across auth responses`() {
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

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `auth envelope descriptors retain their pre-seam identity`() {
        assertEnvelopeDescriptor(
            DeviceSessionEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.DeviceSessionEnvelopeDto",
        )
        assertEnvelopeDescriptor(
            RevokedEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.RevokedEnvelopeDto",
        )
        assertEnvelopeDescriptor(
            ChallengeEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.ChallengeEnvelopeDto",
        )
        assertEnvelopeDescriptor(
            AccessSessionEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.AccessSessionEnvelopeDto",
        )
    }

    private fun assertEnvelopeDescriptor(
        descriptor: SerialDescriptor,
        expectedSerialName: String,
    ) {
        assertEquals(expectedSerialName, descriptor.serialName)
        assertEquals(
            listOf("result", "message"),
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )
    }

    private inline fun <reified T> decodeResponse(id: String): T {
        val response: JsonObject =
            ProtocolFixtureData
                .successExample(id)
                .getValue("response")
                .jsonObject
        return CompanionJson.decodeFromJsonElement(response)
    }

    private fun responseText(id: String): String =
        ProtocolFixtureData.successExample(id).getValue("response").toString()
}
