@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.network

import org.rotki.mobile.core.protocol.generated.ProtocolRetryPolicy
import kotlin.math.min
import kotlin.native.HiddenFromObjC
import kotlin.random.Random

@HiddenFromObjC
public enum class RequestReplayPolicy {
    SAFE_READ,
    IDEMPOTENT_WRITE,
    NEVER,
}

@HiddenFromObjC
public sealed interface RetryFailure {
    @HiddenFromObjC
    public data object PreResponseTransport : RetryFailure

    @HiddenFromObjC
    public data class HttpResponse(
        public val statusCode: Int,
        public val typedError: TypedErrorRetryDisposition = TypedErrorRetryDisposition.NOT_PRESENT,
        public val retryAfterSeconds: Long? = null,
    ) : RetryFailure

    @HiddenFromObjC
    public data object CompleteResponseTransport : RetryFailure

    @HiddenFromObjC
    public data object ContractViolation : RetryFailure
}

@HiddenFromObjC
public enum class TypedErrorRetryDisposition {
    NOT_PRESENT,
    RETRYABLE,
    NOT_RETRYABLE,
    USER_ACTION_REQUIRED,
    UNEXPECTED_ENGINE_ERROR,
}

@HiddenFromObjC
public sealed interface RetryDecision {
    @HiddenFromObjC
    public data class RetryAfter(
        public val delayMillis: Long,
    ) : RetryDecision

    @HiddenFromObjC
    public data class Stop(
        public val reason: RetryStopReason,
    ) : RetryDecision
}

@HiddenFromObjC
public enum class RetryStopReason {
    REQUEST_NOT_REPLAYABLE,
    FAILURE_NOT_RETRYABLE,
    ATTEMPT_BUDGET_EXHAUSTED,
    OUTSIDE_ACTIVE_FOREGROUND,
    CREDENTIAL_UNAVAILABLE,
    RETRY_AFTER_TOO_LONG,
}

@HiddenFromObjC
public fun interface RetryJitterSource {
    public fun nextLong(boundExclusive: Long): Long
}

internal object DefaultRetryJitterSource : RetryJitterSource {
    override fun nextLong(boundExclusive: Long): Long = Random.nextLong(boundExclusive)
}

@HiddenFromObjC
public class RetryPolicy(
    private val jitterSource: RetryJitterSource = DefaultRetryJitterSource,
) {
    public fun decide(
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

        val maximumAttempts =
            when (replayPolicy) {
                RequestReplayPolicy.SAFE_READ -> {
                    ProtocolRetryPolicy.SafeGetMaximumAttempts
                }

                RequestReplayPolicy.IDEMPOTENT_WRITE -> {
                    ProtocolRetryPolicy.IdempotentWriteMaximumAttempts
                }

                RequestReplayPolicy.NEVER -> {
                    ProtocolRetryPolicy.ChallengeOrProofMaximumAttempts
                }
            }
        if (completedAttempts >= maximumAttempts) {
            return RetryDecision.Stop(RetryStopReason.ATTEMPT_BUDGET_EXHAUSTED)
        }

        val responseFailure = failure as? RetryFailure.HttpResponse
        val retryAfterSeconds =
            responseFailure
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
            exponentialWindow =
                min(
                    exponentialWindow * 2,
                    ProtocolRetryPolicy.MaximumDelayMilliseconds,
                )
            remainingDoublings -= 1
        }
        return jitterSource.nextLong(exponentialWindow + 1)
    }

    private fun RetryFailure.isRetryable(): Boolean =
        when (this) {
            RetryFailure.PreResponseTransport -> {
                true
            }

            RetryFailure.CompleteResponseTransport,
            RetryFailure.ContractViolation,
            -> {
                false
            }

            is RetryFailure.HttpResponse -> {
                statusCode in ProtocolRetryPolicy.RetryableHttpStatuses &&
                    (
                        typedError == TypedErrorRetryDisposition.RETRYABLE ||
                            (
                                typedError == TypedErrorRetryDisposition.NOT_PRESENT &&
                                    statusCode in ENVELOPE_OPTIONAL_GATEWAY_STATUSES
                            )
                    )
            }
        }

    private companion object {
        private const val MILLIS_PER_SECOND: Long = 1_000
        private const val HTTP_TOO_MANY_REQUESTS: Int = 429
        private val ENVELOPE_OPTIONAL_GATEWAY_STATUSES: Set<Int> = setOf(408, 502, 504)
    }
}
