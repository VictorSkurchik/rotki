@file:OptIn(
    kotlin.experimental.ExperimentalObjCRefinement::class,
    kotlinx.coroutines.InternalCoroutinesApi::class,
)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import org.rotki.mobile.core.protocol.AccessSessionAuthorizationTarget
import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.generated.ProtocolLifetimesSeconds
import org.rotki.mobile.core.protocol.generated.ProtocolRetryPolicy
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteGateway
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import org.rotki.mobile.feature.authorization.domain.DeviceProofTranscriptEncoder
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.native.HiddenFromObjC

/** Secret-free result of one joined authorization exchange. */
@HiddenFromObjC
public sealed interface AuthorizationCoordinatorOutcome {
    @HiddenFromObjC
    public data class Authorized(
        public val expiresAtEpochSeconds: Long,
        public val sessionRevision: Long,
    ) : AuthorizationCoordinatorOutcome {
        init {
            require(sessionRevision > 0) { "Authorization session revision must be positive" }
        }

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
 * scope, so cancellation of an arbitrary waiter does not cancel joined work. Opaque requests use
 * one fixed trusted executor and per-request expiry fences; native composition remains a later
 * authorization slice.
 */
@HiddenFromObjC
public class AuthorizationCoordinator(
    private val remoteGateway: AuthorizationRemoteGateway,
    private val transcriptEncoder: DeviceProofTranscriptEncoder,
    private val pairingRecordStore: PairingRecordStore,
    private val deviceProofSigner: DeviceProofSigner,
    private val applicationVisibility: ApplicationVisibility,
    private val clock: Clock,
    selectedProtocolVersion: Int,
    processScope: CoroutineScope,
    private val deadlineWaiter: AuthorizationDeadlineWaiter = DefaultAuthorizationDeadlineWaiter(clock),
    private val eventSink: AuthorizationCoordinatorEventSink = AuthorizationCoordinatorEventSink { },
    private val requestExecutor: AuthorizationRequestExecutor? = null,
) : AuthorizationProcessControl,
    AuthorizationRequestAuthority {
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
    private var selectedProtocolVersion: Int = selectedProtocolVersion
    private var nextSessionRevision: Long = 0
    private var nextExchangeId: Long = 0
    private var inFlight: AuthorizationFlight? = null
    private var accessSession: InstalledAccessSession? = null
    private val activeRequestWork: MutableSet<BoundAuthorizationRequest> = mutableSetOf()
    private var closed: Boolean = false
    private var closeInvalidation: CoordinatorAuthorizationInvalidation? = null

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

    override suspend fun authorize(): AuthorizationCoordinatorOutcome {
        ensureNotExecutorReentry()
        val admission =
            lifecycleMutex.withLock {
                stateMutex.withLock { admitAuthorizationLocked() }
            }
        finishCancellation(admission.cancellation)
        admission.cachedOutcome?.let { return it }
        val flight = requireNotNull(admission.flight)
        flight.start()
        return flight.result.await()
    }

    /** Drops process authority and fences any late exchange result before returning the handle. */
    override suspend fun beginClearAccessSession(): AuthorizationInvalidation {
        ensureNotExecutorReentry()
        return beginInvalidation(
            dropAccessSession = true,
            outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
        )
    }

    override suspend fun clearAccessSession(): Unit =
        withContext(NonCancellable) {
            beginClearAccessSession().awaitCompletion()
        }

    /** Replaces the discovery-selected version and fences work admitted under the old selection. */
    override suspend fun beginSelectProtocolVersion(selectedProtocolVersion: Int): AuthorizationInvalidation {
        ensureNotExecutorReentry()
        require(selectedProtocolVersion > 0) { "Selected protocol version must be positive" }
        return withContext(NonCancellable) {
            val cancellation =
                lifecycleMutex.withLock {
                    stateMutex.withLock {
                        if (closed ||
                            this@AuthorizationCoordinator.selectedProtocolVersion ==
                            selectedProtocolVersion
                        ) {
                            return@withLock null
                        }
                        this@AuthorizationCoordinator.selectedProtocolVersion = selectedProtocolVersion
                        invalidateAuthorizationLocked(
                            dropAccessSession = true,
                            outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                        )
                    }
                }
            cancellation?.cancel()
            CoordinatorAuthorizationInvalidation(cancellation).also { it.markFenced() }
        }
    }

    override suspend fun selectProtocolVersion(selectedProtocolVersion: Int): Unit =
        withContext(NonCancellable) {
            beginSelectProtocolVersion(selectedProtocolVersion).awaitCompletion()
        }

    /** Coarse inspection only; the bearer itself is never returned. */
    override suspend fun hasActiveAccessSession(): Boolean {
        ensureNotExecutorReentry()
        return lifecycleMutex
            .withLock {
                stateMutex.withLock { inspectAccessSessionLocked() }
            }.also { inspection -> finishCancellation(inspection.cancellation) }
            .hasActiveAccessSession
    }

    /** Executes one opaque command through the fixed data executor without returning a bearer. */
    override suspend fun <R : Any> execute(request: AuthorizationRequest<R>): AuthorizationAuthorityUseOutcome<R> {
        ensureNotExecutorReentry()
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val admission =
            lifecycleMutex.withLock {
                stateMutex.withLock { admitAuthorityUseLocked() }
            }
        finishCancellation(admission.cancellation)
        admission.unavailableOutcome?.let { return it }
        val boundRequest = requireNotNull(admission.boundRequest)
        val executor = requireNotNull(requestExecutor)
        val callerCancellation =
            callerContext[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { failure ->
                if (failure != null) {
                    boundRequest.root.cancel(
                        failure as? CancellationException
                            ?: CancellationException("Authorization request caller cancelled"),
                    )
                }
            }
        return executeBoundRequest(request, executor, boundRequest, callerContext, callerCancellation)
    }

    private suspend fun <R : Any> executeBoundRequest(
        request: AuthorizationRequest<R>,
        executor: AuthorizationRequestExecutor,
        boundRequest: BoundAuthorizationRequest,
        callerContext: kotlin.coroutines.CoroutineContext,
        callerCancellation: DisposableHandle?,
    ): AuthorizationAuthorityUseOutcome<R> {
        var work: Deferred<R>? = null
        var expiryWatcher: Job? = null
        return try {
            work =
                CoroutineScope(callerContext.minusKey(Job) + boundRequest.root)
                    .async(start = CoroutineStart.LAZY) {
                        withContext(AuthorizationExecutorContext) {
                            executor.execute(request, boundRequest.scopedCredential)
                        }
                    }
            expiryWatcher =
                coordinatorScope.launch(start = CoroutineStart.LAZY) {
                    waitUntil(boundRequest.expiresAtEpochSeconds)
                    expireBoundRequest(boundRequest)
                }
            expiryWatcher.start()
            work.start()
            AuthorizationAuthorityUseOutcome.Executed(work.await())
        } catch (cancellation: CancellationException) {
            if (!callerContext[Job].let { callerJob -> callerJob == null || callerJob.isActive }) {
                throw cancellation
            }
            if (!cancellation.isAuthorityLoss()) throw cancellation
            AuthorizationAuthorityUseOutcome.AuthorityLost
        } finally {
            withContext(NonCancellable) {
                callerCancellation?.dispose()
                expiryWatcher?.cancelAndJoin()
                if (work?.isCompleted == false) {
                    work.cancelAndJoin()
                }
                val root =
                    stateMutex.withLock {
                        activeRequestWork.remove(boundRequest)
                        boundRequest.invalidateLocked()
                        boundRequest.root.cancel()
                        boundRequest.root
                    }
                root.join()
            }
        }
    }

    override suspend fun beginClose(): AuthorizationInvalidation {
        ensureNotExecutorReentry()
        return beginCloseCoordinator()
    }

    override suspend fun close(): Unit =
        withContext(NonCancellable) {
            beginClose().awaitCompletion()
        }

    public override fun toString(): String = "AuthorizationCoordinator(redacted)"

    private suspend fun ensureNotExecutorReentry() {
        check(currentCoroutineContext()[AuthorizationExecutorContextKey] == null) {
            "Authorization executor re-entry into process authority is forbidden"
        }
    }

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
            return AuthorizationAdmission.join(
                startFlightLocked(AuthorizationExchangeKind.ACQUISITION),
            )
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
                        current.revision,
                    ),
                )
            }

