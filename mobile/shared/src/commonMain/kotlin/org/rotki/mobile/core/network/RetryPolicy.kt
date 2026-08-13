package org.rotki.mobile.core.network

import kotlin.math.min
import kotlin.random.Random
import org.rotki.mobile.core.protocol.generated.ProtocolRetryPolicy

internal enum class RequestReplayPolicy {
    SAFE_READ,
    IDEMPOTENT_WRITE,
    NEVER,
}

internal sealed interface RetryFailure {
    data object PreResponseTransport : RetryFailure

    data class HttpResponse(
        internal val statusCode: Int,
        internal val typedError: TypedErrorRetryDisposition = TypedErrorRetryDisposition.NOT_PRESENT,
        internal val retryAfterSeconds: Long? = null,
    ) : RetryFailure

    data object CompleteResponseTransport : RetryFailure

    data object ContractViolation : RetryFailure
}

internal enum class TypedErrorRetryDisposition {
    NOT_PRESENT,
    RETRYABLE,
    NOT_RETRYABLE,
    USER_ACTION_REQUIRED,
    UNEXPECTED_ENGINE_ERROR,
}

internal sealed interface RetryDecision {
    data class RetryAfter(
        internal val delayMillis: Long,
    ) : RetryDecision

    data class Stop(
        internal val reason: RetryStopReason,
    ) : RetryDecision
}

internal enum class RetryStopReason {
    REQUEST_NOT_REPLAYABLE,
    FAILURE_NOT_RETRYABLE,
    ATTEMPT_BUDGET_EXHAUSTED,
    OUTSIDE_ACTIVE_FOREGROUND,
    CREDENTIAL_UNAVAILABLE,
    RETRY_AFTER_TOO_LONG,
}

internal fun interface RetryJitterSource {
    fun nextLong(boundExclusive: Long): Long
}

internal object DefaultRetryJitterSource : RetryJitterSource {
    override fun nextLong(boundExclusive: Long): Long = Random.nextLong(boundExclusive)
}

internal class RetryPolicy(
    private val jitterSource: RetryJitterSource = DefaultRetryJitterSource,
) {
    internal fun decide(
        replayPolicy: RequestReplayPolicy,
        completedAttempts: Int,
        failure: RetryFailure,
        isActiveForeground: Boolean,
        isCredentialAvailable: Boolean,
    ): RetryDecision {
        require(completedAttempts >= 1) { "At least one attempt must have completed" }

        if (!isActiveForeground) {
            return RetryDecision.Stop(RetryStopReason.OUTSIDE_ACTIVE_FOREGROUND)
        }
        if (!isCredentialAvailable) {
            return RetryDecision.Stop(RetryStopReason.CREDENTIAL_UNAVAILABLE)
        }
        if (replayPolicy == RequestReplayPolicy.NEVER) {
            return RetryDecision.Stop(RetryStopReason.REQUEST_NOT_REPLAYABLE)
        }
        if (!failure.isRetryable()) {
            return RetryDecision.Stop(RetryStopReason.FAILURE_NOT_RETRYABLE)
        }

        val maximumAttempts = when (replayPolicy) {
            RequestReplayPolicy.SAFE_READ -> ProtocolRetryPolicy.SafeGetMaximumAttempts
            RequestReplayPolicy.IDEMPOTENT_WRITE ->
                ProtocolRetryPolicy.IdempotentWriteMaximumAttempts
            RequestReplayPolicy.NEVER -> ProtocolRetryPolicy.ChallengeOrProofMaximumAttempts
        }
        if (completedAttempts >= maximumAttempts) {
            return RetryDecision.Stop(RetryStopReason.ATTEMPT_BUDGET_EXHAUSTED)
        }

        val responseFailure = failure as? RetryFailure.HttpResponse
        val retryAfterSeconds = responseFailure
            ?.takeIf { response -> response.statusCode == HTTP_TOO_MANY_REQUESTS }
            ?.retryAfterSeconds
        if (retryAfterSeconds != null &&
            retryAfterSeconds > ProtocolRetryPolicy.MaximumRetryAfterSeconds
        ) {
            return RetryDecision.Stop(RetryStopReason.RETRY_AFTER_TOO_LONG)
        }
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            return RetryDecision.RetryAfter(retryAfterSeconds * MILLIS_PER_SECOND)
        }

        return RetryDecision.RetryAfter(fullJitterDelayMillis(completedAttempts))
    }

    internal fun fullJitterDelayMillis(retryOrdinal: Int): Long {
        require(retryOrdinal >= 1) { "Retry ordinal must be positive" }
        var exponentialWindow = ProtocolRetryPolicy.BaseDelayMilliseconds
        var remainingDoublings = retryOrdinal - 1
        while (remainingDoublings > 0 &&
            exponentialWindow < ProtocolRetryPolicy.MaximumDelayMilliseconds
        ) {
            exponentialWindow = min(
                exponentialWindow * 2,
                ProtocolRetryPolicy.MaximumDelayMilliseconds,
            )
            remainingDoublings -= 1
        }
        return jitterSource.nextLong(exponentialWindow + 1)
    }

    private fun RetryFailure.isRetryable(): Boolean = when (this) {
        RetryFailure.PreResponseTransport -> true
        RetryFailure.CompleteResponseTransport,
        RetryFailure.ContractViolation,
        -> false
        is RetryFailure.HttpResponse ->
            statusCode in ProtocolRetryPolicy.RetryableHttpStatuses &&
                (typedError == TypedErrorRetryDisposition.RETRYABLE ||
                    (typedError == TypedErrorRetryDisposition.NOT_PRESENT &&
                        statusCode in ENVELOPE_OPTIONAL_GATEWAY_STATUSES))
    }

    private companion object {
        private const val MILLIS_PER_SECOND: Long = 1_000
        private const val HTTP_TOO_MANY_REQUESTS: Int = 429
        private val ENVELOPE_OPTIONAL_GATEWAY_STATUSES: Set<Int> = setOf(408, 502, 504)
    }
}
