@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecodeOutcome
import org.rotki.mobile.core.protocol.dto.CompanionEnvelopeDecoder
import org.rotki.mobile.core.protocol.dto.CompanionFailureEnvelopeDto
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import kotlin.native.HiddenFromObjC

internal suspend fun <T> HttpStatement.executeCompanionSuccessEnvelope(
    deserializer: DeserializationStrategy<T>,
): CompanionEnvelopeDecodeOutcome<T> =
    execute { response ->
        response.decodeCompanionSuccessEnvelope(deserializer)
    }

internal suspend fun HttpStatement.executeCompanionFailureEnvelope():
    CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> =
    execute { response ->
        response.decodeCompanionFailureEnvelope()
    }

/**
 * Executes this statement exactly once and decodes either its expected success envelope or its
 * typed failure envelope from the same bounded response body.
 *
 * The response-started distinction is important for replay policy: an idempotent request may be
 * repeated after a pre-response transport failure, while a body failure after headers is
 * deliberately ambiguous and must not be replayed automatically.
 *
 * The Ktor transport boundary must classify every non-cancellation client failure.
 */
@Suppress("TooGenericExceptionCaught")
@HiddenFromObjC
public suspend fun <T> HttpStatement.executeCompanionResponse(
    expectedSuccessStatusCode: Int,
    deserializer: DeserializationStrategy<T>,
    requiredSuccessCacheControl: String? = null,
): CompanionHttpResponseOutcome<T> {
    var responseStarted = false
    return try {
        execute { response ->
            responseStarted = true
            val statusCode = response.status.value
            if (!response.hasStrictJsonContentType() ||
                (
                    statusCode == expectedSuccessStatusCode &&
                        requiredSuccessCacheControl != null &&
                        !response.hasCanonicalHeader(
                            HttpHeaders.CacheControl,
                            requiredSuccessCacheControl,
                        )
                )
            ) {
                response.bodyAsChannel().cancel()
                CompanionHttpResponseOutcome.ContractFailure(statusCode)
            } else if (statusCode == expectedSuccessStatusCode) {
                when (val decoded = response.decodeCompanionSuccessEnvelope(deserializer)) {
                    is CompanionEnvelopeDecodeOutcome.Decoded -> {
                        CompanionHttpResponseOutcome.Success(decoded.value)
                    }

                    CompanionEnvelopeDecodeOutcome.ContractFailure -> {
                        CompanionHttpResponseOutcome.ContractFailure(statusCode)
                    }
                }
            } else {
                when (val decoded = response.decodeCompanionFailureEnvelope()) {
                    is CompanionEnvelopeDecodeOutcome.Decoded -> {
                        val retryAfterSeconds = response.retryAfterSeconds(statusCode)
                        if (statusCode == HTTP_TOO_MANY_REQUESTS && retryAfterSeconds == null) {
                            CompanionHttpResponseOutcome.ContractFailure(statusCode)
                        } else {
                            CompanionHttpResponseOutcome.Failure(
                                statusCode = statusCode,
                                envelope = decoded.value,
                                retryAfterSeconds = retryAfterSeconds,
                            )
                        }
                    }

                    CompanionEnvelopeDecodeOutcome.ContractFailure -> {
                        CompanionHttpResponseOutcome.ContractFailure(statusCode)
                    }
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        if (!isTransportFailure(error)) throw error
        if (responseStarted) {
            CompanionHttpResponseOutcome.CompleteResponseTransportFailure
        } else {
            CompanionHttpResponseOutcome.PreResponseTransportFailure
        }
    }
}

private fun HttpResponse.hasCanonicalHeader(
    name: String,
    expected: String,
): Boolean =
    headers.getAll(name)?.let { values ->
        values.size == 1 && values.single() == expected
    } == true

private suspend fun <T> HttpResponse.decodeCompanionSuccessEnvelope(
    deserializer: DeserializationStrategy<T>,
): CompanionEnvelopeDecodeOutcome<T> =
    when (val body = readBoundedControlResponseText()) {
        is BoundedControlResponseBodyOutcome.Accepted -> {
            CompanionEnvelopeDecoder.decodeSuccess(body.text, deserializer)
        }

        BoundedControlResponseBodyOutcome.ContractFailure -> {
            CompanionEnvelopeDecodeOutcome.ContractFailure
        }
    }

private suspend fun HttpResponse.decodeCompanionFailureEnvelope():
    CompanionEnvelopeDecodeOutcome<CompanionFailureEnvelopeDto> =
    when (val body = readBoundedControlResponseText()) {
        is BoundedControlResponseBodyOutcome.Accepted -> {
            CompanionEnvelopeDecoder.decodeFailure(body.text)
        }

        BoundedControlResponseBodyOutcome.ContractFailure -> {
            CompanionEnvelopeDecodeOutcome.ContractFailure
        }
    }

private suspend fun HttpResponse.readBoundedControlResponseText(): BoundedControlResponseBodyOutcome =
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
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return BoundedControlResponseBodyOutcome.ContractFailure
        }
    return BoundedControlResponseBodyOutcome.Accepted(text)
}

internal sealed interface BoundedControlResponseBodyOutcome {
    class Accepted(
        internal val text: String,
    ) : BoundedControlResponseBodyOutcome {
        override fun toString(): String = "Accepted(redacted)"
    }

    data object ContractFailure : BoundedControlResponseBodyOutcome
}

@HiddenFromObjC
public sealed interface CompanionHttpResponseOutcome<out T> {
    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class Success<T> internal constructor(
        public val value: T,
    ) : CompanionHttpResponseOutcome<T> {
        public override fun toString(): String = "Success(redacted)"
    }

    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class Failure internal constructor(
        public val statusCode: Int,
        public val envelope: CompanionFailureEnvelopeDto,
        public val retryAfterSeconds: Long?,
    ) : CompanionHttpResponseOutcome<Nothing>

    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class ContractFailure internal constructor(
        public val statusCode: Int,
    ) : CompanionHttpResponseOutcome<Nothing>

    @HiddenFromObjC
    public data object PreResponseTransportFailure : CompanionHttpResponseOutcome<Nothing>

    @HiddenFromObjC
    public data object CompleteResponseTransportFailure : CompanionHttpResponseOutcome<Nothing>
}

private fun HttpResponse.retryAfterSeconds(statusCode: Int): Long? {
    if (statusCode != HTTP_TOO_MANY_REQUESTS) return null
    val values = headers.getAll(HttpHeaders.RetryAfter) ?: return null
    if (values.size != 1 || !RETRY_AFTER_SECONDS.matches(values.single())) return null
    return values.single().toLongOrNull()
}

private fun HttpResponse.hasStrictJsonContentType(): Boolean =
    hasStrictJsonContentType(headers.getAll(HttpHeaders.ContentType))

internal fun hasStrictJsonContentType(values: List<String>?): Boolean {
    values ?: return false
    if (values.size != 1) return false
    val parsed =
        try {
            ContentType.parse(values.single())
        } catch (_: IllegalArgumentException) {
            return false
        }
    if (!parsed.match(ContentType.Application.Json)) return false
    val charsetParameters =
        parsed.parameters.filter { parameter ->
            parameter.name.equals("charset", ignoreCase = true)
        }
    return parsed.parameters.size == charsetParameters.size &&
        charsetParameters.size <= 1 &&
        charsetParameters.all { parameter ->
            parameter.value.equals("utf-8", ignoreCase = true)
        }
}

internal fun isTransportFailure(error: Throwable): Boolean {
    val visited = mutableListOf<Throwable>()
    var candidate: Throwable? = error
    repeat(MAXIMUM_CAUSE_DEPTH) {
        val current = candidate ?: return false
        if (visited.any { seen -> seen === current }) return false
        if (current is IOException) return true
        visited += current
        candidate = current.cause
    }
    return false
}

private const val HTTP_TOO_MANY_REQUESTS: Int = 429
private const val MAXIMUM_CAUSE_DEPTH: Int = 16
private val RETRY_AFTER_SECONDS: Regex = Regex("0|[1-9][0-9]*")
