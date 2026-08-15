@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteGateway
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import org.rotki.mobile.feature.authorization.domain.DeviceProofTranscriptEncoder
import kotlin.native.HiddenFromObjC

/** Secret-free result of one joined authorization exchange. */
@HiddenFromObjC
public sealed interface AuthorizationCoordinatorOutcome {
    @HiddenFromObjC
    public data class Authorized(
        public val expiresAtEpochSeconds: Long,
    ) : AuthorizationCoordinatorOutcome {
        public override fun toString(): String = "Authorized(redacted)"
    }

    @HiddenFromObjC
    public data class RemoteFailure(
        public val failure: AuthorizationRemoteFailure,
        public val retryAfterSeconds: Long?,
    ) : AuthorizationCoordinatorOutcome {
        public override fun toString(): String = "RemoteFailure(redacted)"
    }

    @HiddenFromObjC
    public data object OutsideActiveForeground : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object PairingRequired : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object LocalStorageUnavailable : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object LocalSecurityUnavailable : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object DeviceAuthenticationCancelled : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object SessionExpired : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object NetworkUnavailable : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object ContractFailure : AuthorizationCoordinatorOutcome

    @HiddenFromObjC
    public data object UnexpectedFailure : AuthorizationCoordinatorOutcome
}

/**
 * Coordinates one foreground challenge/proof exchange and owns the resulting bearer in memory.
 *
 * Concurrent callers join the same exchange. This C1 boundary deliberately does not implement
 * proactive renewal, request-authority delegation, or native lifecycle composition; those remain
 * later authorization slices.
 */