            AuthorizationSessionDecision.START_SINGLE_FLIGHT_RENEWAL -> {
                AuthorizationAdmission.join(
                    startFlightLocked(AuthorizationExchangeKind.RENEWAL),
                )
            }

            AuthorizationSessionDecision.SESSION_EXPIRED -> {
                val cancellation = removeAccessSessionLocked(expired = true)
                AuthorizationAdmission.joinWithCancellation(
                    startFlightLocked(AuthorizationExchangeKind.ACQUISITION),
                    cancellation,
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
            return AccessSessionInspection.inactive(removeAccessSessionLocked(expired = true))
        }
        return AccessSessionInspection.active()
    }

    private fun admitAuthorityUseLocked(): AuthorityUseAdmission {
        if (closed || !processJob.isActive) {
            return AuthorityUseAdmission.unavailable(AuthorizationAuthorityUseOutcome.Closed)
        }
        if (applicationVisibility.state.value != ApplicationVisibilityState.ACTIVE_FOREGROUND) {
            return AuthorityUseAdmission.unavailable(
                AuthorizationAuthorityUseOutcome.OutsideActiveForeground,
            )
        }
        val current =
            accessSession ?: return AuthorityUseAdmission.unavailable(
                AuthorizationAuthorityUseOutcome.Unavailable,
            )
        if (current.session.expiresAtEpochSeconds <= clock.nowEpochSeconds()) {
            return AuthorityUseAdmission.unavailable(
                AuthorizationAuthorityUseOutcome.Unavailable,
                removeAccessSessionLocked(expired = true),
            )
        }
        if (requestExecutor == null) {
            return AuthorityUseAdmission.unavailable(AuthorizationAuthorityUseOutcome.Unavailable)
        }
        val boundRequest =
            BoundAuthorizationRequest(
                sessionRevision = current.revision,
                expiresAtEpochSeconds = current.session.expiresAtEpochSeconds,
                credential = current.session.credential,
                root = SupervisorJob(coordinatorJob),
            )
        boundRequest.scopedCredential =
            ScopedAuthorizationRequestCredential { target ->
                applyBoundRequestCredential(boundRequest, target)
            }
        activeRequestWork += boundRequest
        return AuthorityUseAdmission.admitted(boundRequest)
    }

