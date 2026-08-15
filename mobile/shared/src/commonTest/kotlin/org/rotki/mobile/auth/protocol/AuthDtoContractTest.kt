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
import kotlin.test.assertTrue

class AuthDtoContractTest {
    @Test
    fun `device management success decodes through its retained envelope`() {
        assertTrue(
            decodeResponse("revoke_current_device_session", RevokedEnvelopeDto.serializer())
                .result.revoked,
        )
    }

    @Test
    fun `quoted JSON boolean is rejected for device management`() {
        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                responseText("revoke_current_device_session")
                    .replace("\"revoked\":true", "\"revoked\":\"true\""),
                RevokedEnvelopeDto.serializer(),
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `device management envelope retains its pre-seam identity`() {
        assertEnvelopeDescriptor(
            RevokedEnvelopeDto.serializer().descriptor,
            "org.rotki.mobile.core.protocol.dto.RevokedEnvelopeDto",
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
