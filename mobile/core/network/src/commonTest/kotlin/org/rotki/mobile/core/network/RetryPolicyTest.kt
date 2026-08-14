package org.rotki.mobile.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RetryPolicyTest {
    @Test
    fun safeReadsHaveThreeTotalAttemptsWithExponentialFullJitter() {
        val jitter = RecordingMaximumJitter()
        val policy = RetryPolicy(jitter)

        assertEquals(
            RetryDecision.RetryAfter(250),
            policy.decide(
                RequestReplayPolicy.SAFE_READ,
                completedAttempts = 1,
                failure = RetryFailure.PreResponseTransport,
                isActiveForeground = true,
                isCredentialAvailable = true,
            ),
        )
        assertEquals(
            RetryDecision.RetryAfter(500),
            policy.decide(
                RequestReplayPolicy.SAFE_READ,
                completedAttempts = 2,
                failure =
                    RetryFailure.HttpResponse(
                        503,
                        TypedErrorRetryDisposition.RETRYABLE,
                    ),
                isActiveForeground = true,
                isCredentialAvailable = true,
            ),
        )
        assertEquals(
            RetryStopReason.ATTEMPT_BUDGET_EXHAUSTED,
            assertIs<RetryDecision.Stop>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 3,
                    failure = RetryFailure.HttpResponse(504),
                    isActiveForeground = true,
                    isCredentialAvailable = true,
                ),
            ).reason,
        )
        assertEquals(listOf(251L, 501L), jitter.requestedBounds)
    }

    @Test
    fun idempotentWritesRetryOnceAndProofRequestsNeverReplay() {
        val policy = RetryPolicy(RecordingMaximumJitter())
        assertIs<RetryDecision.RetryAfter>(
            policy.decide(
                RequestReplayPolicy.IDEMPOTENT_WRITE,
                completedAttempts = 1,
                failure = RetryFailure.PreResponseTransport,
                isActiveForeground = true,
                isCredentialAvailable = true,
            ),
        )
        assertEquals(
            RetryStopReason.ATTEMPT_BUDGET_EXHAUSTED,
            assertIs<RetryDecision.Stop>(
                policy.decide(
                    RequestReplayPolicy.IDEMPOTENT_WRITE,
                    completedAttempts = 2,
                    failure = RetryFailure.PreResponseTransport,
                    isActiveForeground = true,
                    isCredentialAvailable = true,
                ),
            ).reason,
        )
        assertEquals(
            RetryStopReason.REQUEST_NOT_REPLAYABLE,
            assertIs<RetryDecision.Stop>(
                policy.decide(
                    RequestReplayPolicy.NEVER,
                    completedAttempts = 1,
                    failure = RetryFailure.PreResponseTransport,
                    isActiveForeground = true,
                    isCredentialAvailable = true,
                ),
            ).reason,
        )
    }

    @Test
    fun onlyPreResponseTransportAndAllowlistedStatusesAreEligible() {
        val policy = RetryPolicy(RecordingMaximumJitter())
        listOf(408, 502, 504).forEach { status ->
            assertIs<RetryDecision.RetryAfter>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 1,
                    failure = RetryFailure.HttpResponse(status),
                    isActiveForeground = true,
                    isCredentialAvailable = true,
                ),
                "Expected retry for $status",
            )
        }

        listOf<RetryFailure>(
            RetryFailure.CompleteResponseTransport,
            RetryFailure.ContractViolation,
            RetryFailure.HttpResponse(500),
            RetryFailure.HttpResponse(429),
            RetryFailure.HttpResponse(503),
            RetryFailure.HttpResponse(503, TypedErrorRetryDisposition.NOT_RETRYABLE),
            RetryFailure.HttpResponse(503, TypedErrorRetryDisposition.USER_ACTION_REQUIRED),
            RetryFailure.HttpResponse(503, TypedErrorRetryDisposition.UNEXPECTED_ENGINE_ERROR),
        ).forEach { failure ->
            assertEquals(
                RetryStopReason.FAILURE_NOT_RETRYABLE,
                assertIs<RetryDecision.Stop>(
                    policy.decide(
                        RequestReplayPolicy.SAFE_READ,
                        completedAttempts = 1,
                        failure = failure,
                        isActiveForeground = true,
                        isCredentialAvailable = true,
                    ),
                ).reason,
            )
        }
    }

    @Test
    fun typedErrorsRequireRetryableTrue() {
        val policy = RetryPolicy(RecordingMaximumJitter())
        listOf(429, 503).forEach { status ->
            assertIs<RetryDecision.RetryAfter>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 1,
                    failure =
                        RetryFailure.HttpResponse(
                            status,
                            TypedErrorRetryDisposition.RETRYABLE,
                        ),
                    isActiveForeground = true,
                    isCredentialAvailable = true,
                ),
            )
        }
    }

    @Test
    fun honorsOnlyBoundedRetryAfterAndCancelsForLifecycleOrCredentialLoss() {
        val policy = RetryPolicy(RecordingMaximumJitter())
        assertEquals(
            RetryDecision.RetryAfter(5_000),
            policy.decide(
                RequestReplayPolicy.SAFE_READ,
                completedAttempts = 1,
                failure =
                    RetryFailure.HttpResponse(
                        429,
                        typedError = TypedErrorRetryDisposition.RETRYABLE,
                        retryAfterSeconds = 5,
                    ),
                isActiveForeground = true,
                isCredentialAvailable = true,
            ),
        )
        assertEquals(
            RetryStopReason.RETRY_AFTER_TOO_LONG,
            assertIs<RetryDecision.Stop>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 1,
                    failure =
                        RetryFailure.HttpResponse(
                            429,
                            typedError = TypedErrorRetryDisposition.RETRYABLE,
                            retryAfterSeconds = 6,
                        ),
                    isActiveForeground = true,
                    isCredentialAvailable = true,
                ),
            ).reason,
        )
        assertEquals(
            RetryStopReason.OUTSIDE_ACTIVE_FOREGROUND,
            assertIs<RetryDecision.Stop>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 1,
                    failure = RetryFailure.PreResponseTransport,
                    isActiveForeground = false,
                    isCredentialAvailable = true,
                ),
            ).reason,
        )
        assertEquals(
            RetryStopReason.CREDENTIAL_UNAVAILABLE,
            assertIs<RetryDecision.Stop>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 1,
                    failure = RetryFailure.PreResponseTransport,
                    isActiveForeground = true,
                    isCredentialAvailable = false,
                ),
            ).reason,
        )
    }

    @Test
    fun jitterWindowIsCappedAtTwoSeconds() {
        val jitter = RecordingMaximumJitter()
        val policy = RetryPolicy(jitter)
        assertEquals(2_000, policy.fullJitterDelayMillis(retryOrdinal = 50))
        assertEquals(2_001, jitter.requestedBounds.single())
    }

    private class RecordingMaximumJitter : RetryJitterSource {
        val requestedBounds: MutableList<Long> = mutableListOf()

        override fun nextLong(boundExclusive: Long): Long {
            requestedBounds += boundExclusive
            return boundExclusive - 1
        }
    }
}
