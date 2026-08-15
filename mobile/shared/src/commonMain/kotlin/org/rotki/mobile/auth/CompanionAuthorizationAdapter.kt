@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package org.rotki.mobile.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.PairingCleanupHandle
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionTransitionOutcome
import org.rotki.mobile.feature.authorization.application.AuthorizationAuthorityUseOutcome
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinatorEvent
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinatorEventSink
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinatorOutcome
import org.rotki.mobile.feature.authorization.application.AuthorizationExchangeKind
import org.rotki.mobile.feature.authorization.application.AuthorizationInvalidation
import org.rotki.mobile.feature.authorization.application.AuthorizationProcessControl
import org.rotki.mobile.feature.authorization.application.AuthorizationRequest
import org.rotki.mobile.feature.authorization.application.AuthorizationRequestAuthority
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One platform-owned, journaled cleanup of every durable local authority artifact.
 *
 * Cleanup and its finalizers must not re-enter Authorization process control or this adapter;
 * lifecycle feedback is queued only after this callback unwinds.
 */
internal fun interface CompanionAuthorizationLocalAuthorityCleaner {
    suspend fun destroyAll(): Boolean
}

internal enum class CompanionAuthenticatedSessionWorkCloseReason {
    REPLACED,
    AUTHORITY_LOST,
    BACKGROUND_OR_SYSTEM_LOCK,
    CLOSED,
}

/** Secret-free drain returned after matching authenticated session work was detached. */
internal fun interface CompanionAuthenticatedSessionWorkInvalidation {
    /** Returns false when the detached work could not be closed completely. */
    suspend fun awaitCompletion(): Boolean
}

/**
 * Closes facade-owned long-lived authenticated work without exposing its transport.
 *
 * The implementation must synchronously and idempotently detach only work matching
 * [sessionRevision] before returning. A null revision means every current session. It must be safe
 * under concurrent calls. Neither this callback nor its completion may re-enter Authorization
 * process control or this adapter; lifecycle feedback is queued only after completion unwinds.
 */
internal fun interface CompanionAuthenticatedSessionWorkController {
    fun beginClose(
        sessionRevision: Long?,
        reason: CompanionAuthenticatedSessionWorkCloseReason,
    ): CompanionAuthenticatedSessionWorkInvalidation
}

/** Ktor-free discovery result used before an explicit retry from a resolved terminal state. */
internal sealed interface CompanionAuthorizationDiscoveryOutcome {
    data class Compatible(
        val selectedProtocolVersion: Int,
    ) : CompanionAuthorizationDiscoveryOutcome

    data object Incompatible : CompanionAuthorizationDiscoveryOutcome

    data object NetworkUnavailable : CompanionAuthorizationDiscoveryOutcome

    data object ContractFailure : CompanionAuthorizationDiscoveryOutcome
}

/**
 * Performs fresh discovery; proof remains owned by [AuthorizationProcessControl].
 * The callback must not re-enter process control or this adapter before it unwinds.
 */
internal fun interface CompanionAuthorizationDiscovery {
    suspend fun discover(): CompanionAuthorizationDiscoveryOutcome
}

internal enum class CompanionAuthorizationResult {
    IDLE,
    AUTHORIZING,
    RENEWING,
    AUTHORITY_READY,
    OUTSIDE_ACTIVE_FOREGROUND,
    PAIRING_REQUIRED,
    DEVICE_AUTHENTICATION_CANCELLED,
    SESSION_EXPIRED,
    LOCAL_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    CHALLENGE_UNAVAILABLE,
    ENGINE_LOCKED,
    PROFILE_MISMATCH,
    INCOMPATIBLE,
    REVOKED,
    CLEANUP_INCOMPLETE,
    CONTRACT_FAILURE,
    ENGINE_FAILURE,
    TRANSITION_REJECTED,
    CLOSED,
}

/** Secret-free, Kotlin-only diagnostic state for native composition and tests. */
internal data class CompanionAuthorizationStatus(
    val result: CompanionAuthorizationResult,
    val expiresAtEpochSeconds: Long? = null,
    val retryAfterSeconds: Long? = null,
) {
    override fun toString(): String = "CompanionAuthorizationStatus(redacted)"
}

/**
 * Maps one coordinator into the existing facade state machine without widening the Swift API.
 *
 * Trigger recovery is facade-scoped and single-flight. Automatic coordinator events are mapped
 * once by their owner flight, so joined callers cannot duplicate transitions or cleanup.
 */
