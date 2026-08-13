package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.rotki.mobile.core.network.CompanionJson
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import org.rotki.mobile.core.protocol.hasDuplicateJsonMember
import org.rotki.mobile.core.protocol.hasValidJsonSyntax

internal object CompanionEnvelopeDecoder {
    internal fun <T> decodeSuccess(
        text: String,
        deserializer: DeserializationStrategy<T>,
    ): CompanionEnvelopeDecodeOutcome<T> = decode(
        text = text,
        expectedShape = CompanionEnvelopeShape.SUCCESS,
        deserializer = deserializer,
    )

    internal fun decodeFailure(
        text: String,
    ): CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> = decode(
        text = text,
        expectedShape = CompanionEnvelopeShape.FAILURE,
        deserializer = CompanionFailureEnvelopeDto.serializer(),
    )

    private fun <T> decode(
        text: String,
        expectedShape: CompanionEnvelopeShape,
        deserializer: DeserializationStrategy<T>,
    ): CompanionEnvelopeDecodeOutcome<T> {
        val envelope = preflight(text)
            ?: return CompanionEnvelopeDecodeOutcome.ContractFailure
        if (!envelope.matches(expectedShape)) {
            return CompanionEnvelopeDecodeOutcome.ContractFailure
        }
        val decoded = try {
            CompanionJson.decodeFromJsonElement(deserializer, envelope)
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
            CompanionJson.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal sealed interface CompanionEnvelopeDecodeOutcome<out T> {
    data class Decoded<T>(internal val value: T) : CompanionEnvelopeDecodeOutcome<T>

    data object ContractFailure : CompanionEnvelopeDecodeOutcome<Nothing>
}

private enum class CompanionEnvelopeShape {
    SUCCESS,
    FAILURE,
}

private fun JsonObject.matches(shape: CompanionEnvelopeShape): Boolean {
    if (!containsKey("result")) return false
    return when (shape) {
        CompanionEnvelopeShape.SUCCESS ->
            this["result"] != JsonNull &&
                !containsKey("error") &&
                (this["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content == ""
        CompanionEnvelopeShape.FAILURE -> this["result"] == JsonNull && this["error"] is JsonObject
    }
}
