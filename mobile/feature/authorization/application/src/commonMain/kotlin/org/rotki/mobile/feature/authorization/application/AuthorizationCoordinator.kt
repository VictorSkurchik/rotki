@file:OptIn(
    kotlin.experimental.ExperimentalObjCRefinement::class,
    kotlinx.coroutines.InternalCoroutinesApi::class,
)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
import org.rotki.mobile.core.protocol.generated.ProtocolLifetimesSeconds
import org.rotki.mobile.core.protocol.generated.ProtocolRetryPolicy
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

    @HiddenFromObjC
    public data object Closed : AuthorizationCoordinatorOutcome
}

/**
 * Cancellable absolute-deadline seam used by renewal and expiry scheduling.
 *
 * Implementations may wake early. The coordinator always reads [Clock] again before acting.
 */
@HiddenFromObjC
public fun interface AuthorizationDeadlineWaiter {
    public suspend fun waitUntil(deadlineEpochSeconds: Long): Unit
}

/**
 * Owns process-memory Access authority, exact expiry, and challenge/proof acquisition and renewal.
 *
 * Exchange jobs belong to a private supervisor whose lifetime is bound to the injected process
 * scope, so cancellation of an arbitrary waiter does not cancel joined work. Native lifecycle
 * delivery, facade mapping, and authenticated request delegation remain later authorization slices.
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
    processScope: CoroutineScope,
    private val deadlineWaiter: AuthorizationDeadlineWaiter = DefaultAuthorizationDeadlineWaiter(clock),
) {
    init {
        require(selectedProtocolVersion > 0) { "Selected protocol version must be positive" }
    }

    private val lifecycleMutex: Mutex = Mutex()
    private val stateMutex: Mutex = Mutex()
    private val processJob: Job =
        requireNotNull(processScope.coroutineContext[Job]) {
            "Authorization process scope must contain a Job"
        }
    private val processCancellationStarted: CompletableDeferred<Unit> = CompletableDeferred()
    private val processCancellationHandle =
        processJob.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) {
            processCancellationStarted.complete(Unit)
        }
    private val coordinatorJob: Job = SupervisorJob()
    private val coordinatorScope: CoroutineScope =
        CoroutineScope(processScope.coroutineContext + coordinatorJob)
    private val scheduledSession: MutableStateFlow<ScheduledAccessSession?> = MutableStateFlow(null)
    private var generation: Long = 0
    private var nextSessionRevision: Long = 0
    private var inFlight: AuthorizationFlight? = null
    private var accessSession: InstalledAccessSession? = null
    private var closed: Boolean = false

    init {
        coordinatorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeVisibility()
        }
        coordinatorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeRenewalDeadlines()
        }
        coordinatorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            observeExpiryDeadlines()
        }
        coordinatorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            processCancellationStarted.await()
            closeFromProcessScope()
        }
    }

    public suspend fun authorize(): AuthorizationCoordinatorOutcome {
        val admission =
            lifecycleMutex.withLock {
                val admitted = stateMutex.withLock { admitAuthorizationLocked() }
                admitted.cancelledJob?.cancelAndJoin()
                admitted
            }
        admission.cachedOutcome?.let { return it }
        val flight = requireNotNull(admission.flight)
        flight.start()
        return flight.result.await()
    }

    /** Drops process authority and fences any late exchange result from installing a bearer. */
    public suspend fun clearAccessSession(): Unit =
        invalidateAuthorization(
            dropAccessSession = true,
            outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
        )

    /** Coarse inspection only; the bearer itself is never returned. */
    public suspend fun hasActiveAccessSession(): Boolean =
        lifecycleMutex.withLock {
            val inspection = stateMutex.withLock { inspectAccessSessionLocked() }
            inspection.cancelledJob?.cancelAndJoin()
            inspection.hasActiveAccessSession
        }

    public suspend fun close(): Unit = closeCoordinator(joinCoordinatorJob = true)

    public override fun toString(): String = "AuthorizationCoordinator(redacted)"

    private fun admitAuthorizationLocked(): AuthorizationAdmission {
        if (closed) {
            return AuthorizationAdmission.cached(AuthorizationCoordinatorOutcome.Closed)
        }
        if (!processJob.isActive) {
            return AuthorizationAdmission.cachedWithCancellation(
                AuthorizationCoordinatorOutcome.Closed,
                invalidateAuthorizationLocked(
                    dropAccessSession = true,
                    outcome = AuthorizationCoordinatorOutcome.Closed,
                ),
            )
        }
        when (applicationVisibility.state.value) {
            ApplicationVisibilityState.INACTIVE -> {
                return AuthorizationAdmission.cachedWithCancellation(
                    AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    invalidateAuthorizationLocked(
                        dropAccessSession = false,
                        outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    ),
                )
            }

            ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                return AuthorizationAdmission.cachedWithCancellation(
                    AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    invalidateAuthorizationLocked(
                        dropAccessSession = true,
                        outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    ),
                )
            }

            ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
            }
        }
        inFlight?.let { currentFlight -> return AuthorizationAdmission.join(currentFlight) }
        val current = accessSession
        if (current == null) {
            return AuthorizationAdmission.join(startFlightLocked())
        }
        return when (
            AuthorizationSessionPolicy.decide(
                nowEpochSeconds = clock.nowEpochSeconds(),
                sessionExpiresAtEpochSeconds = current.session.expiresAtEpochSeconds,
                isActiveForeground = true,
                isRenewalInFlight = false,
            )
        ) {
            AuthorizationSessionDecision.KEEP_CURRENT_SESSION -> {
                AuthorizationAdmission.cached(
                    AuthorizationCoordinatorOutcome.Authorized(
                        current.session.expiresAtEpochSeconds,
                    ),
                )
            }

            AuthorizationSessionDecision.START_SINGLE_FLIGHT_RENEWAL -> {
                AuthorizationAdmission.join(
                    startFlightLocked(),
                )
            }

            AuthorizationSessionDecision.SESSION_EXPIRED -> {
                removeAccessSessionLocked()
                AuthorizationAdmission.join(
                    startFlightLocked(),
                )
            }

            AuthorizationSessionDecision.RENEWAL_ALREADY_IN_FLIGHT,
            AuthorizationSessionDecision.OUTSIDE_ACTIVE_FOREGROUND,
            -> {
                error("Authorization admission supplied inconsistent renewal state")
            }
        }
    }

    private fun inspectAccessSessionLocked(): AccessSessionInspection {
        if (closed) return AccessSessionInspection.inactive()
        if (!processJob.isActive) {
            return AccessSessionInspection.inactive(
                invalidateAuthorizationLocked(
                    dropAccessSession = true,
                    outcome = AuthorizationCoordinatorOutcome.Closed,
                ),
            )
        }
        when (applicationVisibility.state.value) {
            ApplicationVisibilityState.INACTIVE -> {
                return AccessSessionInspection.inactive(
                    invalidateAuthorizationLocked(
                        dropAccessSession = false,
                        outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    ),
                )
            }

            ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                return AccessSessionInspection.inactive(
                    invalidateAuthorizationLocked(
                        dropAccessSession = true,
                        outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    ),
                )
            }

            ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
            }
        }
        val current = accessSession ?: return AccessSessionInspection.inactive()
        if (current.session.expiresAtEpochSeconds <= clock.nowEpochSeconds()) {
            removeAccessSessionLocked()
            return AccessSessionInspection.inactive()
        }
        return AccessSessionInspection.active()
    }

    private fun startFlightLocked(): AuthorizationFlight {
        check(inFlight == null) { "Authorization exchange is already in flight" }
        val flight = AuthorizationFlight(generation)
        val job =
            coordinatorScope.launch(start = CoroutineStart.LAZY) {
                runFlight(flight)
            }
        flight.attach(job)
        inFlight = flight
        return flight
    }

    private suspend fun runFlight(flight: AuthorizationFlight) {
        try {
            val outcome =
                try {
                    authorizeOwned(flight.generation)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    AuthorizationCoordinatorOutcome.UnexpectedFailure
                }
            val governedOutcome = applyAuthorityFailurePolicy(flight, outcome)
            stateMutex.withLock {
                if (inFlight === flight) inFlight = null
            }
            flight.result.complete(governedOutcome)
        } catch (cancellation: CancellationException) {
            if (!flight.result.isCompleted) flight.result.cancel(cancellation)
            throw cancellation
        } finally {
            stateMutex.withLock {
                if (inFlight === flight) inFlight = null
            }
        }
    }

    private suspend fun authorizeOwned(flightGeneration: Long): AuthorizationCoordinatorOutcome {
        var challengeRecoveryCount = 0
        while (true) {
            val outcome = performExchange(flightGeneration)
            val shouldRequestFreshChallenge =
                outcome is AuthorizationCoordinatorOutcome.RemoteFailure &&
                    outcome.failure == AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE &&
                    challengeRecoveryCount < CHALLENGE_UNAVAILABLE_FRESH_EXCHANGE_BUDGET
            if (!shouldRequestFreshChallenge || !isCurrentActiveFlight(flightGeneration)) {
                return outcome
            }
            challengeRecoveryCount += 1
        }
    }

    private suspend fun performExchange(flightGeneration: Long): AuthorizationCoordinatorOutcome {
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
            is DeviceProofPublicKeyOutcome.PublicKey -> {
            }

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
            if (closed ||
                !processJob.isActive ||
                generation != flightGeneration ||
                applicationVisibility.state.value != ApplicationVisibilityState.ACTIVE_FOREGROUND
            ) {
                AuthorizationCoordinatorOutcome.OutsideActiveForeground
            } else if (session.expiresAtEpochSeconds <= clock.nowEpochSeconds()) {
                AuthorizationCoordinatorOutcome.SessionExpired
            } else {
                nextSessionRevision += 1
                val installed = InstalledAccessSession(nextSessionRevision, session)
                accessSession = installed
                scheduledSession.value = installed.toSchedule()
                AuthorizationCoordinatorOutcome.Authorized(session.expiresAtEpochSeconds)
            }
        }

    private suspend fun applyAuthorityFailurePolicy(
        flight: AuthorizationFlight,
        outcome: AuthorizationCoordinatorOutcome,
    ): AuthorizationCoordinatorOutcome {
        if (!outcome.destroysAccessAuthority()) return outcome
        stateMutex.withLock {
            if (!closed && generation == flight.generation && accessSession != null) {
                generation += 1
                removeAccessSessionLocked()
            }
        }
        return outcome
    }

    private suspend fun observeVisibility() {
        applicationVisibility.state.collect { visibility ->
            when (visibility) {
                ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
                }

                ApplicationVisibilityState.INACTIVE -> {
                    invalidateAuthorization(
                        dropAccessSession = false,
                        outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    )
                }

                ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                    invalidateAuthorization(
                        dropAccessSession = true,
                        outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                    )
                }
            }
        }
    }

    private suspend fun observeRenewalDeadlines() {
        combine(applicationVisibility.state, scheduledSession) { visibility, session ->
            visibility to session
        }.collectLatest { (visibility, session) ->
            if (visibility == ApplicationVisibilityState.ACTIVE_FOREGROUND && session != null) {
                maintainScheduledRenewal(session)
            }
        }
    }

    private suspend fun maintainScheduledRenewal(session: ScheduledAccessSession) {
        waitUntil(
            (session.expiresAtEpochSeconds - ProtocolLifetimesSeconds.ProactiveRenewalWindow)
                .coerceAtLeast(0),
        )
        if (!isScheduledSessionUsable(session)) return
        var outcome = requestScheduledRenewal(session) ?: return
        repeat(PROACTIVE_RENEWAL_AUTOMATIC_RETRY_BUDGET) {
            val retryDelaySeconds = outcome.proactiveRetryDelaySeconds() ?: return
            val retryDeadline = saturatingAdd(clock.nowEpochSeconds(), retryDelaySeconds)
            waitUntil(retryDeadline)
            if (!isScheduledSessionUsable(session)) return
            outcome = requestScheduledRenewal(session) ?: return
        }
    }

    private suspend fun observeExpiryDeadlines() {
        scheduledSession.collectLatest { session ->
            if (session != null) {
                waitUntil(session.expiresAtEpochSeconds)
                expireScheduledSession(session)
            }
        }
    }

    private suspend fun requestScheduledRenewal(session: ScheduledAccessSession): AuthorizationCoordinatorOutcome? {
        val flight =
            lifecycleMutex.withLock {
                stateMutex.withLock stateLock@{
                    if (!isScheduledSessionUsableLocked(session)) return@stateLock null
                    inFlight ?: startFlightLocked()
                }
            } ?: return null
        flight.start()
        return flight.result.await()
    }

    private suspend fun expireScheduledSession(session: ScheduledAccessSession) {
        stateMutex.withLock {
            val current = accessSession
            if (current?.revision == session.revision &&
                clock.nowEpochSeconds() >= current.session.expiresAtEpochSeconds
            ) {
                removeAccessSessionLocked()
            }
        }
    }

    private suspend fun isScheduledSessionUsable(session: ScheduledAccessSession): Boolean =
        stateMutex.withLock {
            isScheduledSessionUsableLocked(session)
        }

    private fun isScheduledSessionUsableLocked(session: ScheduledAccessSession): Boolean {
        val current = accessSession
        return !closed &&
            processJob.isActive &&
            applicationVisibility.state.value == ApplicationVisibilityState.ACTIVE_FOREGROUND &&
            current?.revision == session.revision &&
            current.session.expiresAtEpochSeconds > clock.nowEpochSeconds()
    }

    private suspend fun waitUntil(deadlineEpochSeconds: Long) {
        while (clock.nowEpochSeconds() < deadlineEpochSeconds) {
            deadlineWaiter.waitUntil(deadlineEpochSeconds)
        }
    }

    private suspend fun invalidateAuthorization(
        dropAccessSession: Boolean,
        outcome: AuthorizationCoordinatorOutcome,
    ): Unit =
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                val cancellation =
                    stateMutex.withLock {
                        if (closed) return@withLock null
                        invalidateAuthorizationLocked(dropAccessSession, outcome)
                    }
                cancellation?.cancelAndJoin()
            }
        }

    private suspend fun closeFromProcessScope(): Unit = closeCoordinator(joinCoordinatorJob = false)

    private suspend fun closeCoordinator(joinCoordinatorJob: Boolean) {
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                var performTeardown = false
                val cancellation =
                    stateMutex.withLock {
                        if (closed) return@withLock null
                        closed = true
                        performTeardown = true
                        invalidateAuthorizationLocked(
                            dropAccessSession = true,
                            outcome = AuthorizationCoordinatorOutcome.Closed,
                        )
                    }
                if (!performTeardown) return@withLock
                cancellation?.cancelAndJoin()
                processCancellationHandle.dispose()
                coordinatorJob.cancel()
                remoteGateway.close()
            }
            if (joinCoordinatorJob) coordinatorJob.join()
        }
    }

    private fun invalidateAuthorizationLocked(
        dropAccessSession: Boolean,
        outcome: AuthorizationCoordinatorOutcome,
    ): Job? {
        generation += 1
        if (dropAccessSession) removeAccessSessionLocked()
        val flight = inFlight
        inFlight = null
        flight?.invalidate(outcome)
        return flight?.job
    }

    private fun removeAccessSessionLocked() {
        accessSession = null
        scheduledSession.value = null
    }

    private suspend fun isCurrentActiveFlight(flightGeneration: Long): Boolean =
        stateMutex.withLock {
            !closed &&
                processJob.isActive &&
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

private fun AuthorizationCoordinatorOutcome.destroysAccessAuthority(): Boolean =
    when (this) {
        AuthorizationCoordinatorOutcome.PairingRequired -> {
            true
        }

        is AuthorizationCoordinatorOutcome.RemoteFailure -> {
            failure == AuthorizationRemoteFailure.NOT_AUTHORIZED ||
                failure == AuthorizationRemoteFailure.PROFILE_MISMATCH ||
                failure == AuthorizationRemoteFailure.LOCKED_ENGINE ||
                failure == AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL
        }

        else -> {
            false
        }
    }

private fun AuthorizationCoordinatorOutcome.proactiveRetryDelaySeconds(): Long? =
    when (this) {
        AuthorizationCoordinatorOutcome.NetworkUnavailable -> {
            PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS
        }

        is AuthorizationCoordinatorOutcome.RemoteFailure -> {
            if (failure == AuthorizationRemoteFailure.RATE_LIMITED) {
                retryAfterSeconds?.takeIf { seconds ->
                    seconds in 0..ProtocolRetryPolicy.MaximumRetryAfterSeconds
                }
            } else {
                null
            }
        }

        else -> {
            null
        }
    }

private class AuthorizationFlight(
    val generation: Long,
    val result: CompletableDeferred<AuthorizationCoordinatorOutcome> = CompletableDeferred(),
) {
    lateinit var job: Job
        private set

    fun attach(job: Job) {
        check(!this::job.isInitialized) { "Authorization flight already has a job" }
        this.job = job
        job.invokeOnCompletion { failure ->
            if (result.isCompleted) return@invokeOnCompletion
            if (failure is CancellationException) {
                result.cancel(failure)
            } else {
                result.complete(AuthorizationCoordinatorOutcome.UnexpectedFailure)
            }
        }
    }

    fun start() {
        job.start()
    }

    fun invalidate(outcome: AuthorizationCoordinatorOutcome) {
        result.complete(outcome)
        job.cancel()
    }
}

private data class InstalledAccessSession(
    val revision: Long,
    val session: AccessSession,
) {
    fun toSchedule(): ScheduledAccessSession = ScheduledAccessSession(revision, session.expiresAtEpochSeconds)
}

private data class ScheduledAccessSession(
    val revision: Long,
    val expiresAtEpochSeconds: Long,
)

private class AuthorizationAdmission private constructor(
    val flight: AuthorizationFlight?,
    val cachedOutcome: AuthorizationCoordinatorOutcome?,
    val cancelledJob: Job?,
) {
    companion object {
        fun cached(outcome: AuthorizationCoordinatorOutcome): AuthorizationAdmission =
            AuthorizationAdmission(flight = null, cachedOutcome = outcome, cancelledJob = null)

        fun cachedWithCancellation(
            outcome: AuthorizationCoordinatorOutcome,
            cancelledJob: Job?,
        ): AuthorizationAdmission =
            AuthorizationAdmission(
                flight = null,
                cachedOutcome = outcome,
                cancelledJob = cancelledJob,
            )

        fun join(flight: AuthorizationFlight): AuthorizationAdmission =
            AuthorizationAdmission(flight, cachedOutcome = null, cancelledJob = null)
    }
}

private data class AccessSessionInspection(
    val hasActiveAccessSession: Boolean,
    val cancelledJob: Job?,
) {
    companion object {
        fun active(): AccessSessionInspection =
            AccessSessionInspection(hasActiveAccessSession = true, cancelledJob = null)

        fun inactive(cancelledJob: Job? = null): AccessSessionInspection =
            AccessSessionInspection(hasActiveAccessSession = false, cancelledJob = cancelledJob)
    }
}

private class DefaultAuthorizationDeadlineWaiter(
    private val clock: Clock,
) : AuthorizationDeadlineWaiter {
    override suspend fun waitUntil(deadlineEpochSeconds: Long) {
        val remainingSeconds = (deadlineEpochSeconds - clock.nowEpochSeconds()).coerceAtLeast(0)
        delay(
            saturatingMultiplyByOneThousand(
                remainingSeconds.coerceAtMost(CLOCK_RECHECK_INTERVAL_SECONDS),
            ),
        )
    }
}

private fun saturatingAdd(
    value: Long,
    increment: Long,
): Long = if (increment > 0 && value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

private fun saturatingMultiplyByOneThousand(value: Long): Long =
    if (value > Long.MAX_VALUE / MILLISECONDS_PER_SECOND) {
        Long.MAX_VALUE
    } else {
        value * MILLISECONDS_PER_SECOND
    }

internal const val CHALLENGE_UNAVAILABLE_FRESH_EXCHANGE_BUDGET: Int = 1
internal const val PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS: Long = 2
internal const val PROACTIVE_RENEWAL_AUTOMATIC_RETRY_BUDGET: Int = 1
private const val CLOCK_RECHECK_INTERVAL_SECONDS: Long = 1
private const val MILLISECONDS_PER_SECOND: Long = 1_000