internal class CompanionAuthorizationAdapter(
    private val facade: CompanionFacade,
    private val coordinator: AuthorizationProcessControl,
    private val requestAuthority: AuthorizationRequestAuthority,
    private val applicationVisibility: ApplicationVisibility,
    private val discovery: CompanionAuthorizationDiscovery,
    private val localAuthorityCleaner: CompanionAuthorizationLocalAuthorityCleaner,
    private val sessionWorkController: CompanionAuthenticatedSessionWorkController,
    processScope: CoroutineScope,
    eventBridge: CompanionAuthorizationEventBridge,
) {
    private val lifecycleMutex = Mutex()
    private val stateMutex = Mutex()
    private val processJob: Job =
        requireNotNull(processScope.coroutineContext[Job]) {
            "Companion Authorization adapter scope must contain a Job"
        }
    private val processCancellationStarted: CompletableDeferred<Unit> = CompletableDeferred()
    private val processCancellationHandle =
        processJob.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) {
            processCancellationStarted.complete(Unit)
        }
    private val adapterJob: Job = SupervisorJob()
    private val adapterScope = CoroutineScope(processScope.coroutineContext + adapterJob)
    private val mutableStatus =
        MutableStateFlow(CompanionAuthorizationStatus(CompanionAuthorizationResult.IDLE))
    private var generation: Long = 0
    private val exchangeGenerations: MutableMap<Long, Long> = mutableMapOf()
    private var recoveryFlight: CompanionAuthorizationRecoveryFlight? = null
    private val cleanupFlights: MutableMap<Any, CompanionAuthorizationCleanupFlight> = mutableMapOf()
    private var activeSessionRevision: Long? = null
    private var teardownInProgress: Boolean = false
    private var closed: Boolean = false
    private var closeCompletion: CompletableDeferred<Unit>? = null

    private companion object {
        val AUTHENTICATED_REQUEST_ROOTS: Set<CompanionRootState> =
            setOf(
                CompanionRootState.Connecting,
                CompanionRootState.Online,
                CompanionRootState.Refreshing,
                CompanionRootState.Degraded,
            )
    }

    internal val status: StateFlow<CompanionAuthorizationStatus> = mutableStatus.asStateFlow()

    init {
        eventBridge.attach(::handleCoordinatorEvent)
        adapterScope.launch(start = CoroutineStart.UNDISPATCHED) {
            processCancellationStarted.await()
            closeAdapter(joinAdapterJob = false)
        }
    }

    internal suspend fun authorizeOrJoin(): AuthorizationCoordinatorOutcome {
        ensureNotExternalCallbackReentry()
        val admittedGeneration =
            lifecycleMutex.withLock {
                stateMutex.withLock {
                    if (!canAuthorizeLocked() || recoveryFlight != null) {
                        return AuthorizationCoordinatorOutcome.OutsideActiveForeground
                    }
                    generation
                }
            }
        val outcome = coordinator.authorize()
        when (outcome) {
            is AuthorizationCoordinatorOutcome.Authorized -> {
                updateStatusIfCurrent(
                    admittedGeneration,
                    CompanionAuthorizationStatus(
                        result = CompanionAuthorizationResult.AUTHORITY_READY,
                        expiresAtEpochSeconds = outcome.expiresAtEpochSeconds,
                    ),
                    sessionRevision = outcome.sessionRevision,
                )
            }

            AuthorizationCoordinatorOutcome.OutsideActiveForeground -> {
                updateStatusIfCurrent(
                    admittedGeneration,
                    CompanionAuthorizationStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND),
                )
            }

            AuthorizationCoordinatorOutcome.Closed -> {
                updateStatusIfCurrent(
                    admittedGeneration,
                    CompanionAuthorizationStatus(CompanionAuthorizationResult.CLOSED),
                )
            }

            else -> {
                // Owner-flight completion was exhaustively mapped by the event bridge first.
            }
        }
        return outcome
    }

    internal suspend fun onBackgroundOrSystemLock(): CompanionTransitionOutcome =
        withContext(NonCancellable) {
            ensureNotExternalCallbackReentry()
            val (transition, teardown) =
                lifecycleMutex.withLock {
                    val transition = facade.lock()
                    val (invalidated, expectedGeneration) =
                        stateMutex.withLock {
                            teardownInProgress = true
                            invalidateRecoveryLocked() to generation
                        }
                    transition to
                        CompanionAuthorizationTeardown(
                            recoveryJob = invalidated.recoveryJob,
                            expectedGeneration = expectedGeneration,
                            authorityInvalidation = coordinator.beginClearAccessSession(),
                            sessionWorkInvalidation =
                                beginCloseSessionWork(
                                    reason =
                                        CompanionAuthenticatedSessionWorkCloseReason
                                            .BACKGROUND_OR_SYSTEM_LOCK,
                                    sessionRevision = invalidated.sessionRevision,
                                ),
                        )
                }
            teardown.recoveryJob?.cancel()
            awaitSessionWorkClose(teardown.sessionWorkInvalidation)
            teardown.authorityInvalidation.awaitCompletion()
            teardown.recoveryJob?.join()
            completeTeardownIfCurrent(
                teardown.expectedGeneration,
                CompanionAuthorizationStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND),
            )
            transition
        }

    internal suspend fun onExplicitForegroundRetry(): CompanionAuthorizationStatus {
        ensureNotExternalCallbackReentry()
        return recover(CompanionAuthorizationRecoveryTrigger.EXPLICIT_FOREGROUND_RETRY)
    }

    internal suspend fun onAccessSessionUnavailable(): CompanionAuthorizationStatus {
        ensureNotExternalCallbackReentry()
        return recover(CompanionAuthorizationRecoveryTrigger.ACCESS_SESSION_UNAVAILABLE)
    }

    internal suspend fun onWebSocketPolicyClosed(): CompanionAuthorizationStatus {
        ensureNotExternalCallbackReentry()
        return recover(CompanionAuthorizationRecoveryTrigger.WEBSOCKET_1008)
    }

    internal suspend fun onLocalUnpair(): CompanionAuthorizationStatus =
        withContext(NonCancellable) {
            ensureNotExternalCallbackReentry()
            val (cleanupLease, teardown) =
                lifecycleMutex.withLock {
                    val claimed = facade.claimLocalUnpairCleanup()
                    val (lease, invalidated, expectedGeneration) =
                        stateMutex.withLock {
                            teardownInProgress = true
                            Triple(
                                claimCleanupLeaseLocked(claimed),
                                invalidateRecoveryLocked(),
                                generation,
                            )
                        }
                    lease to
                        CompanionAuthorizationTeardown(
                            recoveryJob = invalidated.recoveryJob,
                            expectedGeneration = expectedGeneration,
                            authorityInvalidation = coordinator.beginClearAccessSession(),
                            sessionWorkInvalidation =
                                beginCloseSessionWork(
                                    reason = CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST,
                                    sessionRevision = invalidated.sessionRevision,
                                ),
                        )
                }
            teardown.recoveryJob?.cancel()
            val cleanupCompleted =
                performCleanupParticipant(
                    lease = cleanupLease,
                    sessionWorkInvalidation = teardown.sessionWorkInvalidation,
                )
            teardown.authorityInvalidation.awaitCompletion()
            teardown.recoveryJob?.join()
            val cleanupReady = cleanupCompleted
            completeTeardownIfCurrent(
                teardown.expectedGeneration,
                CompanionAuthorizationStatus(
                    if (cleanupReady) {
                        CompanionAuthorizationResult.PAIRING_REQUIRED
                    } else {
                        CompanionAuthorizationResult.CLEANUP_INCOMPLETE
                    },
                ),
            )
            if (!completeCleanupParticipant(cleanupLease, cleanupReady)) {
                updateStatusIfGenerationCurrent(
                    teardown.expectedGeneration,
                    CompanionAuthorizationStatus(CompanionAuthorizationResult.CLEANUP_INCOMPLETE),
                )
            }
            status.value
        }

    internal suspend fun <R : Any> executeRequest(
        request: AuthorizationRequest<R>,
    ): AuthorizationAuthorityUseOutcome<R> {
        ensureNotExternalCallbackReentry()
        val denial =
            stateMutex.withLock {
                when {
                    closed || !processJob.isActive -> {
                        AuthorizationAuthorityUseOutcome.Closed
                    }

                    teardownInProgress -> {
                        AuthorizationAuthorityUseOutcome.OutsideActiveForeground
                    }

                    applicationVisibility.state.value !=
                        ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
                        AuthorizationAuthorityUseOutcome.OutsideActiveForeground
                    }

                    facade.status.value.rootState !in AUTHENTICATED_REQUEST_ROOTS -> {
                        AuthorizationAuthorityUseOutcome.Unavailable
                    }

                    else -> {
                        null
                    }
                }
            }
        return denial ?: requestAuthority.execute(request)
    }

    internal suspend fun close() {
        ensureNotExternalCallbackReentry()
        closeAdapter(joinAdapterJob = true)
    }

    private suspend fun closeAdapter(joinAdapterJob: Boolean) {
        withContext(NonCancellable) {
            var ownsTeardown = false
            lateinit var completion: CompletableDeferred<Unit>
            val teardown =
                lifecycleMutex.withLock {
                    val invalidated =
                        stateMutex.withLock {
                            closeCompletion?.let { existing ->
                                completion = existing
                                return@withLock null
                            }
                            completion = CompletableDeferred()
                            closeCompletion = completion
                            closed = true
                            ownsTeardown = true
                            invalidateRecoveryLocked()
                        }
                    invalidated?.let {
                        CompanionAuthorizationCloseTeardown(
                            recoveryJob = it.recoveryJob,
                            authorityInvalidation = coordinator.beginClose(),
                            sessionWorkInvalidation =
                                beginCloseSessionWork(
                                    reason = CompanionAuthenticatedSessionWorkCloseReason.CLOSED,
                                    sessionRevision = it.sessionRevision,
                                ),
                        )
                    }
                }
            if (!ownsTeardown) {
                if (joinAdapterJob) {
                    completion.await()
                    adapterJob.join()
                }
                return@withContext
            }
            try {
                val ownedTeardown = requireNotNull(teardown)
                ownedTeardown.recoveryJob?.cancel()
                awaitSessionWorkClose(ownedTeardown.sessionWorkInvalidation)
                ownedTeardown.authorityInvalidation.awaitCompletion()
                ownedTeardown.recoveryJob?.join()
                processCancellationHandle.dispose()
                adapterJob.cancel()
                updateStatus(CompanionAuthorizationResult.CLOSED)
                if (joinAdapterJob) adapterJob.join()
            } finally {
                completion.complete(Unit)
            }
        }
    }

    override fun toString(): String = "CompanionAuthorizationAdapter(redacted)"

    private suspend fun recover(trigger: CompanionAuthorizationRecoveryTrigger): CompanionAuthorizationStatus {
        val admission =
            lifecycleMutex.withLock {
                stateMutex.withLock {
                    if (closed || !processJob.isActive) {
                        return@withLock CompanionAuthorizationRecoveryAdmission.closed()
                    }
                    if (teardownInProgress) {
                        return@withLock CompanionAuthorizationRecoveryAdmission.outsideForeground()
                    }
                    val current = recoveryFlight
                    if (current != null &&
                        trigger.priority > current.trigger.priority
                    ) {
                        val invalidated = invalidateRecoveryLocked()
                        startRecoveryLocked(trigger, invalidated.recoveryJob)
                    } else {
                        current?.let(CompanionAuthorizationRecoveryAdmission::join)
                            ?: startRecoveryLocked(trigger)
                    }
                }
            }
        admission.cached?.let { return it }
        admission.cancelledJob?.cancel()
        val flight = requireNotNull(admission.flight)
        flight.start()
        return flight.result.await()
    }

    private fun startRecoveryLocked(
        trigger: CompanionAuthorizationRecoveryTrigger,
        cancelledJob: Job? = null,
    ): CompanionAuthorizationRecoveryAdmission {
        val flight = CompanionAuthorizationRecoveryFlight(generation, trigger)
        val job =
            adapterScope.launch(start = CoroutineStart.LAZY) {
                runRecovery(flight)
            }
        flight.attach(job)
        recoveryFlight = flight
        return CompanionAuthorizationRecoveryAdmission.join(flight, cancelledJob)
    }

    private suspend fun runRecovery(flight: CompanionAuthorizationRecoveryFlight) {
        try {
            val outcome =
                try {
                    executeRecovery(flight)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    updateStatusIfRecoveryCurrent(
                        flight,
                        CompanionAuthorizationStatus(CompanionAuthorizationResult.LOCAL_UNAVAILABLE),
                    ) ?: CompanionAuthorizationStatus(
                        CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND,
                    )
                }
            stateMutex.withLock {
                if (recoveryFlight === flight) recoveryFlight = null
            }
            flight.result.complete(outcome)
        } catch (cancellation: CancellationException) {
            if (!flight.result.isCompleted) flight.result.cancel(cancellation)
            throw cancellation
        } finally {
            stateMutex.withLock {
                if (recoveryFlight === flight) recoveryFlight = null
            }
        }
    }

    private suspend fun executeRecovery(flight: CompanionAuthorizationRecoveryFlight): CompanionAuthorizationStatus {
        val prepared =
            lifecycleMutex.withLock {
                if (!isCurrentActiveRecovery(flight)) return@withLock false
                val transition = applyRecoveryTransition(flight.trigger)
                if (transition !is CompanionTransitionOutcome.Applied) {
                    updateStatus(CompanionAuthorizationResult.TRANSITION_REJECTED)
                    return@withLock false
                }
                isCurrentActiveRecovery(flight) &&
                    facade.status.value.rootState == CompanionRootState.Connecting
            }
        if (!prepared) {
            if (status.value.result == CompanionAuthorizationResult.TRANSITION_REJECTED) {
                return status.value
            }
            return CompanionAuthorizationStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND)
        }
        if (flight.trigger.clearsAccessAuthority) {
            val fence =
                lifecycleMutex.withLock {
                    val (accepted, sessionRevision) =
                        stateMutex.withLock {
                            if (!isCurrentRecoveryLocked(flight) ||
                                applicationVisibility.state.value !=
                                ApplicationVisibilityState.ACTIVE_FOREGROUND
                            ) {
                                false to null
                            } else {
                                true to fenceAdapterAuthorityLocked()
                            }
                        }
                    if (!accepted) return@withLock null
                    CompanionAuthorizationAuthorityFence(
                        clearInvalidation = coordinator.beginClearAccessSession(),
                        sessionWorkInvalidation =
                            beginCloseSessionWork(
                                reason = CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST,
                                sessionRevision = sessionRevision,
                            ),
                    )
                } ?: return CompanionAuthorizationStatus(
                    CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND,
                )
            awaitSessionWorkClose(fence.sessionWorkInvalidation)
            fence.clearInvalidation.awaitCompletion()
        }
        if (!isCurrentActiveRecovery(flight)) {
            return CompanionAuthorizationStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND)
        }
        val outcome =
            if (flight.trigger == CompanionAuthorizationRecoveryTrigger.EXPLICIT_FOREGROUND_RETRY) {
                discoverAndAuthorize(flight)
            } else {
                coordinator.authorize()
            }
        if (!isCurrentRecovery(flight)) {
            return CompanionAuthorizationStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND)
        }
        return coarseStatus(outcome)
    }

    private fun applyRecoveryTransition(trigger: CompanionAuthorizationRecoveryTrigger): CompanionTransitionOutcome =
        when (trigger) {
            CompanionAuthorizationRecoveryTrigger.ACCESS_SESSION_UNAVAILABLE -> {
                facade.accessSessionUnavailable()
            }

            CompanionAuthorizationRecoveryTrigger.SESSION_EXPIRED -> {
                facade.accessSessionUnavailable()
            }

            CompanionAuthorizationRecoveryTrigger.WEBSOCKET_1008 -> {
                facade.webSocketPolicyClosed()
            }

            CompanionAuthorizationRecoveryTrigger.EXPLICIT_FOREGROUND_RETRY -> {
                if (facade.status.value.rootState == CompanionRootState.Unreachable) {
                    facade.transportRestored()
                } else {
                    facade.retryResolvedEngineState()
                }
            }
        }

    private suspend fun discoverAndAuthorize(
        flight: CompanionAuthorizationRecoveryFlight,
    ): AuthorizationCoordinatorOutcome =
        when (
            val discovered =
                withContext(companionAuthorizationExternalCallbackContext) {
                    discovery.discover()
                }
        ) {
            is CompanionAuthorizationDiscoveryOutcome.Compatible -> {
                if (discovered.selectedProtocolVersion <= 0) {
                    val outcome = AuthorizationCoordinatorOutcome.ContractFailure
                    handleDiscoveredOutcomeIfCurrent(flight, outcome)
                    return outcome
                }
                val selected =
                    lifecycleMutex.withLock {
                        if (!isCurrentActiveRecovery(flight)) return@withLock false
                        true
                    }
                if (!selected) {
                    AuthorizationCoordinatorOutcome.OutsideActiveForeground
                } else {
                    val fence =
                        lifecycleMutex.withLock {
                            val (accepted, sessionRevision) =
                                stateMutex.withLock {
                                    if (!isCurrentRecoveryLocked(flight) ||
                                        applicationVisibility.state.value !=
                                        ApplicationVisibilityState.ACTIVE_FOREGROUND
                                    ) {
                                        false to null
                                    } else {
                                        true to fenceAdapterAuthorityLocked()
                                    }
                                }
                            if (!accepted) return@withLock null
                            CompanionAuthorizationExplicitFence(
                                sessionWorkInvalidation =
                                    beginCloseSessionWork(
                                        reason =
                                            CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST,
                                        sessionRevision = sessionRevision,
                                    ),
                                clearInvalidation = coordinator.beginClearAccessSession(),
                                selectionInvalidation =
                                    coordinator.beginSelectProtocolVersion(
                                        discovered.selectedProtocolVersion,
                                    ),
                            )
                        } ?: return AuthorizationCoordinatorOutcome.OutsideActiveForeground
                    awaitSessionWorkClose(fence.sessionWorkInvalidation)
                    fence.clearInvalidation.awaitCompletion()
                    fence.selectionInvalidation.awaitCompletion()
                    if (!isCurrentActiveRecovery(flight)) {
                        return AuthorizationCoordinatorOutcome.OutsideActiveForeground
                    }
                    coordinator.authorize()
                }
            }

            CompanionAuthorizationDiscoveryOutcome.Incompatible -> {
                val outcome =
                    AuthorizationCoordinatorOutcome.RemoteFailure(
                        failure = AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL,
                        retryAfterSeconds = null,
                    )
                handleDiscoveredOutcomeIfCurrent(flight, outcome)
                outcome
            }

            CompanionAuthorizationDiscoveryOutcome.NetworkUnavailable -> {
                val outcome = AuthorizationCoordinatorOutcome.NetworkUnavailable
                handleDiscoveredOutcomeIfCurrent(flight, outcome)
                outcome
            }

            CompanionAuthorizationDiscoveryOutcome.ContractFailure -> {
                val outcome = AuthorizationCoordinatorOutcome.ContractFailure
                handleDiscoveredOutcomeIfCurrent(flight, outcome)
                outcome
            }
        }

    private suspend fun handleDiscoveredOutcomeIfCurrent(
        flight: CompanionAuthorizationRecoveryFlight,
        outcome: AuthorizationCoordinatorOutcome,
    ) {
        val fence =
            if (outcome.destroysAccessAuthority()) {
                lifecycleMutex.withLock {
                    val (accepted, sessionRevision) =
                        stateMutex.withLock {
                            if (isCurrentRecoveryLocked(flight) &&
                                applicationVisibility.state.value ==
                                ApplicationVisibilityState.ACTIVE_FOREGROUND
                            ) {
                                true to fenceAdapterAuthorityLocked()
                            } else {
                                false to null
                            }
                        }
                    if (accepted) {
                        CompanionAuthorizationDiscoveredFence(
                            sessionRevision = sessionRevision,
                            clearInvalidation = coordinator.beginClearAccessSession(),
                        )
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        if (fence != null) {
            mapCoordinatorOutcomeIfCurrent(
                outcome = outcome,
                expectedGeneration = flight.generation,
                fencedSessionRevision = fence.sessionRevision,
            )
            fence.clearInvalidation.awaitCompletion()
            return
        }
        mapCoordinatorOutcomeIfCurrent(outcome, flight.generation)
    }

    private suspend fun handleCoordinatorEvent(event: AuthorizationCoordinatorEvent) {
        when (event) {
            is AuthorizationCoordinatorEvent.ExchangeStarted -> {
                lifecycleMutex.withLock {
                    val accepted =
                        stateMutex.withLock {
                            if (closed || teardownInProgress || !processJob.isActive) {
                                false
                            } else {
                                exchangeGenerations[event.exchangeId] = generation
                                true
                            }
                        }
                    if (accepted) {
                        if (event.kind == AuthorizationExchangeKind.RENEWAL) {
                            facade.proactiveRenewalStarted()
                            updateStatus(
                                CompanionAuthorizationStatus(
                                    result = CompanionAuthorizationResult.RENEWING,
                                    expiresAtEpochSeconds = status.value.expiresAtEpochSeconds,
                                ),
                            )
                        } else {
                            updateStatus(CompanionAuthorizationResult.AUTHORIZING)
                        }
                    }
                }
            }

            is AuthorizationCoordinatorEvent.ExchangeCompleted -> {
                val outcome = event.outcome
                val accepted =
                    lifecycleMutex.withLock {
                        stateMutex.withLock {
                            val ownerGeneration = exchangeGenerations.remove(event.exchangeId)
                            ownerGeneration
                                ?.takeIf { it == generation && !closed && !teardownInProgress }
                                ?.let {
                                    CompanionAuthorizationAcceptedExchange(
                                        generation = it,
                                        replacedSessionRevision =
                                            activeSessionRevision.takeIf {
                                                event.kind == AuthorizationExchangeKind.RENEWAL &&
                                                    outcome is AuthorizationCoordinatorOutcome.Authorized
                                            },
                                    )
                                }
                        }
                    }
                if (accepted != null) {
                    if (event.kind == AuthorizationExchangeKind.RENEWAL &&
                        outcome is AuthorizationCoordinatorOutcome.Authorized
                    ) {
                        closeSessionWork(
                            reason = CompanionAuthenticatedSessionWorkCloseReason.REPLACED,
                            sessionRevision = accepted.replacedSessionRevision,
                        )
                    }
                    mapCoordinatorOutcomeIfCurrent(
                        outcome = outcome,
                        expectedGeneration = accepted.generation,
                        retainAuthorityOnSessionExpired = event.kind == AuthorizationExchangeKind.RENEWAL,
                    )
                }
            }

            is AuthorizationCoordinatorEvent.AccessSessionExpired -> {
                val acceptedGeneration =
                    lifecycleMutex.withLock {
                        stateMutex.withLock {
                            if (closed ||
                                teardownInProgress ||
                                activeSessionRevision != event.sessionRevision
                            ) {
                                null
                            } else {
                                activeSessionRevision = null
                                generation
                            }
                        }
                    }
                if (acceptedGeneration != null) {
                    closeSessionWork(
                        reason = CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST,
                        sessionRevision = event.sessionRevision,
                    )
                    val stillMissing =
                        stateMutex.withLock {
                            !closed && generation == acceptedGeneration && activeSessionRevision == null
                        }
                    if (stillMissing) {
                        updateStatus(CompanionAuthorizationResult.SESSION_EXPIRED)
                        adapterScope.launch {
                            val current =
                                stateMutex.withLock {
                                    !closed &&
                                        generation == acceptedGeneration &&
                                        activeSessionRevision == null
                                }
                            if (current) recover(CompanionAuthorizationRecoveryTrigger.SESSION_EXPIRED)
                        }
                    }
                }
            }
        }
    }

    private suspend fun mapCoordinatorOutcomeIfCurrent(
        outcome: AuthorizationCoordinatorOutcome,
        expectedGeneration: Long,
        retainAuthorityOnSessionExpired: Boolean = false,
        fencedSessionRevision: Long? = null,
    ) {
        val actions =
            lifecycleMutex.withLock {
                stateMutex.withLock {
                    if (closed || generation != expectedGeneration) return@withLock null
                    prepareCoordinatorOutcomeLocked(
                        outcome = outcome,
                        retainAuthorityOnSessionExpired = retainAuthorityOnSessionExpired,
                        fencedSessionRevision = fencedSessionRevision,
                    )
                }
            } ?: return
        executeOutcomeActions(actions, expectedGeneration)
    }

    private fun prepareCoordinatorOutcomeLocked(
        outcome: AuthorizationCoordinatorOutcome,
        retainAuthorityOnSessionExpired: Boolean,
        fencedSessionRevision: Long?,
    ): CompanionAuthorizationOutcomeActions =
        when (outcome) {
            is AuthorizationCoordinatorOutcome.Authorized -> {
                activeSessionRevision = outcome.sessionRevision
                restoreConnectingAfterReachableResponse()
                updateStatus(
                    CompanionAuthorizationStatus(
                        result = CompanionAuthorizationResult.AUTHORITY_READY,
                        expiresAtEpochSeconds = outcome.expiresAtEpochSeconds,
                    ),
                )
                CompanionAuthorizationOutcomeActions.NONE
            }

            is AuthorizationCoordinatorOutcome.RemoteFailure -> {
                restoreConnectingAfterReachableResponse()
                prepareRemoteFailureLocked(outcome, fencedSessionRevision)
            }

            AuthorizationCoordinatorOutcome.PairingRequired -> {
                val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                activeSessionRevision = null
                val barrier = facade.claimRecoveredPairingCleanup()
                if (barrier != null) facade.unpair()
                updateStatus(
                    if (barrier == null) {
                        CompanionAuthorizationResult.CLEANUP_INCOMPLETE
                    } else {
                        CompanionAuthorizationResult.PAIRING_REQUIRED
                    },
                )
                CompanionAuthorizationOutcomeActions.authorityLoss(
                    sessionRevision = sessionRevision,
                    cleanup =
                        barrier?.let {
                            CompanionAuthorizationCleanupAction(
                                lease = claimCleanupLeaseLocked(it),
                                successResult = CompanionAuthorizationResult.PAIRING_REQUIRED,
                            )
                        },
                )
            }

            AuthorizationCoordinatorOutcome.NetworkUnavailable -> {
                facade.transportBudgetExhausted()
                updateStatus(CompanionAuthorizationResult.NETWORK_UNAVAILABLE)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationCoordinatorOutcome.DeviceAuthenticationCancelled -> {
                updateStatus(CompanionAuthorizationResult.DEVICE_AUTHENTICATION_CANCELLED)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationCoordinatorOutcome.LocalStorageUnavailable,
            AuthorizationCoordinatorOutcome.LocalSecurityUnavailable,
            -> {
                updateStatus(CompanionAuthorizationResult.LOCAL_UNAVAILABLE)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationCoordinatorOutcome.ContractFailure -> {
                updateStatus(CompanionAuthorizationResult.CONTRACT_FAILURE)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationCoordinatorOutcome.UnexpectedFailure -> {
                updateStatus(CompanionAuthorizationResult.ENGINE_FAILURE)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationCoordinatorOutcome.SessionExpired -> {
                if (retainAuthorityOnSessionExpired && activeSessionRevision != null) {
                    updateStatus(
                        CompanionAuthorizationStatus(
                            result = CompanionAuthorizationResult.AUTHORITY_READY,
                            expiresAtEpochSeconds = status.value.expiresAtEpochSeconds,
                        ),
                    )
                    CompanionAuthorizationOutcomeActions.NONE
                } else {
                    val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                    activeSessionRevision = null
                    updateStatus(CompanionAuthorizationResult.SESSION_EXPIRED)
                    CompanionAuthorizationOutcomeActions.authorityLoss(sessionRevision)
                }
            }

            AuthorizationCoordinatorOutcome.OutsideActiveForeground -> {
                updateStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationCoordinatorOutcome.Closed -> {
                val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                activeSessionRevision = null
                updateStatus(CompanionAuthorizationResult.CLOSED)
                CompanionAuthorizationOutcomeActions(
                    closeReason = CompanionAuthenticatedSessionWorkCloseReason.CLOSED,
                    sessionRevision = sessionRevision,
                )
            }
        }

    private fun prepareRemoteFailureLocked(
        outcome: AuthorizationCoordinatorOutcome.RemoteFailure,
        fencedSessionRevision: Long?,
    ): CompanionAuthorizationOutcomeActions =
        when (outcome.failure) {
            AuthorizationRemoteFailure.NOT_AUTHORIZED -> {
                val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                activeSessionRevision = null
                val barrier = facade.claimRecoveredPairingCleanup()
                if (barrier != null) facade.notAuthorized()
                updateStatus(
                    if (barrier == null) {
                        CompanionAuthorizationResult.CLEANUP_INCOMPLETE
                    } else {
                        CompanionAuthorizationResult.REVOKED
                    },
                )
                CompanionAuthorizationOutcomeActions.authorityLoss(
                    sessionRevision = sessionRevision,
                    cleanup =
                        barrier?.let {
                            CompanionAuthorizationCleanupAction(
                                lease = claimCleanupLeaseLocked(it),
                                successResult = CompanionAuthorizationResult.REVOKED,
                            )
                        },
                )
            }

            AuthorizationRemoteFailure.PROFILE_MISMATCH -> {
                val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                activeSessionRevision = null
                facade.profileMismatch()
                updateStatus(CompanionAuthorizationResult.PROFILE_MISMATCH)
                CompanionAuthorizationOutcomeActions.authorityLoss(sessionRevision)
            }

            AuthorizationRemoteFailure.LOCKED_ENGINE -> {
                val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                activeSessionRevision = null
                facade.engineLocked()
                updateStatus(CompanionAuthorizationResult.ENGINE_LOCKED)
                CompanionAuthorizationOutcomeActions.authorityLoss(sessionRevision)
            }

            AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL -> {
                val sessionRevision = fencedSessionRevision ?: activeSessionRevision
                activeSessionRevision = null
                facade.incompatibleProtocol()
                updateStatus(CompanionAuthorizationResult.INCOMPATIBLE)
                CompanionAuthorizationOutcomeActions.authorityLoss(sessionRevision)
            }

            AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE -> {
                facade.challengeUnavailable()
                updateStatus(CompanionAuthorizationResult.CHALLENGE_UNAVAILABLE)
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationRemoteFailure.RATE_LIMITED -> {
                updateStatus(
                    CompanionAuthorizationStatus(
                        result = CompanionAuthorizationResult.RATE_LIMITED,
                        retryAfterSeconds = outcome.retryAfterSeconds,
                    ),
                )
                CompanionAuthorizationOutcomeActions.NONE
            }

            AuthorizationRemoteFailure.INVALID_REQUEST,
            AuthorizationRemoteFailure.UNEXPECTED_ENGINE_ERROR,
            -> {
                updateStatus(CompanionAuthorizationResult.ENGINE_FAILURE)
                CompanionAuthorizationOutcomeActions.NONE
            }
        }

    private suspend fun executeOutcomeActions(
        actions: CompanionAuthorizationOutcomeActions,
        expectedGeneration: Long,
    ) {
        val cleanup = actions.cleanup
        if (cleanup == null) {
            actions.closeReason?.let { reason ->
                closeSessionWork(reason, actions.sessionRevision)
            }
            return
        }
        withContext(NonCancellable) {
            val sessionInvalidation =
                actions.closeReason?.let { reason ->
                    beginCloseSessionWork(reason, actions.sessionRevision)
                } ?: CompanionAuthenticatedSessionWorkInvalidation { true }
            val participantSucceeded =
                performCleanupParticipant(
                    lease = cleanup.lease,
                    sessionWorkInvalidation = sessionInvalidation,
                )
            stateMutex.withLock {
                if (!closed && generation == expectedGeneration) {
                    updateStatus(
                        if (participantSucceeded) {
                            cleanup.successResult
                        } else {
                            CompanionAuthorizationResult.CLEANUP_INCOMPLETE
                        },
                    )
                }
            }
            val completed =
                completeCleanupParticipant(
                    lease = cleanup.lease,
                    participantSucceeded = participantSucceeded,
                )
            if (!completed) {
                stateMutex.withLock {
                    if (!closed && generation == expectedGeneration) {
                        updateStatus(CompanionAuthorizationResult.CLEANUP_INCOMPLETE)
                    }
                }
            }
        }
    }

    private fun restoreConnectingAfterReachableResponse() {
        if (facade.status.value.rootState == CompanionRootState.Unreachable) {
            facade.transportRestored()
        }
    }

    private suspend fun destroyLocalAuthority(): Boolean =
        withContext(NonCancellable + companionAuthorizationExternalCallbackContext) {
            try {
                localAuthorityCleaner.destroyAll()
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun performCleanupParticipant(
        lease: CompanionAuthorizationCleanupLease,
        sessionWorkInvalidation: CompanionAuthenticatedSessionWorkInvalidation,
    ): Boolean =
        withContext(NonCancellable) {
            coroutineScope {
                val cleanup =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        if (lease.ownsMaterialCleanup) {
                            val result = destroyLocalAuthority()
                            lease.flight.materialCompletion.complete(result)
                        }
                        lease.flight.materialCompletion.await()
                    }
                val sessionClose =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        awaitSessionWorkClose(sessionWorkInvalidation)
                    }
                val cleaned = cleanup.await()
                val sessionClosed = sessionClose.await()
                cleaned && sessionClosed
            }
        }

    private fun claimCleanupLeaseLocked(barrier: PairingCleanupHandle): CompanionAuthorizationCleanupLease {
        val existing = cleanupFlights[barrier.token]
        val flight = existing ?: CompanionAuthorizationCleanupFlight(barrier)
        if (existing == null) cleanupFlights[barrier.token] = flight
        flight.participantCount += 1
        return CompanionAuthorizationCleanupLease(
            flight = flight,
            ownsMaterialCleanup = existing == null,
        )
    }

    private suspend fun completeCleanupParticipant(
        lease: CompanionAuthorizationCleanupLease,
        participantSucceeded: Boolean,
    ): Boolean {
        val completion =
            stateMutex.withLock {
                val flight = lease.flight
                flight.allParticipantsSucceeded =
                    flight.allParticipantsSucceeded && participantSucceeded
                flight.participantCount -= 1
                check(flight.participantCount >= 0) { "Authorization cleanup participant underflow" }
                if (flight.participantCount == 0) {
                    val completed =
                        flight.allParticipantsSucceeded &&
                            facade.completeAuthorizationCleanup(flight.barrier)
                    if (cleanupFlights[flight.barrier.token] === flight) {
                        cleanupFlights.remove(flight.barrier.token)
                    }
                    flight.completion.complete(completed)
                }
                flight.completion
            }
        return completion.await()
    }

    private fun beginCloseSessionWork(
        reason: CompanionAuthenticatedSessionWorkCloseReason,
        sessionRevision: Long?,
    ): CompanionAuthenticatedSessionWorkInvalidation =
        try {
            sessionWorkController.beginClose(sessionRevision, reason)
        } catch (_: Exception) {
            CompanionAuthenticatedSessionWorkInvalidation { false }
        }

    private suspend fun closeSessionWork(
        reason: CompanionAuthenticatedSessionWorkCloseReason,
        sessionRevision: Long? = null,
    ): Boolean = awaitSessionWorkClose(beginCloseSessionWork(reason, sessionRevision))

    private suspend fun awaitSessionWorkClose(invalidation: CompanionAuthenticatedSessionWorkInvalidation): Boolean =
        withContext(NonCancellable + companionAuthorizationExternalCallbackContext) {
            try {
                invalidation.awaitCompletion()
            } catch (_: Exception) {
                // The root transition and bearer fence remain fail-closed if transport close fails.
                false
            }
        }

    private suspend fun ensureNotExternalCallbackReentry() {
        check(currentCoroutineContext()[CompanionAuthorizationExternalCallbackContext] == null) {
            "Authorization external callback must not re-enter its adapter"
        }
    }

    private suspend fun isCurrentActiveRecovery(flight: CompanionAuthorizationRecoveryFlight): Boolean =
        stateMutex.withLock {
            isCurrentRecoveryLocked(flight) &&
                applicationVisibility.state.value == ApplicationVisibilityState.ACTIVE_FOREGROUND
        }

    private suspend fun isCurrentRecovery(flight: CompanionAuthorizationRecoveryFlight): Boolean =
        stateMutex.withLock { isCurrentRecoveryLocked(flight) }

    private fun isCurrentRecoveryLocked(flight: CompanionAuthorizationRecoveryFlight): Boolean =
        !closed && processJob.isActive && generation == flight.generation && recoveryFlight === flight

    private fun fenceAdapterAuthorityLocked(): Long? {
        val fencedSessionRevision = activeSessionRevision
        activeSessionRevision = null
        exchangeGenerations.clear()
        return fencedSessionRevision
    }

    private fun invalidateRecoveryLocked(): CompanionAuthorizationAdapterInvalidation {
        generation += 1
        val sessionRevision = fenceAdapterAuthorityLocked()
        val flight = recoveryFlight
        recoveryFlight = null
        flight?.invalidate(updateStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND))
        return CompanionAuthorizationAdapterInvalidation(
            recoveryJob = flight?.job,
            sessionRevision = sessionRevision,
        )
    }

    private fun coarseStatus(outcome: AuthorizationCoordinatorOutcome): CompanionAuthorizationStatus =
        when (outcome) {
            is AuthorizationCoordinatorOutcome.Authorized -> {
                CompanionAuthorizationStatus(
                    result = CompanionAuthorizationResult.AUTHORITY_READY,
                    expiresAtEpochSeconds = outcome.expiresAtEpochSeconds,
                )
            }

            is AuthorizationCoordinatorOutcome.RemoteFailure -> {
                status.value
            }

            AuthorizationCoordinatorOutcome.OutsideActiveForeground -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND)
            }

            AuthorizationCoordinatorOutcome.PairingRequired -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.PAIRING_REQUIRED)
            }

            AuthorizationCoordinatorOutcome.LocalStorageUnavailable,
            AuthorizationCoordinatorOutcome.LocalSecurityUnavailable,
            -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.LOCAL_UNAVAILABLE)
            }

            AuthorizationCoordinatorOutcome.DeviceAuthenticationCancelled -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.DEVICE_AUTHENTICATION_CANCELLED)
            }

            AuthorizationCoordinatorOutcome.SessionExpired -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.SESSION_EXPIRED)
            }

            AuthorizationCoordinatorOutcome.NetworkUnavailable -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.NETWORK_UNAVAILABLE)
            }

            AuthorizationCoordinatorOutcome.ContractFailure -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.CONTRACT_FAILURE)
            }

            AuthorizationCoordinatorOutcome.UnexpectedFailure -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.ENGINE_FAILURE)
            }

            AuthorizationCoordinatorOutcome.Closed -> {
                CompanionAuthorizationStatus(CompanionAuthorizationResult.CLOSED)
            }
        }

    private fun updateStatus(result: CompanionAuthorizationResult): CompanionAuthorizationStatus =
        updateStatus(CompanionAuthorizationStatus(result))

    private fun updateStatus(status: CompanionAuthorizationStatus): CompanionAuthorizationStatus {
        mutableStatus.value = status
        return status
    }

    private suspend fun updateStatusIfCurrent(
        admittedGeneration: Long,
        newStatus: CompanionAuthorizationStatus,
        sessionRevision: Long? = null,
    ) {
        stateMutex.withLock {
            if (!closed && generation == admittedGeneration && canAuthorizeLocked()) {
                sessionRevision?.let { activeSessionRevision = it }
                updateStatus(newStatus)
            }
        }
    }

    private suspend fun completeTeardownIfCurrent(
        expectedGeneration: Long,
        newStatus: CompanionAuthorizationStatus,
    ) {
        stateMutex.withLock {
            if (!closed && generation == expectedGeneration) {
                updateStatus(newStatus)
                teardownInProgress = false
            }
        }
    }

    private suspend fun updateStatusIfGenerationCurrent(
        expectedGeneration: Long,
        newStatus: CompanionAuthorizationStatus,
    ) {
        stateMutex.withLock {
            if (!closed && generation == expectedGeneration) updateStatus(newStatus)
        }
    }

    private suspend fun updateStatusIfRecoveryCurrent(
        flight: CompanionAuthorizationRecoveryFlight,
        newStatus: CompanionAuthorizationStatus,
    ): CompanionAuthorizationStatus? =
        stateMutex.withLock {
            if (isCurrentRecoveryLocked(flight)) updateStatus(newStatus) else null
        }

    private fun canAuthorizeLocked(): Boolean =
        !closed &&
            !teardownInProgress &&
            processJob.isActive &&
            applicationVisibility.state.value == ApplicationVisibilityState.ACTIVE_FOREGROUND &&
            facade.status.value.rootState == CompanionRootState.Connecting
}

