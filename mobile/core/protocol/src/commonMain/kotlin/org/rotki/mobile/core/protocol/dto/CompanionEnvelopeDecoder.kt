@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import org.rotki.mobile.core.protocol.hasDuplicateJsonMember
import org.rotki.mobile.core.protocol.hasValidJsonSyntax
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public object CompanionEnvelopeDecoder {
    public fun <T> decodeSuccess(
        text: String,
        deserializer: DeserializationStrategy<T>,
    ): CompanionEnvelopeDecodeOutcome<T> =
        decode(
            text = text,
            expectedShape = CompanionEnvelopeShape.SUCCESS,
            deserializer = deserializer,
        )

    public fun decodeFailure(text: String): CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> =
        decode(
            text = text,
            expectedShape = CompanionEnvelopeShape.FAILURE,
            deserializer = CompanionFailureEnvelopeDto.serializer(),
        )

    private fun <T> decode(
        text: String,
        expectedShape: CompanionEnvelopeShape,
        deserializer: DeserializationStrategy<T>,
    ): CompanionEnvelopeDecodeOutcome<T> {
        val envelope =
            preflight(text)
                ?: return CompanionEnvelopeDecodeOutcome.ContractFailure
        if (!envelope.matches(expectedShape)) {
            return CompanionEnvelopeDecodeOutcome.ContractFailure
        }
        val decoded =
            try {
                CompanionJsonCodec.decodeFromJsonElement(deserializer, envelope)
            } catch (_: SerializationException) {
                return CompanionEnvelopeDecodeOutcome.ContractFailure
            } catch (_: IllegalArgumentException) {
                return CompanionEnvelopeDecodeOutcome.ContractFailure
            }
        return CompanionEnvelopeDecodeOutcome.Decoded(decoded)
    }

    private fun preflight(text: String): JsonObject? {
        if (text.encodeToByteArray().size > ProtocolClientInputLimits.MaximumControlResponseBytes ||
            hasDuplicateJsonMember(text) ||
            !hasValidJsonSyntax(text)
        ) {
            return null
        }
        return try {
            CompanionJsonCodec.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@HiddenFromObjC
public sealed interface CompanionEnvelopeDecodeOutcome<out T> {
    @ConsistentCopyVisibility
    public data class Decoded<T> internal constructor(
        public val value: T,
    ) : CompanionEnvelopeDecodeOutcome<T> {
        public override fun toString(): String = "Decoded(redacted)"
    }

    public data object ContractFailure : CompanionEnvelopeDecodeOutcome<Nothing>
}

private enum class CompanionEnvelopeShape {
    SUCCESS,
    FAILURE,
}

private fun JsonObject.matches(shape: CompanionEnvelopeShape): Boolean {
    if (!containsKey("result")) return false
    return when (shape) {
        CompanionEnvelopeShape.SUCCESS -> {
            this["result"] != JsonNull &&
                !containsKey("error") &&
                (this["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content == ""
        }

        CompanionEnvelopeShape.FAILURE -> {
            this["result"] == JsonNull && this["error"] is JsonObject
        }
    }
}
