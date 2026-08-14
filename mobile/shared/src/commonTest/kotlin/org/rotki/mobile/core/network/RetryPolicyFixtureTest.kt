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

class RetryPolicyFixtureTest {
    @Test
    fun allAuthoredRetryPolicyCasesExecuteUnchanged() {
        val policyFixture = ProtocolFixtureData.clientPolicy.getValue("retry_policy").jsonObject
        val cases = policyFixture.getValue("cases").jsonArray
        val maximumDelayMillis = policyFixture.long("maximum_delay_milliseconds")
        assertEquals(15, cases.size)

        cases.forEach { element ->
            val case = element.jsonObject
            val policy = RetryPolicy(RecordingMaximumJitter())
            val actual =
                policy.decide(
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

                "retry_after_5000_milliseconds" -> {
                    assertEquals(RetryDecision.RetryAfter(5_000), actual, case.string("id"))
                }

                "stop_budget_exhausted" -> {
                    actual.assertStoppedFor(
                        RetryStopReason.ATTEMPT_BUDGET_EXHAUSTED,
                        case.string("id"),
                    )
                }

                "stop_not_replayable" -> {
                    actual.assertStoppedFor(
                        RetryStopReason.REQUEST_NOT_REPLAYABLE,
                        case.string("id"),
                    )
                }

                "stop_failure_not_retryable" -> {
                    actual.assertStoppedFor(
                        RetryStopReason.FAILURE_NOT_RETRYABLE,
                        case.string("id"),
                    )
                }

                "stop_retry_after_too_long" -> {
                    actual.assertStoppedFor(
                        RetryStopReason.RETRY_AFTER_TOO_LONG,
                        case.string("id"),
                    )
                }

                "stop_outside_active_foreground" -> {
                    actual.assertStoppedFor(
                        RetryStopReason.OUTSIDE_ACTIVE_FOREGROUND,
                        case.string("id"),
                    )
                }

                "stop_credential_unavailable" -> {
                    actual.assertStoppedFor(
                        RetryStopReason.CREDENTIAL_UNAVAILABLE,
                        case.string("id"),
                    )
                }

                else -> {
                    error("Unknown retry decision fixture: ${case.string("decision")}")
                }
            }
        }
    }

    private class RecordingMaximumJitter : RetryJitterSource {
        override fun nextLong(boundExclusive: Long): Long = boundExclusive - 1
    }
}

private fun JsonObject.requestReplayPolicy(): RequestReplayPolicy =
    when (string("request_kind")) {
        "safe_get" -> RequestReplayPolicy.SAFE_READ
        "idempotent_write" -> RequestReplayPolicy.IDEMPOTENT_WRITE
        "challenge_or_proof" -> RequestReplayPolicy.NEVER
        else -> error("Unknown request kind fixture: ${string("request_kind")}")
    }

private fun JsonObject.retryFailure(): RetryFailure =
    when (string("failure")) {
        "pre_response_transport" -> {
            RetryFailure.PreResponseTransport
        }

        "complete_response_transport" -> {
            RetryFailure.CompleteResponseTransport
        }

        "contract_failure" -> {
            RetryFailure.ContractViolation
        }

        "http_502_without_envelope" -> {
            RetryFailure.HttpResponse(502)
        }

        "http_503_retryable" -> {
            RetryFailure.HttpResponse(
                503,
                TypedErrorRetryDisposition.RETRYABLE,
            )
        }

        "http_503_not_retryable" -> {
            RetryFailure.HttpResponse(
                503,
                TypedErrorRetryDisposition.NOT_RETRYABLE,
            )
        }

        "http_503_user_action" -> {
            RetryFailure.HttpResponse(
                503,
                TypedErrorRetryDisposition.USER_ACTION_REQUIRED,
            )
        }

        "http_503_unexpected_engine_error" -> {
            RetryFailure.HttpResponse(
                503,
                TypedErrorRetryDisposition.UNEXPECTED_ENGINE_ERROR,
            )
        }

        "http_429_retry_after_5" -> {
            RetryFailure.HttpResponse(
                429,
                typedError = TypedErrorRetryDisposition.RETRYABLE,
                retryAfterSeconds = 5,
            )
        }

        "http_429_retry_after_6" -> {
            RetryFailure.HttpResponse(
                429,
                typedError = TypedErrorRetryDisposition.RETRYABLE,
                retryAfterSeconds = 6,
            )
        }

        else -> {
            error("Unknown retry failure fixture: ${string("failure")}")
        }
    }

private fun RetryDecision.assertStoppedFor(
    expected: RetryStopReason,
    caseId: String,
) {
    assertEquals(expected, assertIs<RetryDecision.Stop>(this, caseId).reason, caseId)
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.int(name: String): Int = string(name).toInt()

private fun JsonObject.long(name: String): Long = string(name).toLong()

private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()
