@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public object StrictJsonIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StrictJsonInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int =
        decoder
            .strictJsonInteger()
            .toIntOrNull()
            ?: throw SerializationException("Integer is outside the Int range")

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ): Unit = encoder.encodeInt(value)
}

@HiddenFromObjC
public object StrictJsonLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StrictJsonLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long =
        decoder
            .strictJsonInteger()
            .toLongOrNull()
            ?: throw SerializationException("Integer is outside the Long range")

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ): Unit = encoder.encodeLong(value)
}

@HiddenFromObjC
public object StrictJsonBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StrictJsonBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val primitive = decoder.strictJsonPrimitive()
        return when {
            primitive.isString -> throw SerializationException("Expected a JSON boolean")
            primitive.content == "true" -> true
            primitive.content == "false" -> false
            else -> throw SerializationException("Expected a JSON boolean")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ): Unit = encoder.encodeBoolean(value)
}

private fun Decoder.strictJsonInteger(): String {
    val primitive = strictJsonPrimitive()
    if (primitive.isString || !JSON_INTEGER.matches(primitive.content)) {
        throw SerializationException("Expected a JSON integer")
    }
    return primitive.content
}

private fun Decoder.strictJsonPrimitive(): JsonPrimitive {
    if (this !is JsonDecoder) {
        throw SerializationException("Protocol scalars require a JSON decoder")
    }
    return decodeJsonElement() as? JsonPrimitive
        ?: throw SerializationException("Expected a JSON primitive")
}

private val JSON_INTEGER: Regex = Regex("-?(?:0|[1-9][0-9]*)")
