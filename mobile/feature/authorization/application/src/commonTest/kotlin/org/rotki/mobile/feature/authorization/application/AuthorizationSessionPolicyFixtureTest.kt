package org.rotki.mobile.feature.authorization.application

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizationSessionPolicyFixtureTest {
    @Test
    fun `all authored renewal policy cases execute unchanged`() {
        val policy =
            ProtocolFixtureData.clientPolicy
                .getValue("renewal_policy")
                .jsonObject
        assertEquals(
            PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS,
            policy.long("proactive_transport_retry_delay_seconds"),
        )
        assertEquals(
            PROACTIVE_RENEWAL_AUTOMATIC_RETRY_BUDGET.toLong(),
            policy.long("proactive_automatic_retry_budget"),
        )
        assertEquals(
            CHALLENGE_UNAVAILABLE_FRESH_EXCHANGE_BUDGET.toLong(),
            policy.long("challenge_unavailable_fresh_exchange_budget"),
        )
        val cases = policy.getValue("cases").jsonArray
        assertEquals(5, cases.size)

        cases.forEach { element ->
            val case = element.jsonObject
            val actual =
                AuthorizationSessionPolicy.decide(
                    nowEpochSeconds = NOW,
                    sessionExpiresAtEpochSeconds = NOW + case.long("seconds_until_expiry"),
                    isActiveForeground = case.boolean("active_foreground"),
                    isRenewalInFlight = case.boolean("renewal_in_flight"),
                )
            val expected =
                when (case.string("decision")) {
                    "keep_current_session" -> {
                        AuthorizationSessionDecision.KEEP_CURRENT_SESSION
                    }

                    "start_single_flight_renewal" -> {
                        AuthorizationSessionDecision.START_SINGLE_FLIGHT_RENEWAL
                    }

                    "renewal_already_in_flight" -> {
                        AuthorizationSessionDecision.RENEWAL_ALREADY_IN_FLIGHT
                    }

                    "session_expired" -> {
                        AuthorizationSessionDecision.SESSION_EXPIRED
                    }

                    "outside_active_foreground" -> {
                        AuthorizationSessionDecision.OUTSIDE_ACTIVE_FOREGROUND
                    }

                    else -> {
                        error("Unknown renewal decision fixture: ${case.string("decision")}")
                    }
                }
            assertEquals(expected, actual, case.string("id"))
        }
    }

    private companion object {
        private const val NOW: Long = 1_786_550_400
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.long(name: String): Long = string(name).toLong()

private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()