@HiddenFromObjC
public class AuthorizationCoordinator(
    private val remoteGateway: AuthorizationRemoteGateway,
    private val transcriptEncoder: DeviceProofTranscriptEncoder,
    private val pairingRecordStore: PairingRecordStore,
    private val deviceProofSigner: DeviceProofSigner,
    private val applicationVisibility: ApplicationVisibility,
    private val clock: Clock,
    private val selectedProtocolVersion: Int,
) {
    init {
        require(selectedProtocolVersion > 0) { "Selected protocol version must be positive" }
    }

    private val stateMutex: Mutex = Mutex()
    private var generation: Long = 0
    private var inFlight: AuthorizationFlight? = null
    private var accessSession: AccessSession? = null

    public suspend fun authorize(): AuthorizationCoordinatorOutcome {
        val admission =
            stateMutex.withLock {
                admitAuthorization()
            }
        admission.cachedOutcome?.let { return it }
        val flight = requireNotNull(admission.flight)
        if (!admission.isOwner) return flight.result.await()

        return try {
            // This boundary converts unexpected platform/adapter failures into a coarse redacted result.
            @Suppress("TooGenericExceptionCaught")
            val outcome =
                try {
                    executeOwned(flight)
                } catch (cancellation: CancellationException) {
                    flight.result.cancel(cancellation)
                    throw cancellation
                } catch (_: Exception) {
                    AuthorizationCoordinatorOutcome.UnexpectedFailure
                }
            flight.result.complete(outcome)
            outcome
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (inFlight === flight) inFlight = null
                }
            }
        }
    }

    /** Drops process authority and fences any late exchange result from installing a bearer. */
    public suspend fun clearAccessSession(): Unit =
        stateMutex.withLock {
            invalidateAuthorizationLocked()
        }

    /** Coarse inspection only; the bearer itself is never returned. */
    public suspend fun hasActiveAccessSession(): Boolean =
        stateMutex.withLock {
            val visibility = applicationVisibility.state.value
            if (visibility == ApplicationVisibilityState.BACKGROUND_OR_LOCKED) {
                invalidateAuthorizationLocked()
                return@withLock false
            }
            val current = accessSession ?: return@withLock false
            if (current.expiresAtEpochSeconds <= clock.nowEpochSeconds()) {
                accessSession = null
                return@withLock false
            }
            visibility == ApplicationVisibilityState.ACTIVE_FOREGROUND
        }

    public suspend fun close() {
        clearAccessSession()
        remoteGateway.close()
    }

    public override fun toString(): String = "AuthorizationCoordinator(redacted)"

    private fun admitAuthorization(): AuthorizationAdmission {
        val visibility = applicationVisibility.state.value
        if (visibility != ApplicationVisibilityState.ACTIVE_FOREGROUND) {
            if (visibility == ApplicationVisibilityState.BACKGROUND_OR_LOCKED) {
                invalidateAuthorizationLocked()
            }
            return AuthorizationAdmission.cached(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
            )
        }
        accessSession?.let { current ->
            if (current.expiresAtEpochSeconds > clock.nowEpochSeconds()) {
                return AuthorizationAdmission.cached(
                    AuthorizationCoordinatorOutcome.Authorized(current.expiresAtEpochSeconds),
                )
            }
            accessSession = null
        }
        inFlight?.let { current -> return AuthorizationAdmission.join(current) }
        val flight = AuthorizationFlight(generation)
        inFlight = flight
        return AuthorizationAdmission.own(flight)
    }

    private suspend fun executeOwned(flight: AuthorizationFlight): AuthorizationCoordinatorOutcome =
        coroutineScope {
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    authorizeOwned(flight.generation)
                }
            select {
                operation.onAwait { outcome -> outcome }
                flight.invalidation.onAwait {
                    operation.cancelAndJoin()
                    AuthorizationCoordinatorOutcome.OutsideActiveForeground
                }
            }
        }

    private fun invalidateAuthorizationLocked() {
        generation += 1
        accessSession = null
        inFlight?.invalidate()
        inFlight = null
    }

    private suspend fun authorizeOwned(flightGeneration: Long): AuthorizationCoordinatorOutcome {
        if (!isCurrentActiveFlight(flightGeneration)) {
            return AuthorizationCoordinatorOutcome.OutsideActiveForeground
        }
        val record =
            when (val stored = pairingRecordStore.read()) {
                PairingRecordReadOutcome.Missing -> {
                    return AuthorizationCoordinatorOutcome.PairingRequired
                }

                is PairingRecordReadOutcome.Present -> {
                    stored.record
                }

                PairingRecordReadOutcome.Corrupt,
                PairingRecordReadOutcome.Unavailable,
                -> {
                    return AuthorizationCoordinatorOutcome.LocalStorageUnavailable
                }
            }
        if (!isCurrentActiveFlight(flightGeneration)) {
            return AuthorizationCoordinatorOutcome.OutsideActiveForeground
        }
        when (deviceProofSigner.currentPublicKeyX963()) {
            is DeviceProofPublicKeyOutcome.PublicKey -> {}

            DeviceProofPublicKeyOutcome.PairingRequired -> {
                return AuthorizationCoordinatorOutcome.PairingRequired
            }

            DeviceProofPublicKeyOutcome.UnexpectedFailure -> {
                return AuthorizationCoordinatorOutcome.LocalSecurityUnavailable
            }
        }
        val challenge =
            when (
                val remote =
                    remoteGateway.requestChallenge(
                        engineOrigin = record.engineOrigin,
                        deviceSessionId = record.deviceSessionId,
                        selectedProtocolVersion = selectedProtocolVersion,
                    )
            ) {
                is AuthorizationRemoteOutcome.Success -> remote.value
                else -> return remote.toCoordinatorOutcome()
            }
        if (!isCurrentActiveFlight(flightGeneration)) {
            return AuthorizationCoordinatorOutcome.OutsideActiveForeground
        }
        return signAndSubmit(record, challenge, flightGeneration)
    }

    private suspend fun signAndSubmit(
        record: PairingRecord,
        challenge: AuthorizationChallenge,
        flightGeneration: Long,
    ): AuthorizationCoordinatorOutcome {
        val transcript =
            try {
                transcriptEncoder.encode(record.engineOrigin, record.deviceSessionId, challenge)
            } catch (_: IllegalArgumentException) {
                return AuthorizationCoordinatorOutcome.ContractFailure
            }
        val signature =
            try {
                when (val signing = deviceProofSigner.sign(transcript)) {
                    is DeviceProofSigningOutcome.Signed -> {
                        signing.signature
                    }

                    DeviceProofSigningOutcome.DeviceAuthenticationCancelled -> {
                        return AuthorizationCoordinatorOutcome.DeviceAuthenticationCancelled
                    }

                    DeviceProofSigningOutcome.DeviceAuthenticationUnavailable,
                    DeviceProofSigningOutcome.UnexpectedFailure,
                    -> {
                        return AuthorizationCoordinatorOutcome.LocalSecurityUnavailable
                    }

                    DeviceProofSigningOutcome.PairingRequired -> {
                        return AuthorizationCoordinatorOutcome.PairingRequired
                    }
                }
            } finally {
                transcript.fill(0)
            }
        if (!isCurrentActiveFlight(flightGeneration)) {
            return AuthorizationCoordinatorOutcome.OutsideActiveForeground
        }
        val session =
            when (
                val remote =
                    remoteGateway.submitProof(
                        engineOrigin = record.engineOrigin,
                        deviceSessionId = record.deviceSessionId,
                        challengeId = challenge.id,
                        signature = signature,
                        selectedProtocolVersion = selectedProtocolVersion,
                    )
            ) {
                is AuthorizationRemoteOutcome.Success -> remote.value
                else -> return remote.toCoordinatorOutcome()
            }
        return installSession(session, flightGeneration)
    }

    private suspend fun installSession(
        session: AccessSession,
        flightGeneration: Long,
    ): AuthorizationCoordinatorOutcome =
        stateMutex.withLock {
            if (generation != flightGeneration ||
                applicationVisibility.state.value != ApplicationVisibilityState.ACTIVE_FOREGROUND
            ) {
                AuthorizationCoordinatorOutcome.OutsideActiveForeground
            } else if (session.expiresAtEpochSeconds <= clock.nowEpochSeconds()) {
                AuthorizationCoordinatorOutcome.SessionExpired
            } else {
                accessSession = session
                AuthorizationCoordinatorOutcome.Authorized(session.expiresAtEpochSeconds)
            }
        }

    private suspend fun isCurrentActiveFlight(flightGeneration: Long): Boolean =
        stateMutex.withLock {
            generation == flightGeneration &&
                applicationVisibility.state.value == ApplicationVisibilityState.ACTIVE_FOREGROUND
        }
}