/** One attach-only bridge so the coordinator can be constructed before its facade adapter. */
internal class CompanionAuthorizationEventBridge : AuthorizationCoordinatorEventSink {
    private var receiver: (suspend (AuthorizationCoordinatorEvent) -> Unit)? = null

    internal fun attach(receiver: suspend (AuthorizationCoordinatorEvent) -> Unit) {
        check(this.receiver == null) { "Authorization event bridge is already attached" }
        this.receiver = receiver
    }

    override suspend fun emit(event: AuthorizationCoordinatorEvent) {
        requireNotNull(receiver) { "Authorization event bridge is not attached" }(event)
    }

    override fun toString(): String = "CompanionAuthorizationEventBridge(redacted)"
}

private data class CompanionAuthorizationCleanupAction(
    val lease: CompanionAuthorizationCleanupLease,
    val successResult: CompanionAuthorizationResult,
)

private class CompanionAuthorizationCleanupFlight(
    val barrier: PairingCleanupHandle,
    val materialCompletion: CompletableDeferred<Boolean> = CompletableDeferred(),
    val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
) {
    var participantCount: Int = 0
    var allParticipantsSucceeded: Boolean = true
}

private data class CompanionAuthorizationCleanupLease(
    val flight: CompanionAuthorizationCleanupFlight,
    val ownsMaterialCleanup: Boolean,
)

