package org.rotki.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.rotki.mobile.auth.CompanionPairingSessionAdapter
import org.rotki.mobile.auth.PairingConnection
import org.rotki.mobile.auth.PairingConnectionConfiguration
import org.rotki.mobile.auth.PairingFlow
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
) {
    public constructor() : this(
        CompanionStatus(
            rootState = CompanionRootState.Unpaired,
            snapshotCoverage = SnapshotCoverage.Absent,
        ),
    )

    private val coordinator: CompanionCoordinator = CompanionCoordinator(initialStatus)
    private val pendingPairingAttempt: MutableStateFlow<PendingPairingAttempt?> =
        MutableStateFlow(null)
    private val pendingPairingCleanup: MutableStateFlow<Any?> = MutableStateFlow(null)
    private val pairingConnectionMutex: Mutex = Mutex()
    private val pairingSessionAdapter: CompanionPairingSessionAdapter =
        CompanionPairingSessionAdapter(this)

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
    ): CompanionTransitionOutcome {
        if (pendingPairingCleanup.value != null) {
            return rejectedPairingAcceptance()
        }
        val attempt = PendingPairingAttempt(pairingQr)
        if (!pendingPairingAttempt.compareAndSet(expect = null, update = attempt)) {
            return rejectedPairingAcceptance()
        }
        afterPendingStored()
        if (pendingPairingCleanup.value != null) {
            clearPendingPairing(attempt.token)
            return rejectedPairingAcceptance()
        }
        val outcome = beginPairing()
        if (outcome !is CompanionTransitionOutcome.Applied) {
            clearPendingPairing(attempt.token)
            return outcome
        }
        beforeFinalOwnershipCheck()
        if (pendingPairingCleanup.value != null ||
            pendingPairingAttempt.value?.token !== attempt.token
        ) {
            clearPendingPairing(attempt.token)
            coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
            return rejectedPairingAcceptance()
        }
        return outcome
    }

    /** One-shot internal hand-off for shared registration; never exported to native UI. */
    internal fun takePendingPairingForConnection(): PendingPairingLease? {
        if (pendingPairingCleanup.value != null) return null
        while (true) {
            val current = pendingPairingAttempt.value ?: return null
            val pairingQr = current.pairingQr ?: return null
            val consumed =
                PendingPairingAttempt(
                    pairingQr = null,
                    token = current.token,
                    durableRegistration = current.durableRegistration,
                )
            if (pendingPairingAttempt.compareAndSet(expect = current, update = consumed)) {
                return PendingPairingLease(pairingQr, current.token)
            }
        }
    }

    internal fun isPendingPairing(lease: PendingPairingLease): Boolean =
        pendingPairingAttempt.value?.token === lease.token

    internal suspend fun awaitPendingPairingLoss(lease: PendingPairingLease) {
        pendingPairingAttempt.first { attempt -> attempt?.token !== lease.token }
    }

    /** Marks a persisted registration as the durable Pairing relationship. */
    internal fun markPendingPairingDurable(lease: PendingPairingLease): Boolean {
        while (true) {
            val current = pendingPairingAttempt.value ?: return false
            if (current.token !== lease.token || current.pairingQr != null) return false
            if (current.durableRegistration) return true
            if (status.value.rootState != CompanionRootState.Connecting) return false
            val durable =
                PendingPairingAttempt(
                    pairingQr = null,
                    token = current.token,
                    durableRegistration = true,
                )
            if (pendingPairingAttempt.compareAndSet(expect = current, update = durable)) {
                return status.value.rootState in COMMITTABLE_PAIRING_STATES
            }
        }
    }

    /** Clears only an attempt whose registration is already durable and locally recoverable. */
    internal fun commitPendingPairing(lease: PendingPairingLease): Boolean {
        while (true) {
            val current = pendingPairingAttempt.value ?: return false
            if (current.token !== lease.token || !current.durableRegistration) return false
            if (status.value.rootState !in COMMITTABLE_PAIRING_STATES) return false
            if (pendingPairingAttempt.compareAndSet(expect = current, update = null)) {
                if (status.value.rootState !in COMMITTABLE_PAIRING_STATES) return false
                clearPairingCleanup(current.token)
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
        if (!isPendingPairing(lease)) return null
        val existing = pendingPairingCleanup.value
        if (existing != null && existing !== lease.token) return null
        if (existing == null &&
            !pendingPairingCleanup.compareAndSet(expect = null, update = lease.token)
        ) {
            return markPairingCleanupRequired(lease)
        }
        if (!isPendingPairing(lease)) {
            clearPairingCleanup(lease.token)
            return null
        }
        return PairingCleanupHandle(lease.token)
    }

    internal fun hasPendingPairingCleanup(): Boolean = pendingPairingCleanup.value != null

    internal fun completePendingPairingCleanup(handle: PairingCleanupHandle): Boolean {
        if (pendingPairingCleanup.value !== handle.token) return false
        clearPendingPairing(handle.token)
        coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
        return clearPairingCleanup(handle.token)
    }

    internal fun abandonPendingPairingCleanup(handle: PairingCleanupHandle): Boolean {
        if (pendingPairingCleanup.value !== handle.token) return false
        if (clearPendingPairing(handle.token)) {
            coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
        }
        return clearPairingCleanup(handle.token)
    }

    /** Releases a facade-scoped destructive-cleanup barrier without changing terminal root state. */
    internal fun completeAuthorizationCleanup(handle: PairingCleanupHandle): Boolean = clearPairingCleanup(handle.token)

    /** Claims the cleanup barrier before an explicit unpair clears any pending attempt. */
    internal fun claimLocalUnpairCleanup(): PairingCleanupHandle {
        while (true) {
            pendingPairingCleanup.value?.let { token ->
                clearPendingPairing()
                coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
                return PairingCleanupHandle(token)
            }
            val token = Any()
            if (pendingPairingCleanup.compareAndSet(expect = null, update = token)) {
                clearPendingPairing()
                coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
                return PairingCleanupHandle(token)
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
    internal fun claimRecoveredPairingCleanup(): PairingCleanupHandle? =
        claimRecoveredPairingCleanup(afterPendingObservedAbsent = {})

    internal fun claimRecoveredPairingCleanup(afterPendingObservedAbsent: () -> Unit): PairingCleanupHandle? {
        while (true) {
            pendingPairingCleanup.value?.let { token ->
                return PairingCleanupHandle(token)
            }
            if (pendingPairingAttempt.value != null) return null
            afterPendingObservedAbsent()
            val token = Any()
            if (pendingPairingCleanup.compareAndSet(expect = null, update = token)) {
                if (pendingPairingAttempt.value == null) {
                    return PairingCleanupHandle(token)
                }
                clearPairingCleanup(token)
                return null
            }
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

    public fun pairingConnection(configuration: PairingConnectionConfiguration): PairingConnection =
        PairingConnection.create(pairingSessionAdapter, configuration)

    public fun lock(): CompanionTransitionOutcome {
        val cancelledUnregisteredPairing = clearPendingPairingAttempt()
        return coordinator.transition(
            if (cancelledUnregisteredPairing) {
                CompanionTransitionEvent.LOCAL_UNPAIR
            } else {
                CompanionTransitionEvent.BACKGROUND_OR_SYSTEM_LOCK
            },
        )
    }

    public fun deviceAuthenticationSucceeded(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.DEVICE_AUTHENTICATION_SUCCEEDED)

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
        coordinator.transition(CompanionTransitionEvent.TRANSPORT_RESTORED)

    public fun accessSessionUnavailable(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.ACCESS_SESSION_UNAVAILABLE)

    public fun proactiveRenewalStarted(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.PROACTIVE_RENEWAL_STARTED)

    public fun webSocketPolicyClosed(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.WEBSOCKET_CLOSE_1008)

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
        coordinator.transition(CompanionTransitionEvent.EXPLICIT_FOREGROUND_RETRY)

    public fun unpair(): CompanionTransitionOutcome {
        completePairingRegistration()
        return coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
    }

    private fun clearPendingPairing() {
        pendingPairingAttempt.value = null
    }

    private fun completePairingRegistration() {
        clearPendingPairing()
    }

    private fun clearPendingPairing(token: Any): Boolean {
        while (true) {
            val current = pendingPairingAttempt.value ?: return false
            if (current.token !== token) return false
            if (pendingPairingAttempt.compareAndSet(expect = current, update = null)) return true
        }
    }

    private fun clearPendingPairingAttempt(): Boolean {
        while (true) {
            val current = pendingPairingAttempt.value ?: return false
            if (current.durableRegistration || pendingPairingCleanup.value === current.token) {
                return false
            }
            if (pendingPairingAttempt.compareAndSet(expect = current, update = null)) return true
        }
    }

    private fun clearPairingCleanup(token: Any): Boolean =
        pendingPairingCleanup.compareAndSet(expect = token, update = null)

    private fun rejectedPairingAcceptance(): CompanionTransitionOutcome =
        CompanionTransitionOutcome.Rejected(
            status = status.value,
            eventCode = CompanionTransitionEvent.ACCEPT_PAIRING_QR.code,
        )

    private class PendingPairingAttempt(
        val pairingQr: PairingQr?,
        val token: Any = Any(),
        val durableRegistration: Boolean = false,
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
) {
    override fun toString(): String = "PairingCleanupHandle(redacted)"
}
