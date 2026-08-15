package org.rotki.mobile.feature.authorization.domain

import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.ChallengeNonce
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AuthorizationContractsTest {
    @Test
    fun `challenge access and outcomes have redacted representations`() {
        val challenge = AuthorizationChallenge(challengeId(), nonce(), 1_786_550_400)
        val session = AccessSession(credential(), 1_786_551_300)
        val accepted = AuthorizationRemoteOutcome.Success(session)
        val rejected =
            AuthorizationRemoteOutcome.Rejected(
                AuthorizationRemoteFailure.NOT_AUTHORIZED,
                retryAfterSeconds = null,
            )

        assertEquals("AuthorizationChallenge(redacted)", challenge.toString())
        assertEquals("AccessSession(redacted)", session.toString())
        assertEquals("Success(redacted)", accepted.toString())
        assertEquals("Rejected(redacted)", rejected.toString())
        listOf(CHALLENGE_ID, NONCE, ACCESS_CREDENTIAL).forEach { value ->
            assertFalse(value in challenge.toString())
            assertFalse(value in session.toString())
            assertFalse(value in accepted.toString())
            assertFalse(value in rejected.toString())
        }
    }

    @Test
    fun `negative Engine expiries fail before entering application policy`() {
        assertFailsWith<IllegalArgumentException> {
            AuthorizationChallenge(challengeId(), nonce(), -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AccessSession(credential(), -1)
        }
    }

    private fun challengeId(): ChallengeId = accepted(ChallengeId.parse(CHALLENGE_ID))

    private fun nonce(): ChallengeNonce = accepted(ChallengeNonce.parse(NONCE))

    private fun credential(): AccessSessionCredential = accepted(AccessSessionCredential.parse(ACCESS_CREDENTIAL))

    private fun <T> accepted(outcome: ProtocolValueParseOutcome<T>): T =
        assertIs<ProtocolValueParseOutcome.Accepted<T>>(outcome).value
}

private const val CHALLENGE_ID: String = "ICEiIyQlJicoKSorLC0uLw"
private const val NONCE: String = "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8"
private const val ACCESS_CREDENTIAL: String =
    "UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8"