private data class CompanionAuthorizationExplicitFence(
    val sessionWorkInvalidation: CompanionAuthenticatedSessionWorkInvalidation,
    val clearInvalidation: AuthorizationInvalidation,
    val selectionInvalidation: AuthorizationInvalidation,
)

private data class CompanionAuthorizationAuthorityFence(
    val clearInvalidation: AuthorizationInvalidation,
    val sessionWorkInvalidation: CompanionAuthenticatedSessionWorkInvalidation,
)

private data class CompanionAuthorizationDiscoveredFence(
    val sessionRevision: Long?,
    val clearInvalidation: AuthorizationInvalidation,
)

private data class CompanionAuthorizationAdapterInvalidation(
    val recoveryJob: Job?,
    val sessionRevision: Long?,
)

private data class CompanionAuthorizationTeardown(
    val recoveryJob: Job?,
    val expectedGeneration: Long,
    val authorityInvalidation: AuthorizationInvalidation,
    val sessionWorkInvalidation: CompanionAuthenticatedSessionWorkInvalidation,
)

private data class CompanionAuthorizationCloseTeardown(
    val recoveryJob: Job?,
    val authorityInvalidation: AuthorizationInvalidation,
    val sessionWorkInvalidation: CompanionAuthenticatedSessionWorkInvalidation,
)

