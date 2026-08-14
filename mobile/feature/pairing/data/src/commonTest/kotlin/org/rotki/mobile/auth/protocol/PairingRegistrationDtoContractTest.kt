package org.rotki.mobile.auth.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PairingRegistrationDtoContractTest {
    @Test
    fun `canonical device-session responses decode through the data-owned envelope`() {
        listOf("register_device_session", "rename_current_device_session").forEach { id ->
            val response =
                ProtocolFixtureData
                    .successExample(id)
                    .getValue("response")
                    .jsonObject
            val envelope =
                CompanionJsonCodec.decodeFromJsonElement(
                    DeviceSessionEnvelopeDto.serializer(),
                    response,
                )

            assertNotNull(envelope.result.deviceSession.toDomainOrNull(), id)
        }
    }

    @Test
    fun `quoted paired-at scalar is rejected`() {
        val response =
            ProtocolFixtureData
                .successExample("register_device_session")
                .getValue("response")
                .toString()
                .replace("\"paired_at\":1786550300", "\"paired_at\":\"1786550300\"")

        assertFailsWith<SerializationException> {
            ProtocolFixtureData.decodeCompanionJson(
                response,
                DeviceSessionEnvelopeDto.serializer(),
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `device-session envelope descriptor retains its wire identity`() {
        val descriptor = DeviceSessionEnvelopeDto.serializer().descriptor

        assertEquals(
            "org.rotki.mobile.core.protocol.dto.DeviceSessionEnvelopeDto",
            descriptor.serialName,
        )
        assertEquals(
            listOf("result", "message"),
            (0 until descriptor.elementsCount).map(descriptor::getElementName),
        )
    }

    @Test
    fun `device label and session redact user and server authority`() {
        val labelValue = "seeded-private-device-label"
        val response =
            ProtocolFixtureData
                .successExample("register_device_session")
                .getValue("response")
                .jsonObject
        val session =
            assertNotNull(
                CompanionJsonCodec
                    .decodeFromJsonElement(DeviceSessionEnvelopeDto.serializer(), response)
                    .result.deviceSession
                    .toDomainOrNull(),
            )
        val label = assertNotNull(parsePairingDeviceLabel(labelValue))

        assertEquals("DeviceLabel(redacted)", label.toString())
        assertEquals("DeviceSession(redacted)", session.toString())
        listOf(labelValue, session.id.encoded).forEach { authority ->
            assertFalse(authority in label.toString())
            assertFalse(authority in session.toString())
        }
    }
}
