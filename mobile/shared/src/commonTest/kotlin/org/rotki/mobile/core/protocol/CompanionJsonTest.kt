package org.rotki.mobile.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class CompanionJsonTest {
    @Test
    fun ignoresAdditiveMembersButRequiresEveryDeclaredMember() {
        val decoded =
            decodeStrictFixture(
                """{"required":"present","required_nullable":null,"future":true}""",
            )
        assertEquals("present", decoded.required)
        assertNull(decoded.requiredNullable)

        assertFailsWith<SerializationException> {
            decodeStrictFixture("""{"required":"present"}""")
        }
    }

    @Test
    fun encodesExactCompactUtf8Bytes() {
        val encoded =
            CompanionJsonCodec.encodeToByteArray(
                StrictFixture.serializer(),
                StrictFixture(required = "Grüße 🔐", requiredNullable = null),
            )

        assertContentEquals(
            """{"required":"Grüße 🔐","required_nullable":null}""".encodeToByteArray(),
            encoded,
        )
    }

    @Test
    fun propagatesEncodingFailuresWithoutWrapping() {
        val expected = SerializationException("expected encoding failure")
        val throwingSerializer =
            object : SerializationStrategy<Unit> {
                override val descriptor = StrictFixture.serializer().descriptor

                override fun serialize(
                    encoder: Encoder,
                    value: Unit,
                ): Unit = throw expected
            }

        val actual =
            assertFailsWith<SerializationException> {
                CompanionJsonCodec.encodeToByteArray(throwingSerializer, Unit)
            }

        assertSame(expected, actual)
    }

    @Test
    fun rejectsLenientJsonAndDoesNotEmbedSourceInDiagnostics() {
        val secretMarker = "must-not-enter-diagnostics"
        val failure =
            assertFailsWith<SerializationException> {
                decodeStrictFixture(
                    """{"required":"$secretMarker","required_nullable":null,}""",
                )
            }
        assertFalse(failure.message.orEmpty().contains(secretMarker))
    }

    @Serializable
    private data class StrictFixture(
        val required: String,
        @SerialName("required_nullable")
        val requiredNullable: String?,
    )

    private fun decodeStrictFixture(text: String): StrictFixture =
        CompanionJsonCodec.decodeFromJsonElement(
            StrictFixture.serializer(),
            CompanionJsonCodec.parseToJsonElement(text),
        )
}