private data class CompanionAuthorizationAcceptedExchange(
    val generation: Long,
    val replacedSessionRevision: Long?,
)

private data class CompanionAuthorizationOutcomeActions(
    val closeReason: CompanionAuthenticatedSessionWorkCloseReason? = null,
    val sessionRevision: Long? = null,
    val cleanup: CompanionAuthorizationCleanupAction? = null,
) {
    companion object {
        val NONE = CompanionAuthorizationOutcomeActions()

        fun authorityLoss(
            sessionRevision: Long?,
            cleanup: CompanionAuthorizationCleanupAction? = null,
        ): CompanionAuthorizationOutcomeActions =
            CompanionAuthorizationOutcomeActions(
                closeReason = CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST,
                sessionRevision = sessionRevision,
                cleanup = cleanup,
            )
    }
}

private enum class CompanionAuthorizationRecoveryTrigger(
    val clearsAccessAuthority: Boolean,
    val priority: Int,
) {
    EXPLICIT_FOREGROUND_RETRY(clearsAccessAuthority = false, priority = 0),
    SESSION_EXPIRED(clearsAccessAuthority = false, priority = 1),
    ACCESS_SESSION_UNAVAILABLE(clearsAccessAuthority = true, priority = 2),
    WEBSOCKET_1008(clearsAccessAuthority = true, priority = 2),
}

