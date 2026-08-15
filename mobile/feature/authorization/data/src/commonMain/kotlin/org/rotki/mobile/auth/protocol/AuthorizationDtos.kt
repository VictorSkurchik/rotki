package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.ChallengeNonce
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.StrictJsonLongSerializer
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge

@Serializable
internal class ChallengeRequestDto(
    @SerialName("device_session_id")
    internal val deviceSessionId: String,
) {
    internal companion object {
        internal fun create(deviceSessionId: DeviceSessionId): ChallengeRequestDto =
            ChallengeRequestDto(deviceSessionId.encoded)
    }
}

@Serializable
internal class ChallengeResultDto(
    @SerialName("challenge_id")
    internal val challengeId: String,
    @SerialName("nonce")
    internal val nonce: String,
    @SerialName("expires_at")
    internal val expiresAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long,
)

internal fun ChallengeResultDto.toDomain(): AuthContractOutcome<AuthorizationChallenge> {
    val parsedId = ChallengeId.parse(challengeId)
    val parsedNonce = ChallengeNonce.parse(nonce)
    if (parsedId !is ProtocolValueParseOutcome.Accepted ||
        parsedNonce !is ProtocolValueParseOutcome.Accepted ||
        expiresAt < 0
    ) {
        return AuthContractOutcome.ContractFailure
    }
    return AuthContractOutcome.Accepted(
        AuthorizationChallenge(parsedId.value, parsedNonce.value, expiresAt),
    )
}

@Serializable
internal class AccessSessionRequestDto(
    @SerialName("device_session_id")
    internal val deviceSessionId: String,
    @SerialName("challenge_id")
    internal val challengeId: String,
    @SerialName("signature")
    internal val signature: String,
) {
    internal companion object {
        internal fun create(
            deviceSessionId: DeviceSessionId,
            challengeId: ChallengeId,
            signature: P1363Signature,
        ): AccessSessionRequestDto =
            AccessSessionRequestDto(
                deviceSessionId = deviceSessionId.encoded,
                challengeId = challengeId.encoded,
                signature = signature.encoded,
            )
    }
}

@Serializable
internal class AccessSessionResultDto(
    @SerialName("access_session_credential")
    internal val accessSessionCredential: String,
    @SerialName("expires_at")
    internal val expiresAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long,
)

internal fun AccessSessionResultDto.toDomain(): AuthContractOutcome<AccessSession> {
    val parsedCredential = AccessSessionCredential.parse(accessSessionCredential)
    if (parsedCredential !is ProtocolValueParseOutcome.Accepted || expiresAt < 0) {
        return AuthContractOutcome.ContractFailure
    }
    return AuthContractOutcome.Accepted(AccessSession(parsedCredential.value, expiresAt))
}

internal sealed interface AuthContractOutcome<out T> {
    data class Accepted<T>(
        internal val value: T,
    ) : AuthContractOutcome<T> {
        override fun toString(): String = "Accepted(redacted)"
    }

    data object ContractFailure : AuthContractOutcome<Nothing>
}