    private suspend fun applyBoundRequestCredential(
        boundRequest: BoundAuthorizationRequest,
        target: AccessSessionAuthorizationTarget,
    ): Boolean =
        stateMutex.withLock {
            val current = accessSession
            if (boundRequest !in activeRequestWork ||
                boundRequest.credentialState != BoundCredentialState.UNUSED ||
                current?.revision != boundRequest.sessionRevision ||
                current.session.expiresAtEpochSeconds <= clock.nowEpochSeconds() ||
                applicationVisibility.state.value != ApplicationVisibilityState.ACTIVE_FOREGROUND ||
                closed ||
                !processJob.isActive
            ) {
                boundRequest.invalidateLocked()
                false
            } else {
                val credential = boundRequest.credential ?: return@withLock false
                boundRequest.markAppliedLocked()
                credential.applyTo(target)
                true
            }
        }

    private fun startFlightLocked(kind: AuthorizationExchangeKind): AuthorizationFlight {
        check(inFlight == null) { "Authorization exchange is already in flight" }
        nextExchangeId += 1
        val flight =
            AuthorizationFlight(
                id = nextExchangeId,
                generation = generation,
                kind = kind,
                selectedProtocolVersion = selectedProtocolVersion,
            )
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
            emitEvent(
                AuthorizationCoordinatorEvent.ExchangeStarted(
                    exchangeId = flight.id,
                    kind = flight.kind,
                ),
            )
            val outcome =
                try {
                    authorizeOwned(flight.generation, flight.selectedProtocolVersion)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    AuthorizationCoordinatorOutcome.UnexpectedFailure
                }
            val governed = governCompletedOutcome(flight, outcome)
            governed.cancellation?.cancel()
            emitEvent(
                AuthorizationCoordinatorEvent.ExchangeCompleted(
                    exchangeId = flight.id,
                    kind = flight.kind,
                    outcome = governed.outcome,
                ),
            )
            governed.cancellation?.join()
            stateMutex.withLock {
                if (inFlight === flight) inFlight = null
            }
            flight.result.complete(governed.outcome)
        } catch (cancellation: CancellationException) {
            if (!flight.result.isCompleted) flight.result.cancel(cancellation)
            throw cancellation
        } finally {
            stateMutex.withLock {
                if (inFlight === flight) inFlight = null
            }
        }
    }

    private suspend fun authorizeOwned(
        flightGeneration: Long,
        flightProtocolVersion: Int,
    ): AuthorizationCoordinatorOutcome {
        var challengeRecoveryCount = 0
        while (true) {
            val outcome = performExchange(flightGeneration, flightProtocolVersion)
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

    private suspend fun performExchange(
        flightGeneration: Long,
        flightProtocolVersion: Int,
    ): AuthorizationCoordinatorOutcome {
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
                        selectedProtocolVersion = flightProtocolVersion,
                    )
            ) {
                is AuthorizationRemoteOutcome.Success -> remote.value
                else -> return remote.toCoordinatorOutcome()
            }
        if (!isCurrentActiveFlight(flightGeneration)) {
            return AuthorizationCoordinatorOutcome.OutsideActiveForeground
        }
        return signAndSubmit(record, challenge, flightGeneration, flightProtocolVersion)
    }

    private suspend fun signAndSubmit(
        record: PairingRecord,
        challenge: AuthorizationChallenge,
        flightGeneration: Long,
        flightProtocolVersion: Int,
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
                        selectedProtocolVersion = flightProtocolVersion,
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
    ): AuthorizationCoordinatorOutcome {
        var unusedOldRequests: AuthorizationCancellation? = null
        val outcome =
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
                    val replacedRevision = accessSession?.revision
                    nextSessionRevision += 1
                    val installed = InstalledAccessSession(nextSessionRevision, session)
                    accessSession = installed
                    scheduledSession.value = installed.toSchedule()
                    if (replacedRevision != null) {
                        unusedOldRequests =
                            AuthorizationCancellation.of(
                                requests = detachUnusedRequestWorkLocked(replacedRevision),
                            )
                    }
                    AuthorizationCoordinatorOutcome.Authorized(
                        expiresAtEpochSeconds = session.expiresAtEpochSeconds,
                        sessionRevision = installed.revision,
                    )
                }
            }
        unusedOldRequests?.let { cancellation ->
            cancellation.cancel()
            coordinatorScope.launch { cancellation.join() }
        }
        return outcome
    }

    private suspend fun governCompletedOutcome(
        flight: AuthorizationFlight,
        outcome: AuthorizationCoordinatorOutcome,
    ): GovernedAuthorizationOutcome {
        var cancellation: AuthorizationCancellation? = null
        val governed =
            stateMutex.withLock {
                if (closed ||
                    !processJob.isActive ||
                    generation != flight.generation ||
                    applicationVisibility.state.value != ApplicationVisibilityState.ACTIVE_FOREGROUND
                ) {
                    AuthorizationCoordinatorOutcome.OutsideActiveForeground
                } else {
                    if (outcome.destroysAccessAuthority() && accessSession != null) {
                        generation += 1
                        cancellation = removeAccessSessionLocked()
                    }
                    outcome
                }
            }
        return GovernedAuthorizationOutcome(governed, cancellation)
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
                withContext(NonCancellable) {
                    finishCancellation(expireScheduledSession(session))
                }
            }
        }
    }

    private suspend fun requestScheduledRenewal(session: ScheduledAccessSession): AuthorizationCoordinatorOutcome? {
        val flight =
            lifecycleMutex.withLock {
                stateMutex.withLock stateLock@{
                    if (!isScheduledSessionUsableLocked(session)) return@stateLock null
                    inFlight ?: startFlightLocked(AuthorizationExchangeKind.RENEWAL)
                }
            } ?: return null
        flight.start()
        return flight.result.await()
    }

    private suspend fun expireScheduledSession(session: ScheduledAccessSession): AuthorizationCancellation? =
        stateMutex.withLock {
            val current = accessSession
            if (current?.revision == session.revision &&
                clock.nowEpochSeconds() >= current.session.expiresAtEpochSeconds
            ) {
                removeAccessSessionLocked(expired = true)
            } else {
                null
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
            beginInvalidation(dropAccessSession, outcome).awaitCompletion()
        }

    private suspend fun beginInvalidation(
        dropAccessSession: Boolean,
        outcome: AuthorizationCoordinatorOutcome,
    ): AuthorizationInvalidation =
        withContext(NonCancellable) {
            val cancellation =
                lifecycleMutex.withLock {
                    stateMutex.withLock {
                        if (closed) return@withLock null
                        invalidateAuthorizationLocked(dropAccessSession, outcome)
                    }
                }
            cancellation?.cancel()
            CoordinatorAuthorizationInvalidation(cancellation).also { it.markFenced() }
        }

    private suspend fun closeFromProcessScope() {
        beginClose()
    }

    private suspend fun beginCloseCoordinator(): AuthorizationInvalidation =
        withContext(NonCancellable) {
            var ownsFence = false
            val invalidation =
                lifecycleMutex.withLock {
                    stateMutex.withLock {
                        closeInvalidation?.let { return@withLock it }
                        closed = true
                        ownsFence = true
                        CoordinatorAuthorizationInvalidation(
                            cancellation =
                                invalidateAuthorizationLocked(
                                    dropAccessSession = true,
                                    outcome = AuthorizationCoordinatorOutcome.Closed,
                                ),
                            root = coordinatorJob,
                        ).also { closeInvalidation = it }
                    }
                }
            if (!ownsFence) {
                invalidation.awaitFence()
                return@withContext invalidation
            }
            try {
                invalidation.cancel()
                processCancellationHandle.dispose()
                coordinatorJob.cancel()
                try {
                    remoteGateway.close()
                } catch (_: Exception) {
                    // Teardown remains committed and diagnostics remain secret-free.
                }
            } finally {
                invalidation.markFenced()
            }
            invalidation
        }

    private fun invalidateAuthorizationLocked(
        dropAccessSession: Boolean,
        outcome: AuthorizationCoordinatorOutcome,
    ): AuthorizationCancellation? {
        generation += 1
        val requestWork =
            if (dropAccessSession) {
                removeAccessSessionLocked()?.requests.orEmpty()
            } else {
                detachRequestWorkLocked()
            }
        val flight = inFlight
        inFlight = null
        flight?.invalidate(outcome)
        return AuthorizationCancellation.of(
            flight = flight?.job,
            requests = requestWork,
        )
    }

    private fun removeAccessSessionLocked(expired: Boolean = false): AuthorizationCancellation? {
        val removedRevision = accessSession?.revision
        accessSession = null
        scheduledSession.value = null
        return AuthorizationCancellation.of(
            requests =
                if (expired && removedRevision != null) {
                    detachRequestWorkLocked(removedRevision)
                } else {
                    detachRequestWorkLocked()
                },
            expiredSessionRevision = removedRevision.takeIf { expired },
        )
    }

    private fun detachRequestWorkLocked(sessionRevision: Long? = null): List<BoundAuthorizationRequest> {
        val selected =
            activeRequestWork.filter { request ->
                sessionRevision == null || request.sessionRevision == sessionRevision
            }
        activeRequestWork.removeAll(selected.toSet())
        selected.forEach(BoundAuthorizationRequest::invalidateLocked)
        return selected
    }

    private fun detachUnusedRequestWorkLocked(sessionRevision: Long): List<BoundAuthorizationRequest> {
        val selected =
            activeRequestWork.filter { request ->
                request.sessionRevision == sessionRevision &&
                    request.credentialState == BoundCredentialState.UNUSED
            }
        activeRequestWork.removeAll(selected.toSet())
        selected.forEach(BoundAuthorizationRequest::invalidateLocked)
        return selected
    }

    private suspend fun expireBoundRequest(boundRequest: BoundAuthorizationRequest) {
        val cancellation =
            stateMutex.withLock {
                if (clock.nowEpochSeconds() < boundRequest.expiresAtEpochSeconds ||
                    !activeRequestWork.remove(boundRequest)
                ) {
                    return@withLock null
                }
                boundRequest.invalidateLocked()
                AuthorizationCancellation.of(requests = listOf(boundRequest))
            }
        cancellation?.cancelAndJoin()
    }

    private suspend fun finishCancellation(cancellation: AuthorizationCancellation?) {
        cancellation ?: return
        cancellation.cancel()
        cancellation.expiredSessionRevision?.let { revision ->
            emitEvent(AuthorizationCoordinatorEvent.AccessSessionExpired(revision))
        }
        cancellation.join()
    }

    private suspend fun isCurrentActiveFlight(flightGeneration: Long): Boolean =
        stateMutex.withLock {
            !closed &&
                processJob.isActive &&
                generation == flightGeneration &&
                applicationVisibility.state.value == ApplicationVisibilityState.ACTIVE_FOREGROUND
        }

    private suspend fun emitEvent(event: AuthorizationCoordinatorEvent) {
        try {
            eventSink.emit(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Consumer diagnostics must never corrupt authorization state or expose exception text.
        }
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
    val id: Long,
    val generation: Long,
    val kind: AuthorizationExchangeKind,
    val selectedProtocolVersion: Int,
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

private data class GovernedAuthorizationOutcome(
    val outcome: AuthorizationCoordinatorOutcome,
    val cancellation: AuthorizationCancellation?,
)

private class AuthorizationAdmission private constructor(
    val flight: AuthorizationFlight?,
    val cachedOutcome: AuthorizationCoordinatorOutcome?,
    val cancellation: AuthorizationCancellation?,
) {
    companion object {
        fun cached(outcome: AuthorizationCoordinatorOutcome): AuthorizationAdmission =
            AuthorizationAdmission(flight = null, cachedOutcome = outcome, cancellation = null)

        fun cachedWithCancellation(
            outcome: AuthorizationCoordinatorOutcome,
            cancellation: AuthorizationCancellation?,
        ): AuthorizationAdmission =
            AuthorizationAdmission(
                flight = null,
                cachedOutcome = outcome,
                cancellation = cancellation,
            )

        fun join(flight: AuthorizationFlight): AuthorizationAdmission =
            AuthorizationAdmission(flight, cachedOutcome = null, cancellation = null)

        fun joinWithCancellation(
            flight: AuthorizationFlight,
            cancellation: AuthorizationCancellation?,
        ): AuthorizationAdmission = AuthorizationAdmission(flight, cachedOutcome = null, cancellation = cancellation)
    }
}

private data class AccessSessionInspection(
    val hasActiveAccessSession: Boolean,
    val cancellation: AuthorizationCancellation?,
) {
    companion object {
        fun active(): AccessSessionInspection =
            AccessSessionInspection(hasActiveAccessSession = true, cancellation = null)

        fun inactive(cancellation: AuthorizationCancellation? = null): AccessSessionInspection =
            AccessSessionInspection(hasActiveAccessSession = false, cancellation = cancellation)
    }
}

private class AuthorityUseAdmission private constructor(
    val boundRequest: BoundAuthorizationRequest?,
    val unavailableOutcome: AuthorizationAuthorityUseOutcome<Nothing>?,
    val cancellation: AuthorizationCancellation?,
) {
    companion object {
        fun admitted(boundRequest: BoundAuthorizationRequest): AuthorityUseAdmission =
            AuthorityUseAdmission(
                boundRequest = boundRequest,
                unavailableOutcome = null,
                cancellation = null,
            )

        fun unavailable(
            outcome: AuthorizationAuthorityUseOutcome<Nothing>,
            cancellation: AuthorizationCancellation? = null,
        ): AuthorityUseAdmission =
            AuthorityUseAdmission(
                boundRequest = null,
                unavailableOutcome = outcome,
                cancellation = cancellation,
            )
    }
}

private class AuthorizationCancellation private constructor(
    val flight: Job?,
    val requests: List<BoundAuthorizationRequest>,
    val expiredSessionRevision: Long?,
) {
    fun cancel() {
        flight?.cancel()
        requests.forEach { request -> request.root.cancel(AuthorizationAuthorityLostCancellationException()) }
    }

    suspend fun join() {
        flight?.join()
        requests
            .map(BoundAuthorizationRequest::root)
            .filterNot { job -> job === flight }
            .forEach { job -> job.join() }
    }

    suspend fun cancelAndJoin() {
        cancel()
        join()
    }

    companion object {
        fun of(
            flight: Job? = null,
            requests: List<BoundAuthorizationRequest> = emptyList(),
            expiredSessionRevision: Long? = null,
        ): AuthorizationCancellation? =
            if (flight == null && requests.isEmpty() && expiredSessionRevision == null) {
                null
            } else {
                AuthorizationCancellation(flight, requests, expiredSessionRevision)
            }
    }
}

private class CoordinatorAuthorizationInvalidation(
    private val cancellation: AuthorizationCancellation?,
    private val root: Job? = null,
) : AuthorizationInvalidation {
    private val fenced: CompletableDeferred<Unit> = CompletableDeferred()

    fun cancel() {
        cancellation?.cancel()
    }

    fun markFenced() {
        fenced.complete(Unit)
    }

    suspend fun awaitFence() {
        fenced.await()
    }

    override suspend fun awaitCompletion() {
        fenced.await()
        cancellation?.join()
        root?.join()
    }
}

private class BoundAuthorizationRequest(
    val sessionRevision: Long,
    val expiresAtEpochSeconds: Long,
    credential: AccessSessionCredential,
    val root: Job,
) {
    lateinit var scopedCredential: ScopedAuthorizationRequestCredential
    var credential: AccessSessionCredential? = credential
        private set
    var credentialState: BoundCredentialState = BoundCredentialState.UNUSED
        private set

    fun markAppliedLocked() {
        check(credentialState == BoundCredentialState.UNUSED)
        credentialState = BoundCredentialState.APPLIED
        credential = null
    }

    fun invalidateLocked() {
        credentialState = BoundCredentialState.INVALID
        credential = null
    }
}

private enum class BoundCredentialState {
    UNUSED,
    APPLIED,
    INVALID,
}

private class ScopedAuthorizationRequestCredential(
    private val applyCredential: suspend (AccessSessionAuthorizationTarget) -> Boolean,
) : AuthorizationRequestCredential {
    override suspend fun applyTo(target: AccessSessionAuthorizationTarget): Boolean = applyCredential(target)

    override fun toString(): String = "AuthorizationRequestCredential(redacted)"
}

private class AuthorizationAuthorityLostCancellationException :
    CancellationException("Authorization authority lost")

private object AuthorizationExecutorContextKey : CoroutineContext.Key<AuthorizationExecutorContext>

private object AuthorizationExecutorContext :
    AbstractCoroutineContextElement(AuthorizationExecutorContextKey)

private fun CancellationException.isAuthorityLoss(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is AuthorizationAuthorityLostCancellationException) return true
        current = current.cause
    }
    return false
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
