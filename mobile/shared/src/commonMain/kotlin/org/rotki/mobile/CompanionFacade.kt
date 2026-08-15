package org.rotki.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.rotki.mobile.auth.CompanionAuthorizationAutoInstallation
import org.rotki.mobile.auth.CompanionAuthorizationInstaller
import org.rotki.mobile.auth.CompanionPairingSessionAdapter
import org.rotki.mobile.auth.PairingConnection
import org.rotki.mobile.auth.PairingConnectionConfiguration
import org.rotki.mobile.auth.PairingFlow
import org.rotki.mobile.auth.createPlatformCompanionAuthorizationInstaller
import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionCoordinator
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.CompanionTransitionEvent
import org.rotki.mobile.core.state.CompanionTransitionOutcome
import org.rotki.mobile.core.state.SnapshotCoverage

public class CompanionFacade internal constructor(
    initialStatus: CompanionStatus,
    authorizationInstaller: CompanionAuthorizationInstaller? =
        createPlatformCompanionAuthorizationInstaller(),
) {
    public constructor() : this(
        CompanionStatus(
            rootState = CompanionRootState.Unpaired,
            snapshotCoverage = SnapshotCoverage.Absent,
        ),
    )

    private val coordinator: CompanionCoordinator = CompanionCoordinator(initialStatus)
    private val pairingOwnership: MutableStateFlow<PairingOwnershipState> =
        MutableStateFlow(
            PairingOwnershipState(
                attempt = null,
                cleanup = null,
                authorityEpoch =
                    if (initialStatus.rootState != CompanionRootState.Unpaired &&
                        initialStatus.rootState != CompanionRootState.Revoked
                    ) {
                        Any()
                    } else {
                        null
                    },
            ),
        )
    private val pairingConnectionMutex: Mutex = Mutex()
    private val pairingSessionAdapter: CompanionPairingSessionAdapter =
        CompanionPairingSessionAdapter(this)
    private val authorizationAutoInstallation: CompanionAuthorizationAutoInstallation =
        CompanionAuthorizationAutoInstallation(authorizationInstaller)

    public val status: StateFlow<CompanionStatus> = coordinator.status

    public companion object {
        private val COMMITTABLE_PAIRING_STATES: Set<CompanionRootState> =
            setOf(
                CompanionRootState.Connecting,
                CompanionRootState.DeviceLocked,
            )

        public fun restorePaired(snapshotCoverage: SnapshotCoverage): CompanionFacade =
            CompanionFacade(
                CompanionStatus(
                    rootState = CompanionRootState.DeviceLocked,
                    snapshotCoverage = snapshotCoverage,
                ),
            )
    }

    internal fun beginPairing(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.ACCEPT_PAIRING_QR)

    internal fun acceptPairing(pairingQr: PairingQr): CompanionTransitionOutcome =
        acceptPairing(pairingQr, afterPendingStored = {})

    internal fun acceptPairing(
        pairingQr: PairingQr,
        afterPendingStored: () -> Unit,
    ): CompanionTransitionOutcome =
        acceptPairing(
            pairingQr = pairingQr,
            afterPendingStored = afterPendingStored,
            beforeFinalOwnershipCheck = {},
        )

    internal fun acceptPairing(
        pairingQr: PairingQr,
        afterPendingStored: () -> Unit,
        beforeFinalOwnershipCheck: () -> Unit,
        afterPendingPublishedBeforeStatusCheck: () -> Unit = {},
    ): CompanionTransitionOutcome {
        val statusBeforeAdmission = status.value
        val rootStateBeforeAdmission = statusBeforeAdmission.rootState
        val emptyOwnership = pairingOwnership.value
        if (emptyOwnership.cleanup != null ||
            emptyOwnership.attempt != null ||
            emptyOwnership.authorityEpoch != null ||
            (
                rootStateBeforeAdmission != CompanionRootState.Unpaired &&
                    rootStateBeforeAdmission != CompanionRootState.Revoked
            )
        ) {
            return rejectedPairingAcceptance()
        }
        val attempt = PendingPairingAttempt(pairingQr)
        val attemptOwnership =
            PairingOwnershipState(
                attempt = attempt,
                cleanup = null,
                authorityEpoch = emptyOwnership.authorityEpoch,
            )
        if (!pairingOwnership.compareAndSet(expect = emptyOwnership, update = attemptOwnership)) {
            return rejectedPairingAcceptance()
        }
        afterPendingPublishedBeforeStatusCheck()
        if (status.value !== statusBeforeAdmission ||
            pairingOwnership.value !== attemptOwnership
        ) {
            clearPendingPairing(attempt.token)
            return rejectedPairingAcceptance()
        }
        afterPendingStored()
        if (pairingOwnership.value.cleanup != null) {
            clearPendingPairing(attempt.token)
            return rejectedPairingAcceptance()
        }
        val outcome = beginPairing()
        if (outcome !is CompanionTransitionOutcome.Applied) {
            clearPendingPairing(attempt.token)
            return outcome
        }
        beforeFinalOwnershipCheck()
        if (pairingOwnership.value.cleanup != null ||
            pairingOwnership.value.attempt?.token !== attempt.token
        ) {
            clearPendingPairing(attempt.token)
            coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
            return rejectedPairingAcceptance()
        }
        return outcome
    }

    /** One-shot internal hand-off for shared registration; never exported to native UI. */
    internal fun takePendingPairingForConnection(): PendingPairingLease? {
        while (true) {
            val currentOwnership = pairingOwnership.value
            if (currentOwnership.cleanup != null) return null
            val current = currentOwnership.attempt ?: return null
            val pairingQr = current.pairingQr ?: return null
            val consumed =
                PendingPairingAttempt(
                    pairingQr = null,
                    token = current.token,
                    durableRegistration = current.durableRegistration,
                )
            if (
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withAttempt(consumed),
                )
            ) {
                return PendingPairingLease(pairingQr, current.token)
            }
        }
    }

    internal fun isPendingPairing(lease: PendingPairingLease): Boolean =
        pairingOwnership.value.attempt?.token === lease.token

    internal suspend fun awaitPendingPairingLoss(lease: PendingPairingLease) {
        pairingOwnership.first { ownership -> ownership.attempt?.token !== lease.token }
    }

    /** Marks a persisted registration as the durable Pairing relationship. */
    internal fun markPendingPairingDurable(lease: PendingPairingLease): Boolean {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val current = currentOwnership.attempt ?: return false
            if (current.token !== lease.token || current.pairingQr != null) return false
            if (status.value.rootState != CompanionRootState.Connecting) return false
            if (currentOwnership.authorityEpoch !== lease.token) {
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withAuthorityEpoch(lease.token),
                )
                continue
            }
            if (current.durableRegistration) return true
            val durable =
                PendingPairingAttempt(
                    pairingQr = null,
                    token = current.token,
                    durableRegistration = true,
                )
            if (
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withAttempt(durable),
                )
            ) {
                return status.value.rootState in COMMITTABLE_PAIRING_STATES
            }
        }
    }

    /** Clears only an attempt whose registration is already durable and locally recoverable. */
    internal fun commitPendingPairing(
        lease: PendingPairingLease,
        afterRootPrecheckBeforeCleanupRelease: () -> Unit = {},
    ): Boolean {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val current = currentOwnership.attempt ?: return false
            if (current.token !== lease.token || !current.durableRegistration) return false
            val cleanup = currentOwnership.cleanup ?: return false
            if (cleanup.authorizationOwned || cleanup.token !== current.token) return false
            if (status.value.rootState !in COMMITTABLE_PAIRING_STATES) return false
            afterRootPrecheckBeforeCleanupRelease()
            if (status.value.rootState !in COMMITTABLE_PAIRING_STATES) return false
            val committedOwnership =
                PairingOwnershipState(
                    attempt = null,
                    cleanup = null,
                    authorityEpoch = currentOwnership.authorityEpoch,
                )
            if (pairingOwnership.compareAndSet(currentOwnership, committedOwnership)) {
                return true
            }
        }
    }

    /** Cancels only the matching uncommitted attempt and returns the facade to Unpaired. */
    internal fun abortPendingPairing(lease: PendingPairingLease): Boolean {
        if (!clearPendingPairing(lease.token)) return false
        coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
        return true
    }

    internal fun markPairingCleanupRequired(lease: PendingPairingLease): PairingCleanupHandle? {
        while (true) {
            val currentOwnership = pairingOwnership.value
            if (currentOwnership.attempt?.token !== lease.token) return null
            val existing = currentOwnership.cleanup
            if (existing != null) {
                return if (existing.token === lease.token && !existing.authorizationOwned) {
                    PairingCleanupHandle(existing.token, existing.releaseCapability)
                } else {
                    null
                }
            }
            val cleanup =
                PairingCleanupOwnership(
                    token = lease.token,
                    releaseCapability = Any(),
                    authorizationOwned = false,
                )
            if (
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withCleanup(cleanup),
                )
            ) {
                return PairingCleanupHandle(cleanup.token, cleanup.releaseCapability)
            }
        }
    }

    internal fun hasPendingPairingCleanup(): Boolean = pairingOwnership.value.cleanup != null

    internal fun completePendingPairingCleanup(handle: PairingCleanupHandle): Boolean {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val cleanup = currentOwnership.cleanup ?: return false
            if (cleanup.authorizationOwned ||
                cleanup.token !== handle.token ||
                cleanup.releaseCapability !== handle.releaseCapability
            ) {
                return false
            }
            val completedOwnership =
                PairingOwnershipState(
                    attempt = null,
                    cleanup = null,
                    authorityEpoch = null,
                )
            if (pairingOwnership.compareAndSet(currentOwnership, completedOwnership)) break
        }
        coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
        return true
    }

    internal fun abandonPendingPairingCleanup(handle: PairingCleanupHandle): Boolean {
        var abandonedAttempt = false
        while (true) {
            val currentOwnership = pairingOwnership.value
            val cleanup = currentOwnership.cleanup ?: return false
            if (cleanup.authorizationOwned ||
                cleanup.token !== handle.token ||
                cleanup.releaseCapability !== handle.releaseCapability
            ) {
                return false
            }
            val attempt = currentOwnership.attempt
            val abandonedOwnership =
                PairingOwnershipState(
                    attempt = null,
                    cleanup = null,
                    authorityEpoch =
                        if (attempt?.token === currentOwnership.authorityEpoch) {
                            null
                        } else {
                            currentOwnership.authorityEpoch
                        },
                )
            if (pairingOwnership.compareAndSet(currentOwnership, abandonedOwnership)) {
                abandonedAttempt = attempt != null
                break
            }
        }
        if (abandonedAttempt) {
            coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
        }
        return true
    }

    /** Releases a facade-scoped destructive-cleanup barrier without changing terminal root state. */
    internal fun completeAuthorizationCleanup(handle: PairingCleanupHandle): Boolean {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val cleanup = currentOwnership.cleanup ?: return false
            if (!cleanup.authorizationOwned ||
                cleanup.token !== handle.token ||
                cleanup.releaseCapability !== handle.releaseCapability
            ) {
                return false
            }
            val completedOwnership =
                PairingOwnershipState(
                    attempt = null,
                    cleanup = null,
                    authorityEpoch = null,
                )
            if (pairingOwnership.compareAndSet(currentOwnership, completedOwnership)) return true
        }
    }

    /** Claims the cleanup barrier before an explicit unpair clears any pending attempt. */
    internal fun claimLocalUnpairCleanup(transitionToUnpaired: Boolean = true): PairingCleanupHandle {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val existing = currentOwnership.cleanup
            val cleanup =
                when {
                    existing == null -> {
                        PairingCleanupOwnership(
                            token = Any(),
                            releaseCapability = Any(),
                            authorizationOwned = true,
                        )
                    }

                    existing.authorizationOwned -> {
                        existing
                    }

                    else -> {
                        PairingCleanupOwnership(
                            token = existing.token,
                            releaseCapability = Any(),
                            authorizationOwned = true,
                        )
                    }
                }
            val claimedOwnership =
                PairingOwnershipState(
                    attempt = null,
                    cleanup = cleanup,
                    authorityEpoch = currentOwnership.authorityEpoch,
                )
            if (pairingOwnership.compareAndSet(currentOwnership, claimedOwnership)) {
                if (transitionToUnpaired) {
                    coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
                }
                return PairingCleanupHandle(cleanup.token, cleanup.releaseCapability)
            }
        }
    }

    /**
     * Establishes a non-destructive cleanup barrier before recovery state is inspected.
     *
     * A concurrent admission either loses its final ownership check or observes this marker, so
     * recovered cleanup can never silently delete a replacement attempt after the barrier exists.
     * Existing root authority is changed only after the journal proves cleanup is required, or
     * when abandoning an attempt that was already pending when the barrier was claimed.
     */
    internal fun claimRecoveredPairingCleanup(
        forAuthorization: Boolean = false,
        afterPendingObservedAbsent: () -> Unit = {},
    ): PairingCleanupHandle? {
        val emptyOwnership = pairingOwnership.value
        val expectedAuthorityEpoch = emptyOwnership.authorityEpoch
        emptyOwnership.cleanup?.let { existing ->
            if (!forAuthorization) {
                if (existing.authorizationOwned) return null
                val cleanup =
                    PairingCleanupOwnership(
                        token = existing.token,
                        releaseCapability = Any(),
                        authorizationOwned = false,
                    )
                val claimedOwnership = emptyOwnership.withCleanup(cleanup)
                return if (pairingOwnership.compareAndSet(emptyOwnership, claimedOwnership)) {
                    PairingCleanupHandle(cleanup.token, cleanup.releaseCapability)
                } else {
                    null
                }
            }
            if (existing.authorizationOwned) {
                return PairingCleanupHandle(existing.token, existing.releaseCapability)
            }
            val cleanup =
                PairingCleanupOwnership(
                    token = existing.token,
                    releaseCapability = Any(),
                    authorizationOwned = true,
                )
            val claimedOwnership = emptyOwnership.withCleanup(cleanup)
            return if (pairingOwnership.compareAndSet(emptyOwnership, claimedOwnership)) {
                PairingCleanupHandle(cleanup.token, cleanup.releaseCapability)
            } else {
                null
            }
        }
        if (emptyOwnership.attempt != null) return null
        afterPendingObservedAbsent()
        if (pairingOwnership.value.authorityEpoch !== expectedAuthorityEpoch) return null
        val cleanup =
            PairingCleanupOwnership(
                token = Any(),
                releaseCapability = Any(),
                authorizationOwned = forAuthorization,
            )
        val claimedOwnership = emptyOwnership.withCleanup(cleanup)
        return if (pairingOwnership.compareAndSet(expect = emptyOwnership, update = claimedOwnership)) {
            PairingCleanupHandle(cleanup.token, cleanup.releaseCapability)
        } else {
            null
        }
    }

    internal suspend fun <T> withPairingConnectionOwnership(operation: suspend () -> T): T {
        pairingConnectionMutex.lock()
        return try {
            operation()
        } finally {
            pairingConnectionMutex.unlock()
        }
    }

    public fun pairingFlow(): PairingFlow = PairingFlow(pairingSessionAdapter)

    public fun pairingFlow(clock: Clock): PairingFlow = PairingFlow(pairingSessionAdapter, clock)

    @Suppress("TooGenericExceptionCaught")
    public fun pairingConnection(configuration: PairingConnectionConfiguration): PairingConnection {
        val authorizationLease =
            authorizationAutoInstallation.acquire(
                facade = this,
                configuration = configuration,
                initiallyArmed =
                    status.value.rootState == CompanionRootState.DeviceLocked &&
                        isAuthorizationRelationshipDurable(),
            )
        return try {
            PairingConnection.create(
                attempts = pairingSessionAdapter,
                configuration = configuration,
                authorizationHandoffLease = authorizationLease,
            )
        } catch (failure: Exception) {
            authorizationLease?.close()
            throw failure
        }
    }

    public fun lock(): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            transitionAuthorizationEvent(CompanionTransitionEvent.BACKGROUND_OR_SYSTEM_LOCK)
                .also { handoff?.onBackgroundOrSystemLock() }
        }

    public fun deviceAuthenticationSucceeded(): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            val outcome = coordinator.transition(CompanionTransitionEvent.DEVICE_AUTHENTICATION_SUCCEEDED)
            if (outcome is CompanionTransitionOutcome.Applied) {
                handoff?.onDeviceAuthenticationSucceeded()
            }
            outcome
        }

    public fun completeConnectionWithCompleteSnapshot(): CompanionTransitionOutcome {
        completePairingRegistration()
        return coordinator.transition(
            CompanionTransitionEvent.PROOF_AND_COMPLETE_SNAPSHOT_RECONCILED,
        )
    }

    public fun completeConnectionWithDegradedSnapshot(): CompanionTransitionOutcome {
        completePairingRegistration()
        return coordinator.transition(
            CompanionTransitionEvent.PROOF_AND_DEGRADED_SNAPSHOT_RECONCILED,
        )
    }

    public fun activeRefreshReconciled(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.ACTIVE_REFRESH_RECONCILED)

    public fun completeConnectionWithActiveRefresh(): CompanionTransitionOutcome {
        completePairingRegistration()
        return coordinator.transition(CompanionTransitionEvent.PROOF_AND_ACTIVE_REFRESH_RECONCILED)
    }

    public fun finishRefreshWithCompleteSnapshot(): CompanionTransitionOutcome =
        coordinator.transition(
            CompanionTransitionEvent.TERMINAL_REFRESH_AND_COMPLETE_SNAPSHOT,
        )

    public fun finishRefreshWithDegradedSnapshot(): CompanionTransitionOutcome =
        coordinator.transition(
            CompanionTransitionEvent.TERMINAL_REFRESH_AND_DEGRADED_SNAPSHOT,
        )

    public fun transportBudgetExhausted(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.TRANSPORT_RETRY_BUDGET_EXHAUSTED)

    public fun transportRestored(): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            val outcome = transitionAuthorizationEvent(CompanionTransitionEvent.TRANSPORT_RESTORED)
            if (outcome is CompanionTransitionOutcome.Applied) {
                handoff?.onExplicitForegroundRetryAfterTransition()
            }
            outcome
        }

    public fun accessSessionUnavailable(): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            val outcome =
                transitionAuthorizationEvent(CompanionTransitionEvent.ACCESS_SESSION_UNAVAILABLE)
            if (outcome is CompanionTransitionOutcome.Applied) {
                handoff?.onAccessSessionUnavailableAfterTransition()
            }
            outcome
        }

    public fun proactiveRenewalStarted(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.PROACTIVE_RENEWAL_STARTED)

    public fun webSocketPolicyClosed(): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            val outcome = transitionAuthorizationEvent(CompanionTransitionEvent.WEBSOCKET_CLOSE_1008)
            if (outcome is CompanionTransitionOutcome.Applied) {
                handoff?.onWebSocketPolicyClosedAfterTransition()
            }
            outcome
        }

    public fun engineLocked(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.LOCKED_ENGINE)

    public fun profileMismatch(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.PROFILE_MISMATCH)

    public fun incompatibleProtocol(): CompanionTransitionOutcome =
        coordinator.transition(
            CompanionTransitionEvent.NO_SHARED_PROTOCOL_OR_DEVICE_SESSIONS_CAPABILITY,
        )

    public fun notAuthorized(): CompanionTransitionOutcome {
        completePairingRegistration()
        return coordinator.transition(CompanionTransitionEvent.NOT_AUTHORIZED)
    }

    public fun challengeUnavailable(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.CHALLENGE_UNAVAILABLE)

    public fun retryResolvedEngineState(): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            val outcome = transitionAuthorizationEvent(CompanionTransitionEvent.EXPLICIT_FOREGROUND_RETRY)
            if (outcome is CompanionTransitionOutcome.Applied) {
                handoff?.onExplicitForegroundRetryAfterTransition()
            }
            outcome
        }

    public fun unpair(): CompanionTransitionOutcome = unpair(afterOwnershipCapturedBeforeTransition = {})

    internal fun unpair(
        afterOwnershipCapturedBeforeTransition: () -> Unit,
        afterTransitionBeforeCleanupClaim: () -> Unit = {},
    ): CompanionTransitionOutcome =
        authorizationAutoInstallation.withCurrentHandoff { handoff ->
            val ownershipBefore = pairingOwnership.value
            val rootStateBefore = status.value.rootState
            var cleanup =
                handoff
                    ?.takeIf {
                        ownershipBefore.authorityEpoch != null &&
                            ownershipBefore.attempt == null &&
                            rootStateBefore != CompanionRootState.Unpaired &&
                            rootStateBefore != CompanionRootState.Revoked
                    }?.let {
                        claimLocalUnpairCleanup(transitionToUnpaired = false)
                    }
            afterOwnershipCapturedBeforeTransition()
            val outcome = transitionAuthorizationEvent(CompanionTransitionEvent.LOCAL_UNPAIR)
            afterTransitionBeforeCleanupClaim()
            val ownershipAfter = pairingOwnership.value
            val originalAttemptToken = ownershipBefore.attempt?.token
            val originalPairingOwnsCleanup =
                originalAttemptToken != null && ownershipAfter.cleanup?.token === originalAttemptToken
            if (handoff != null &&
                cleanup == null &&
                ownershipAfter.attempt == null &&
                !originalPairingOwnsCleanup &&
                ownershipAfter.authorityEpoch != null &&
                (
                    ownershipBefore.attempt != null ||
                        ownershipAfter.authorityEpoch !== ownershipBefore.authorityEpoch
                )
            ) {
                cleanup = claimLocalUnpairCleanup(transitionToUnpaired = false)
            }
            // A rejection means a concurrent terminal transition won; join its cleanup barrier.
            if (handoff != null && cleanup != null) {
                handoff.onLocalUnpairAfterTransition(cleanup)
            }
            outcome
        }

    internal fun isAuthorizationRelationshipDurable(): Boolean {
        val rootState = status.value.rootState
        val ownership = pairingOwnership.value
        return ownership.attempt == null &&
            ownership.cleanup == null &&
            ownership.authorityEpoch != null &&
            rootState != CompanionRootState.Unpaired &&
            rootState != CompanionRootState.Revoked
    }

    private fun clearPendingPairing() {
        while (true) {
            val currentOwnership = pairingOwnership.value
            if (
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withAttempt(attempt = null),
                )
            ) {
                return
            }
        }
    }

    private fun completePairingRegistration() {
        clearPendingPairing()
    }

    private fun clearPendingPairing(token: Any): Boolean {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val current = currentOwnership.attempt ?: return false
            if (current.token !== token) return false
            if (
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withAttempt(attempt = null),
                )
            ) {
                return true
            }
        }
    }

    private fun clearPendingPairingAttempt(): Boolean {
        while (true) {
            val currentOwnership = pairingOwnership.value
            val current = currentOwnership.attempt ?: return false
            if (current.durableRegistration || currentOwnership.cleanup?.token === current.token) {
                return false
            }
            if (
                pairingOwnership.compareAndSet(
                    expect = currentOwnership,
                    update = currentOwnership.withAttempt(attempt = null),
                )
            ) {
                return true
            }
        }
    }

    private fun rejectedPairingAcceptance(): CompanionTransitionOutcome =
        CompanionTransitionOutcome.Rejected(
            status = status.value,
            eventCode = CompanionTransitionEvent.ACCEPT_PAIRING_QR.code,
        )

    internal fun transitionAuthorizationEvent(event: CompanionTransitionEvent): CompanionTransitionOutcome =
        when (event) {
            CompanionTransitionEvent.BACKGROUND_OR_SYSTEM_LOCK -> {
                coordinator.transition(
                    if (clearPendingPairingAttempt()) {
                        CompanionTransitionEvent.LOCAL_UNPAIR
                    } else {
                        event
                    },
                )
            }

            CompanionTransitionEvent.LOCAL_UNPAIR -> {
                completePairingRegistration()
                coordinator.transition(event)
            }

            else -> {
                coordinator.transition(event)
            }
        }

    private class PendingPairingAttempt(
        val pairingQr: PairingQr?,
        val token: Any = Any(),
        val durableRegistration: Boolean = false,
    )

    private class PairingOwnershipState(
        val attempt: PendingPairingAttempt?,
        val cleanup: PairingCleanupOwnership?,
        val authorityEpoch: Any?,
    ) {
        fun withAttempt(attempt: PendingPairingAttempt?): PairingOwnershipState =
            PairingOwnershipState(attempt, cleanup, authorityEpoch)

        fun withCleanup(cleanup: PairingCleanupOwnership?): PairingOwnershipState =
            PairingOwnershipState(attempt, cleanup, authorityEpoch)

        fun withAuthorityEpoch(authorityEpoch: Any?): PairingOwnershipState =
            PairingOwnershipState(attempt, cleanup, authorityEpoch)
    }

    private class PairingCleanupOwnership(
        val token: Any,
        val releaseCapability: Any,
        val authorizationOwned: Boolean,
    )
}

internal class PendingPairingLease(
    internal val pairingQr: PairingQr,
    internal val token: Any,
) {
    override fun toString(): String = "PendingPairingLease(redacted)"
}

internal class PairingCleanupHandle(
    internal val token: Any,
    internal val releaseCapability: Any,
) {
    override fun toString(): String = "PairingCleanupHandle(redacted)"
}