private fun AuthorizationRemoteOutcome<*>.toCoordinatorOutcome(): AuthorizationCoordinatorOutcome =
    when (this) {
        is AuthorizationRemoteOutcome.Rejected -> {
            AuthorizationCoordinatorOutcome.RemoteFailure(failure, retryAfterSeconds)
        }

        AuthorizationRemoteOutcome.ContractFailure -> {
            AuthorizationCoordinatorOutcome.ContractFailure
        }

        AuthorizationRemoteOutcome.PreResponseTransportFailure,
        AuthorizationRemoteOutcome.CompleteResponseTransportFailure,
        -> {
            AuthorizationCoordinatorOutcome.NetworkUnavailable
        }

        is AuthorizationRemoteOutcome.Success -> {
            error("A success must be handled before failure mapping")
        }
    }

private class AuthorizationFlight(
    val generation: Long,
    val result: CompletableDeferred<AuthorizationCoordinatorOutcome> = CompletableDeferred(),
) {
    val invalidation: CompletableDeferred<Unit> = CompletableDeferred()

    fun invalidate() {
        invalidation.complete(Unit)
    }
}

private class AuthorizationAdmission private constructor(
    val flight: AuthorizationFlight?,
    val isOwner: Boolean,
    val cachedOutcome: AuthorizationCoordinatorOutcome?,
) {
    companion object {
        fun cached(outcome: AuthorizationCoordinatorOutcome): AuthorizationAdmission =
            AuthorizationAdmission(flight = null, isOwner = false, cachedOutcome = outcome)

        fun join(flight: AuthorizationFlight): AuthorizationAdmission =
            AuthorizationAdmission(flight, isOwner = false, cachedOutcome = null)

        fun own(flight: AuthorizationFlight): AuthorizationAdmission =
            AuthorizationAdmission(flight, isOwner = true, cachedOutcome = null)
    }
}