private class CompanionAuthorizationRecoveryFlight(
    val generation: Long,
    val trigger: CompanionAuthorizationRecoveryTrigger,
    val result: CompletableDeferred<CompanionAuthorizationStatus> = CompletableDeferred(),
) {
    lateinit var job: Job
        private set

    fun attach(job: Job) {
        check(!this::job.isInitialized) { "Authorization recovery flight already has a job" }
        this.job = job
    }

    fun start() {
        job.start()
    }

    fun invalidate(status: CompanionAuthorizationStatus) {
        result.complete(status)
        job.cancel()
    }
}

private class CompanionAuthorizationRecoveryAdmission private constructor(
    val flight: CompanionAuthorizationRecoveryFlight?,
    val cached: CompanionAuthorizationStatus?,
    val cancelledJob: Job?,
) {
    companion object {
        fun join(
            flight: CompanionAuthorizationRecoveryFlight,
            cancelledJob: Job? = null,
        ): CompanionAuthorizationRecoveryAdmission =
            CompanionAuthorizationRecoveryAdmission(flight, cached = null, cancelledJob = cancelledJob)

        fun closed(): CompanionAuthorizationRecoveryAdmission =
            CompanionAuthorizationRecoveryAdmission(
                flight = null,
                cached = CompanionAuthorizationStatus(CompanionAuthorizationResult.CLOSED),
                cancelledJob = null,
            )

        fun outsideForeground(): CompanionAuthorizationRecoveryAdmission =
            CompanionAuthorizationRecoveryAdmission(
                flight = null,
                cached =
                    CompanionAuthorizationStatus(
                        CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND,
                    ),
                cancelledJob = null,
            )
    }
}

private class CompanionAuthorizationExternalCallbackContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CompanionAuthorizationExternalCallbackContext>
}

private val companionAuthorizationExternalCallbackContext =
    CompanionAuthorizationExternalCallbackContext()

private fun AuthorizationCoordinatorOutcome.destroysAccessAuthority(): Boolean =
    when (this) {
        AuthorizationCoordinatorOutcome.PairingRequired,
        AuthorizationCoordinatorOutcome.SessionExpired,
        AuthorizationCoordinatorOutcome.Closed,
        -> {
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
