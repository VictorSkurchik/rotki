package org.rotki.mobile.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRenewalPolicyTest {
    @Test
    fun allAuthoredRenewalPolicyCasesExecuteUnchanged() {
        val cases =
            ProtocolFixtureData.clientPolicy
                .getValue("renewal_policy")
                .jsonObject
                .getValue("cases")
                .jsonArray
        assertEquals(5, cases.size)

        cases.forEach { element ->
            val case = element.jsonObject
            val actual =
                SessionRenewalPolicy.decide(
                    nowEpochSeconds = NOW,
                    sessionExpiresAtEpochSeconds = NOW + case.long("seconds_until_expiry"),
                    isActiveForeground = case.boolean("active_foreground"),
                    isRenewalInFlight = case.boolean("renewal_in_flight"),
                )
            val expected =
                when (case.string("decision")) {
                    "keep_current_session" -> {
                        SessionRenewalDecision.KEEP_CURRENT_SESSION
                    }

                    "start_single_flight_renewal" -> {
                        SessionRenewalDecision.START_SINGLE_FLIGHT_RENEWAL
                    }

                    "renewal_already_in_flight" -> {
                        SessionRenewalDecision.RENEWAL_ALREADY_IN_FLIGHT
                    }

                    "session_expired" -> {
                        SessionRenewalDecision.SESSION_EXPIRED
                    }

                    "outside_active_foreground" -> {
                        SessionRenewalDecision.OUTSIDE_ACTIVE_FOREGROUND
                    }

                    else -> {
                        error("Unknown renewal decision fixture: ${case.string("decision")}")
                    }
                }
            assertEquals(expected, actual, case.string("id"))
        }
    }

    @Test
    fun startsRenewalAtExactlyFiveMinutesRemaining() {
        assertEquals(
            SessionRenewalDecision.KEEP_CURRENT_SESSION,
            decide(remainingSeconds = 301),
        )
        assertEquals(
            SessionRenewalDecision.START_SINGLE_FLIGHT_RENEWAL,
            decide(remainingSeconds = 300),
        )
        assertEquals(
            SessionRenewalDecision.START_SINGLE_FLIGHT_RENEWAL,
            decide(remainingSeconds = 1),
        )
    }

    @Test
    fun exactExpiryStopsSessionUseEvenDuringRenewal() {
        assertEquals(
            SessionRenewalDecision.SESSION_EXPIRED,
            decide(remainingSeconds = 0, isRenewalInFlight = true),
        )
        assertEquals(
            SessionRenewalDecision.SESSION_EXPIRED,
            decide(remainingSeconds = -1),
        )
    }

    @Test
    fun renewalIsSingleFlightAndForegroundOnly() {
        assertEquals(
            SessionRenewalDecision.RENEWAL_ALREADY_IN_FLIGHT,
            decide(remainingSeconds = 300, isRenewalInFlight = true),
        )
        assertEquals(
            SessionRenewalDecision.OUTSIDE_ACTIVE_FOREGROUND,
            decide(remainingSeconds = 300, isActiveForeground = false),
        )
    }

    private fun decide(
        remainingSeconds: Long,
        isActiveForeground: Boolean = true,
        isRenewalInFlight: Boolean = false,
    ): SessionRenewalDecision =
        SessionRenewalPolicy.decide(
            nowEpochSeconds = NOW,
            sessionExpiresAtEpochSeconds = NOW + remainingSeconds,
            isActiveForeground = isActiveForeground,
            isRenewalInFlight = isRenewalInFlight,
        )

    private companion object {
        private const val NOW: Long = 1_786_550_400
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.long(name: String): Long = string(name).toLong()

private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()
