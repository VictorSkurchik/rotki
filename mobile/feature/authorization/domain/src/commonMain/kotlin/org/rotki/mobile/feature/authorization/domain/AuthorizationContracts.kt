@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.feature.authorization.domain

import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.ChallengeNonce
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.P1363Signature
import kotlin.native.HiddenFromObjC

/** Validated, single-use challenge material returned by the Engine. */
@HiddenFromObjC
public class AuthorizationChallenge(
    public val id: ChallengeId,
    public val nonce: ChallengeNonce,
    public val expiresAtEpochSeconds: Long,
) {
    init {
        require(expiresAtEpochSeconds >= 0) { "Challenge expiry must be non-negative" }
    }

    public override fun toString(): String = "AuthorizationChallenge(redacted)"
}

/** A short-lived Access Session accepted at the strict wire boundary. */
@HiddenFromObjC
public class AccessSession(
    public val credential: AccessSessionCredential,
    public val expiresAtEpochSeconds: Long,
) {
    init {
        require(expiresAtEpochSeconds >= 0) { "Access Session expiry must be non-negative" }
    }

    public override fun toString(): String = "AccessSession(redacted)"
}

/** Route-specific, redacted failures that application policy can handle without wire DTOs. */
@HiddenFromObjC
public enum class AuthorizationRemoteFailure {
    INVALID_REQUEST,
    NOT_AUTHORIZED,
    PROFILE_MISMATCH,
    CHALLENGE_UNAVAILABLE,
    LOCKED_ENGINE,
    INCOMPATIBLE_PROTOCOL,
    RATE_LIMITED,
    UNEXPECTED_ENGINE_ERROR,
}

/** One exact challenge or proof request result. Neither operation is replayed automatically. */
@HiddenFromObjC
public sealed interface AuthorizationRemoteOutcome<out T> {
    @HiddenFromObjC
    public data class Success<T>(
        public val value: T,
    ) : AuthorizationRemoteOutcome<T> {
        public override fun toString(): String = "Success(redacted)"
    }

    @HiddenFromObjC
    public data class Rejected(
        public val failure: AuthorizationRemoteFailure,
        public val retryAfterSeconds: Long?,
    ) : AuthorizationRemoteOutcome<Nothing> {
        public override fun toString(): String = "Rejected(redacted)"
    }

    @HiddenFromObjC
    public data object ContractFailure : AuthorizationRemoteOutcome<Nothing>

    @HiddenFromObjC
    public data object PreResponseTransportFailure : AuthorizationRemoteOutcome<Nothing>

    @HiddenFromObjC
    public data object CompleteResponseTransportFailure : AuthorizationRemoteOutcome<Nothing>
}

/** Consumer-owned, Ktor-free boundary for the two non-replayable authorization requests. */
@HiddenFromObjC
public interface AuthorizationRemoteGateway {
    public suspend fun requestChallenge(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        selectedProtocolVersion: Int,
    ): AuthorizationRemoteOutcome<AuthorizationChallenge>

    public suspend fun submitProof(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        challengeId: ChallengeId,
        signature: P1363Signature,
        selectedProtocolVersion: Int,
    ): AuthorizationRemoteOutcome<AccessSession>

    public fun close(): Unit
}

/** Exact transcript construction stays substitutable and owned by the data boundary. */
@HiddenFromObjC
public fun interface DeviceProofTranscriptEncoder {
    public fun encode(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        challenge: AuthorizationChallenge,
    ): ByteArray
}
