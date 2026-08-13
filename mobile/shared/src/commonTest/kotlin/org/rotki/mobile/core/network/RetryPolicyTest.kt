package org.rotki.mobile.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetryPolicyTest {
    @Test
    fun allAuthoredRetryPolicyCasesExecuteUnchanged(): Unit {
        val policyFixture = ProtocolFixtureData.clientPolicy.getValue("retry_policy").jsonObject
        val cases = policyFixture.getValue("cases").jsonArray
        val maximumDelayMillis = policyFixture.long("maximum_delay_milliseconds")
        assertEquals(15, cases.size)

        cases.forEach { element ->
            val case = element.jsonObject
            val policy = RetryPolicy(RecordingMaximumJitter())
            val actual = policy.decide(
                replayPolicy = case.requestReplayPolicy(),
                completedAttempts = case.int("completed_attempts"),
                failure = case.retryFailure(),
                isActiveForeground = case.boolean("active_foreground"),
                isCredentialAvailable = case.boolean("credential_available"),
            )

            when (case.string("decision")) {
                "retry_with_jitter" -> {
                    val retry = assertIs<RetryDecision.RetryAfter>(actual, case.string("id"))
                    assertTrue(
                        retry.delayMillis in 0..maximumDelayMillis,
                        case.string("id"),
                    )
                }
                "retry_after_5000_milliseconds" ->
                    assertEquals(RetryDecision.RetryAfter(5_000), actual, case.string("id"))
                "stop_budget_exhausted" -> actual.assertStoppedFor(
                    RetryStopReason.ATTEMPT_BUDGET_EXHAUSTED,
                    case.string("id"),
                )
                "stop_not_replayable" -> actual.assertStoppedFor(
                    RetryStopReason.REQUEST_NOT_REPLAYABLE,
                    case.string("id"),
                )
                "stop_failure_not_retryable" -> actual.assertStoppedFor(
                    RetryStopReason.FAILURE_NOT_RETRYABLE,
                    case.string("id"),
                )
                "stop_retry_after_too_long" -> actual.assertStoppedFor(
                    RetryStopReason.RETRY_AFTER_TOO_LONG,
                    case.string("id"),
                )
                "stop_outside_active_foreground" -> actual.assertStoppedFor(
                    RetryStopReason.OUTSIDE_ACTIVE_FOREGROUND,
                    case.string("id"),
                )
                "stop_credential_unavailable" -> actual.assertStoppedFor(
                    RetryStopReason.CREDENTIAL_UNAVAILABLE,
                    case.string("id"),
                )
                else -> error("Unknown retry decision fixture: ${case.string("decision")}")
            }
        }
    }

    @Test
    fun safeReadsHaveThreeTotalAttemptsWithExponentialFullJitter(): Unit {
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
                failure = RetryFailure.HttpResponse(
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
    fun idempotentWritesRetryOnceAndProofRequestsNeverReplay(): Unit {
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
    fun onlyPreResponseTransportAndAllowlistedStatusesAreEligible(): Unit {
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
    fun typedErrorsRequireRetryableTrue(): Unit {
        val policy = RetryPolicy(RecordingMaximumJitter())
        listOf(429, 503).forEach { status ->
            assertIs<RetryDecision.RetryAfter>(
                policy.decide(
                    RequestReplayPolicy.SAFE_READ,
                    completedAttempts = 1,
                    failure = RetryFailure.HttpResponse(
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
    fun honorsOnlyBoundedRetryAfterAndCancelsForLifecycleOrCredentialLoss(): Unit {
        val policy = RetryPolicy(RecordingMaximumJitter())
        assertEquals(
            RetryDecision.RetryAfter(5_000),
            policy.decide(
                RequestReplayPolicy.SAFE_READ,
                completedAttempts = 1,
                failure = RetryFailure.HttpResponse(
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
                    failure = RetryFailure.HttpResponse(
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
    fun jitterWindowIsCappedAtTwoSeconds(): Unit {
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

private fun JsonObject.requestReplayPolicy(): RequestReplayPolicy = when (string("request_kind")) {
    "safe_get" -> RequestReplayPolicy.SAFE_READ
    "idempotent_write" -> RequestReplayPolicy.IDEMPOTENT_WRITE
    "challenge_or_proof" -> RequestReplayPolicy.NEVER
    else -> error("Unknown request kind fixture: ${string("request_kind")}")
}

private fun JsonObject.retryFailure(): RetryFailure = when (string("failure")) {
    "pre_response_transport" -> RetryFailure.PreResponseTransport
    "complete_response_transport" -> RetryFailure.CompleteResponseTransport
    "contract_failure" -> RetryFailure.ContractViolation
    "http_502_without_envelope" -> RetryFailure.HttpResponse(502)
    "http_503_retryable" -> RetryFailure.HttpResponse(
        503,
        TypedErrorRetryDisposition.RETRYABLE,
    )
    "http_503_not_retryable" -> RetryFailure.HttpResponse(
        503,
        TypedErrorRetryDisposition.NOT_RETRYABLE,
    )
    "http_503_user_action" -> RetryFailure.HttpResponse(
        503,
        TypedErrorRetryDisposition.USER_ACTION_REQUIRED,
    )
    "http_503_unexpected_engine_error" -> RetryFailure.HttpResponse(
        503,
        TypedErrorRetryDisposition.UNEXPECTED_ENGINE_ERROR,
    )
    "http_429_retry_after_5" -> RetryFailure.HttpResponse(
        429,
        typedError = TypedErrorRetryDisposition.RETRYABLE,
        retryAfterSeconds = 5,
    )
    "http_429_retry_after_6" -> RetryFailure.HttpResponse(
        429,
        typedError = TypedErrorRetryDisposition.RETRYABLE,
        retryAfterSeconds = 6,
    )
    else -> error("Unknown retry failure fixture: ${string("failure")}")
}

private fun RetryDecision.assertStoppedFor(expected: RetryStopReason, caseId: String): Unit {
    assertEquals(expected, assertIs<RetryDecision.Stop>(this, caseId).reason, caseId)
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.int(name: String): Int = string(name).toInt()
private fun JsonObject.long(name: String): Long = string(name).toLong()
private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()
