package org.rotki.mobile.auth.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthDtoContractTest {
    @Test
    fun `non-pairing auth success examples decode through auth-owned envelopes`() {
        assertTrue(
            decodeResponse("revoke_current_device_session", RevokedEnvelopeDto.serializer())
                .result.revoked,
        )
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse("create_challenge", ChallengeEnvelopeDto.serializer()).result.toDomain(),
        )
        assertIs<AuthContractOutcome.Accepted<*>>(
            decodeResponse("create_access_session", AccessSessionEnvelopeDto.serializer())
                .result
                .toDomain(),
        )
    }

    @Test
    fun `quoted JSON scalars are rejected across auth responses`() {
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("revoke_current_device_session")
                    .replace("\"revoked\":true", "\"revoked\":\"true\""),
                RevokedEnvelopeDto.serializer(),
            )
        }
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("create_challenge")
                    .replace("\"expires_at\":1786550400", "\"expires_at\":\"1786550400\""),
                ChallengeEnvelopeDto.serializer(),
            )
        }
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("create_access_session")
                    .replace("\"expires_at\":1786551300", "\"expires_at\":\"1786551300\""),
                AccessSessionEnvelopeDto.serializer(),
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `auth envelope descriptors retain their pre-seam identity`() {
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
