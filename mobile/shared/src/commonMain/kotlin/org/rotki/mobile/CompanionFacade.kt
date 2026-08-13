package org.rotki.mobile

import kotlinx.coroutines.flow.StateFlow
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

    public fun beginPairing(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.ACCEPT_PAIRING_QR)

    public fun lock(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.BACKGROUND_OR_SYSTEM_LOCK)

    public fun deviceAuthenticationSucceeded(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.DEVICE_AUTHENTICATION_SUCCEEDED)

    public fun completeConnectionWithCompleteSnapshot(): CompanionTransitionOutcome =
        coordinator.transition(
            CompanionTransitionEvent.PROOF_AND_COMPLETE_SNAPSHOT_RECONCILED,
        )

    public fun completeConnectionWithDegradedSnapshot(): CompanionTransitionOutcome =
        coordinator.transition(
            CompanionTransitionEvent.PROOF_AND_DEGRADED_SNAPSHOT_RECONCILED,
        )

    public fun activeRefreshReconciled(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.ACTIVE_REFRESH_RECONCILED)

    public fun completeConnectionWithActiveRefresh(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.PROOF_AND_ACTIVE_REFRESH_RECONCILED)

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

    public fun notAuthorized(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.NOT_AUTHORIZED)

    public fun challengeUnavailable(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.CHALLENGE_UNAVAILABLE)

    public fun retryResolvedEngineState(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.EXPLICIT_FOREGROUND_RETRY)

    public fun unpair(): CompanionTransitionOutcome =
        coordinator.transition(CompanionTransitionEvent.LOCAL_UNPAIR)
}
