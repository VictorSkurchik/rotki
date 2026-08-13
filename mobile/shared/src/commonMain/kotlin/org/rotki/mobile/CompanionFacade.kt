package org.rotki.mobile

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

    public val status: StateFlow<CompanionStatus> = coordinator.status

    public companion object {
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

    internal fun acceptPairing(pairingQr: PairingQr): CompanionTransitionOutcome {
        val attempt = PendingPairingAttempt(pairingQr)
        if (!pendingPairingAttempt.compareAndSet(expect = null, update = attempt)) {
            return CompanionTransitionOutcome.Rejected(
                status = status.value,
                eventCode = CompanionTransitionEvent.ACCEPT_PAIRING_QR.code,
            )
        }
        val outcome = beginPairing()
        if (outcome !is CompanionTransitionOutcome.Applied) {
            clearPendingPairing(attempt.token)
        }
        return outcome
    }

    /** One-shot internal hand-off for shared registration; never exported to native UI. */
    internal fun takePendingPairingForConnection(): PairingQr? {
        while (true) {
            val current = pendingPairingAttempt.value ?: return null
            val pairingQr = current.pairingQr ?: return null
            val consumed = PendingPairingAttempt(
                pairingQr = null,
                token = current.token,
            )
            if (pendingPairingAttempt.compareAndSet(expect = current, update = consumed)) {
                return pairingQr
            }
        }
    }

    public fun pairingFlow(): PairingFlow = PairingFlow(this)

    public fun pairingFlow(clock: Clock): PairingFlow = PairingFlow(this, clock)

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

    private fun clearPendingPairing(): Unit {
        pendingPairingAttempt.value = null
    }

    private fun completePairingRegistration(): Unit {
        clearPendingPairing()
    }

    private fun clearPendingPairing(token: Any): Unit {
        while (true) {
            val current = pendingPairingAttempt.value ?: return
            if (current.token !== token) return
            if (pendingPairingAttempt.compareAndSet(expect = current, update = null)) return
        }
    }

    private fun clearPendingPairingAttempt(): Boolean {
        while (true) {
            val current = pendingPairingAttempt.value ?: return false
            if (pendingPairingAttempt.compareAndSet(expect = current, update = null)) return true
        }
    }

    private class PendingPairingAttempt(
        val pairingQr: PairingQr?,
        val token: Any = Any(),
    )
}
