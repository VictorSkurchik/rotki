package org.rotki.mobile.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecodeOutcome
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecoder
import org.rotki.mobile.core.protocol.dto.CompanionFailureEnvelopeDto
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits

internal suspend fun <T> HttpStatement.executeCompanionSuccessEnvelope(
    deserializer: DeserializationStrategy<T>,
): CompanionEnvelopeDecodeOutcome<T> = execute { response ->
    response.decodeCompanionSuccessEnvelope(deserializer)
}

internal suspend fun HttpStatement.executeCompanionFailureEnvelope():
    CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> = execute { response ->
    response.decodeCompanionFailureEnvelope()
}

private suspend fun <T> HttpResponse.decodeCompanionSuccessEnvelope(
    deserializer: DeserializationStrategy<T>,
): CompanionEnvelopeDecodeOutcome<T> = when (val body = readBoundedControlResponseText()) {
    is BoundedControlResponseBodyOutcome.Accepted ->
        CompanionEnvelopeDecoder.decodeSuccess(body.text, deserializer)
    BoundedControlResponseBodyOutcome.ContractFailure ->
        CompanionEnvelopeDecodeOutcome.ContractFailure
}

private suspend fun HttpResponse.decodeCompanionFailureEnvelope():
    CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> =
    when (val body = readBoundedControlResponseText()) {
        is BoundedControlResponseBodyOutcome.Accepted ->
            CompanionEnvelopeDecoder.decodeFailure(body.text)
        BoundedControlResponseBodyOutcome.ContractFailure ->
            CompanionEnvelopeDecodeOutcome.ContractFailure
    }

private suspend fun HttpResponse.readBoundedControlResponseText():
    BoundedControlResponseBodyOutcome =
    readBoundedControlResponseText(
        declaredLength = contentLength(),
        channel = bodyAsChannel(),
    )

internal suspend fun readBoundedControlResponseText(
    declaredLength: Long?,
    channel: ByteReadChannel,
): BoundedControlResponseBodyOutcome {
    val maximumBytes = ProtocolClientInputLimits.MaximumControlResponseBytes
    if (declaredLength != null && declaredLength !in 0..maximumBytes.toLong()) {
        channel.cancel()
        return BoundedControlResponseBodyOutcome.ContractFailure
    }

    val bytes = channel.readRemaining(maximumBytes.toLong() + 1L).readByteArray()
    channel.closedCause?.let { cause -> throw cause }
    if (bytes.size > maximumBytes) {
        channel.cancel()
        return BoundedControlResponseBodyOutcome.ContractFailure
    }
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return BoundedControlResponseBodyOutcome.ContractFailure
    }
    return BoundedControlResponseBodyOutcome.Accepted(text)
}

internal sealed interface BoundedControlResponseBodyOutcome {
    class Accepted(internal val text: String) : BoundedControlResponseBodyOutcome {
        override fun toString(): String = "Accepted(redacted)"
    }

    data object ContractFailure : BoundedControlResponseBodyOutcome
}
