package org.rotki.mobile.feature.authorization.data

import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.ChallengeNonce
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import kotlin.test.assertIs

internal object TestAuthorizationValues {
    internal const val ORIGIN: String = "https://rotki.example"
    internal const val DEVICE_SESSION_ID: String =
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    internal const val CHALLENGE_ID: String = "ICEiIyQlJicoKSorLC0uLw"
    internal const val NONCE: String = "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8"
    internal const val SIGNATURE: String =
        "zKnDT8nsSEMoIxqZIzUybLt-QJJr6mtaaXm6SJMRmK7kJLpg-BP20iAJBHwLqXstAHFxaHwb_vs_jb4hSU6N8A"
    internal const val ACCESS_CREDENTIAL: String =
        "UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8"

    internal fun origin(): EngineOrigin = assertIs<EngineOriginParseOutcome.Accepted>(EngineOrigin.parse(ORIGIN)).origin

    internal fun deviceSessionId(): DeviceSessionId = accepted(DeviceSessionId.parse(DEVICE_SESSION_ID))

    internal fun alternateDeviceSessionId(): DeviceSessionId =
        accepted(DeviceSessionId.parse("B${DEVICE_SESSION_ID.drop(1)}"))

    internal fun challengeId(): ChallengeId = accepted(ChallengeId.parse(CHALLENGE_ID))

    internal fun alternateChallengeId(): ChallengeId = accepted(ChallengeId.parse("J${CHALLENGE_ID.drop(1)}"))

    internal fun nonce(): ChallengeNonce = accepted(ChallengeNonce.parse(NONCE))

    internal fun alternateNonce(): ChallengeNonce = accepted(ChallengeNonce.parse("N${NONCE.drop(1)}"))

    internal fun signature(): P1363Signature = accepted(P1363Signature.parse(SIGNATURE))

    internal fun credential(): AccessSessionCredential = accepted(AccessSessionCredential.parse(ACCESS_CREDENTIAL))

    internal fun challenge(
        expiresAtEpochSeconds: Long = 1_786_550_400,
        challengeId: ChallengeId = challengeId(),
        nonce: ChallengeNonce = nonce(),
    ): AuthorizationChallenge = AuthorizationChallenge(challengeId, nonce, expiresAtEpochSeconds)

    internal fun session(expiresAtEpochSeconds: Long = 1_786_551_300): AccessSession =
        AccessSession(credential(), expiresAtEpochSeconds)

    private fun <T> accepted(outcome: ProtocolValueParseOutcome<T>): T =
        assertIs<ProtocolValueParseOutcome.Accepted<T>>(outcome).value
}
