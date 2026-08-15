package org.rotki.mobile.feature.authorization.application

import org.rotki.mobile.core.protocol.generated.ProtocolLifetimesSeconds

internal enum class AuthorizationSessionDecision {
    KEEP_CURRENT_SESSION,
    START_SINGLE_FLIGHT_RENEWAL,
    RENEWAL_ALREADY_IN_FLIGHT,
    SESSION_EXPIRED,
    OUTSIDE_ACTIVE_FOREGROUND,
}

internal object AuthorizationSessionPolicy {
    internal fun decide(
        nowEpochSeconds: Long,
        sessionExpiresAtEpochSeconds: Long,
        isActiveForeground: Boolean,
        isRenewalInFlight: Boolean,
    ): AuthorizationSessionDecision {
        require(nowEpochSeconds >= 0) { "Current epoch seconds must not be negative" }
        require(sessionExpiresAtEpochSeconds >= 0) { "Session expiry must not be negative" }

        if (!isActiveForeground) {
            return AuthorizationSessionDecision.OUTSIDE_ACTIVE_FOREGROUND
        }
        if (nowEpochSeconds >= sessionExpiresAtEpochSeconds) {
            return AuthorizationSessionDecision.SESSION_EXPIRED
        }
        if (isRenewalInFlight) {
            return AuthorizationSessionDecision.RENEWAL_ALREADY_IN_FLIGHT
        }
        if (sessionExpiresAtEpochSeconds - nowEpochSeconds <=
            ProtocolLifetimesSeconds.ProactiveRenewalWindow
        ) {
            return AuthorizationSessionDecision.START_SINGLE_FLIGHT_RENEWAL
        }
        return AuthorizationSessionDecision.KEEP_CURRENT_SESSION
    }
}
