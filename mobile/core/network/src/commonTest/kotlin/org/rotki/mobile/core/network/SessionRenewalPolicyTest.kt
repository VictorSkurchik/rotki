package org.rotki.mobile.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRenewalPolicyTest {
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
