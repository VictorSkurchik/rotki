package org.rotki.mobile.core.network

import org.rotki.mobile.core.protocol.generated.ProtocolLifetimesSeconds

internal enum class SessionRenewalDecision {
    KEEP_CURRENT_SESSION,
    START_SINGLE_FLIGHT_RENEWAL,
    RENEWAL_ALREADY_IN_FLIGHT,
    SESSION_EXPIRED,
    OUTSIDE_ACTIVE_FOREGROUND,
}

internal object SessionRenewalPolicy {
    internal fun decide(
        nowEpochSeconds: Long,
        sessionExpiresAtEpochSeconds: Long,
        isActiveForeground: Boolean,
        isRenewalInFlight: Boolean,
    ): SessionRenewalDecision {
        require(nowEpochSeconds >= 0) { "Current epoch seconds must not be negative" }
        require(sessionExpiresAtEpochSeconds >= 0) { "Session expiry must not be negative" }

        if (!isActiveForeground) {
            return SessionRenewalDecision.OUTSIDE_ACTIVE_FOREGROUND
        }
        if (nowEpochSeconds >= sessionExpiresAtEpochSeconds) {
            return SessionRenewalDecision.SESSION_EXPIRED
        }
        if (isRenewalInFlight) {
            return SessionRenewalDecision.RENEWAL_ALREADY_IN_FLIGHT
        }
        if (sessionExpiresAtEpochSeconds - nowEpochSeconds <=
            ProtocolLifetimesSeconds.ProactiveRenewalWindow
        ) {
            return SessionRenewalDecision.START_SINGLE_FLIGHT_RENEWAL
        }
        return SessionRenewalDecision.KEEP_CURRENT_SESSION
    }
}
