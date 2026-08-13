package org.rotki.mobile.core.decimal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

public object ExactDecimalStringSerializer : KSerializer<ExactDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExactDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExactDecimal): Unit =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): ExactDecimal =
        ExactDecimal.parse(decoder.decodeString())
}
