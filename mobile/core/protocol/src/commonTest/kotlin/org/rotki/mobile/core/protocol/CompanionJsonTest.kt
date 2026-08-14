package org.rotki.mobile.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
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
                StrictFixtureSerializer,
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
                override val descriptor = StrictFixtureSerializer.descriptor

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

    private data class StrictFixture(
        val required: String,
        val requiredNullable: String?,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private object StrictFixtureSerializer : KSerializer<StrictFixture> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("StrictFixture") {
                element<String>("required")
                element("required_nullable", String.serializer().nullable.descriptor)
            }

        override fun serialize(
            encoder: Encoder,
            value: StrictFixture,
        ) {
            encoder.encodeStructure(descriptor) {
                encodeStringElement(descriptor, 0, value.required)
                encodeNullableSerializableElement(
                    descriptor,
                    1,
                    String.serializer().nullable,
                    value.requiredNullable,
                )
            }
        }

        override fun deserialize(decoder: Decoder): StrictFixture {
            var required: String? = null
            var requiredSeen = false
            var requiredNullable: String? = null
            var requiredNullableSeen = false
            decoder.decodeStructure(descriptor) {
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> {
                            break
                        }

                        0 -> {
                            required = decodeStringElement(descriptor, index)
                            requiredSeen = true
                        }

                        1 -> {
                            requiredNullable =
                                decodeNullableSerializableElement(
                                    descriptor,
                                    index,
                                    String.serializer().nullable,
                                )
                            requiredNullableSeen = true
                        }

                        else -> {
                            invalidFixture("Unexpected fixture member index")
                        }
                    }
                }
            }
            if (!requiredSeen || !requiredNullableSeen) {
                invalidFixture("Missing required fixture member")
            }
            return StrictFixture(
                required = required ?: invalidFixture("Missing required fixture member"),
                requiredNullable = requiredNullable,
            )
        }

        private fun invalidFixture(message: String): Nothing = throw SerializationException(message)
    }

    private fun decodeStrictFixture(text: String): StrictFixture =
        CompanionJsonCodec.decodeFromJsonElement(
            StrictFixtureSerializer,
            CompanionJsonCodec.parseToJsonElement(text),
        )
}
