package org.rotki.mobile.core.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrictJsonScalarSerializersTest {
    @Test
    fun decodesExactJsonScalarKindsAndRanges() {
        assertEquals(Int.MIN_VALUE, decode(StrictJsonIntSerializer, "-2147483648"))
        assertEquals(Int.MAX_VALUE, decode(StrictJsonIntSerializer, "2147483647"))
        assertEquals(Long.MIN_VALUE, decode(StrictJsonLongSerializer, "-9223372036854775808"))
        assertEquals(Long.MAX_VALUE, decode(StrictJsonLongSerializer, "9223372036854775807"))
        assertEquals(false, decode(StrictJsonBooleanSerializer, "false"))
        assertEquals(true, decode(StrictJsonBooleanSerializer, "true"))
    }

    @Test
    fun rejectsQuotedFractionalAndOutOfRangeScalars() {
        listOf(
            "\"1\"",
            "1.0",
            "1e0",
            "2147483648",
            "-2147483649",
        ).forEach { text ->
            assertFailsWith<SerializationException>(text) {
                decode(StrictJsonIntSerializer, text)
            }
        }

        listOf(
            "\"1\"",
            "1.0",
            "1e0",
            "9223372036854775808",
            "-9223372036854775809",
        ).forEach { text ->
            assertFailsWith<SerializationException>(text) {
                decode(StrictJsonLongSerializer, text)
            }
        }

        listOf("\"true\"", "1", "null", "{}").forEach { text ->
            assertFailsWith<SerializationException>(text) {
                decode(StrictJsonBooleanSerializer, text)
            }
        }
    }

    private fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        text: String,
    ): T =
        CompanionJsonCodec.decodeFromJsonElement(
            deserializer,
            CompanionJsonCodec.parseToJsonElement(text),
        )
}
