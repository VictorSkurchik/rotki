package org.rotki.mobile.feature.authorization.application

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizationSessionPolicyTest {
    @Test
    fun `starts renewal at exactly five minutes remaining`() {
        assertEquals(
            AuthorizationSessionDecision.KEEP_CURRENT_SESSION,
            decide(remainingSeconds = 301),
        )
        assertEquals(
            AuthorizationSessionDecision.START_SINGLE_FLIGHT_RENEWAL,
            decide(remainingSeconds = 300),
        )
        assertEquals(
            AuthorizationSessionDecision.START_SINGLE_FLIGHT_RENEWAL,
            decide(remainingSeconds = 1),
        )
    }

    @Test
    fun `exact expiry stops session use even during renewal`() {
        assertEquals(
            AuthorizationSessionDecision.SESSION_EXPIRED,
            decide(remainingSeconds = 0, isRenewalInFlight = true),
        )
        assertEquals(
            AuthorizationSessionDecision.SESSION_EXPIRED,
            decide(remainingSeconds = -1),
        )
    }

    @Test
    fun `renewal is single flight and foreground only`() {
        assertEquals(
            AuthorizationSessionDecision.RENEWAL_ALREADY_IN_FLIGHT,
            decide(remainingSeconds = 300, isRenewalInFlight = true),
        )
        assertEquals(
            AuthorizationSessionDecision.OUTSIDE_ACTIVE_FOREGROUND,
            decide(remainingSeconds = 300, isActiveForeground = false),
        )
    }

    private fun decide(
        remainingSeconds: Long,
        isActiveForeground: Boolean = true,
        isRenewalInFlight: Boolean = false,
    ): AuthorizationSessionDecision =
        AuthorizationSessionPolicy.decide(
            nowEpochSeconds = NOW,
            sessionExpiresAtEpochSeconds = NOW + remainingSeconds,
            isActiveForeground = isActiveForeground,
            isRenewalInFlight = isRenewalInFlight,
        )

    private companion object {
        private const val NOW: Long = 1_786_550_400
    }
}
