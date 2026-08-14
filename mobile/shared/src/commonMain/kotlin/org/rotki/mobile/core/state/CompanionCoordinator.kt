package org.rotki.mobile.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CompanionCoordinator(
    initialStatus: CompanionStatus =
        CompanionStatus(
            rootState = CompanionRootState.Unpaired,
            snapshotCoverage = SnapshotCoverage.Absent,
        ),
) {
    private val mutableStatus: MutableStateFlow<CompanionStatus> = MutableStateFlow(initialStatus)

    internal val status: StateFlow<CompanionStatus> = mutableStatus.asStateFlow()

    init {
        requireValidStatus(initialStatus)
    }

    internal fun transition(event: CompanionTransitionEvent): CompanionTransitionOutcome {
        while (true) {
            val previous: CompanionStatus = mutableStatus.value
            val rule: TransitionRule = ruleFor(event)
            if (previous.rootState !in rule.from) {
                return CompanionTransitionOutcome.Rejected(
                    status = previous,
                    eventCode = event.code,
                )
            }

            val next: CompanionStatus =
                CompanionStatus(
                    rootState = rule.to,
                    snapshotCoverage = rule.coverage(previous.snapshotCoverage),
                )
            requireValidStatus(next)
            if (mutableStatus.compareAndSet(previous, next)) {
                return CompanionTransitionOutcome.Applied(
                    status = next,
                    effects = rule.effects,
                )
            }
        }
    }

    private fun ruleFor(event: CompanionTransitionEvent): TransitionRule =
        when (event) {
            CompanionTransitionEvent.START_WITHOUT_PAIRING -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Unpaired),
                    to = CompanionRootState.Unpaired,
                    effects = effects("absent", "absent", "absent"),
                    coverage = { SnapshotCoverage.Absent },
                )
            }

            CompanionTransitionEvent.ACCEPT_PAIRING_QR -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Unpaired, CompanionRootState.Revoked),
                    to = CompanionRootState.Connecting,
                    effects = effects("replace", "delete", "absent"),
                    coverage = { SnapshotCoverage.Absent },
                )
            }

            CompanionTransitionEvent.BACKGROUND_OR_SYSTEM_LOCK -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Connecting,
                            CompanionRootState.Degraded,
                            CompanionRootState.DeviceLocked,
                            CompanionRootState.EngineLocked,
                            CompanionRootState.Incompatible,
                            CompanionRootState.Online,
                            CompanionRootState.ProfileMismatch,
                            CompanionRootState.Refreshing,
                            CompanionRootState.Unreachable,
                        ),
                    to = CompanionRootState.DeviceLocked,
                    effects = effects("keep", "keep_encrypted", "delete"),
                )
            }

            CompanionTransitionEvent.DEVICE_AUTHENTICATION_SUCCEEDED -> {
                TransitionRule(
                    from = setOf(CompanionRootState.DeviceLocked),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "unlock", "absent"),
                )
            }

            CompanionTransitionEvent.PROOF_AND_COMPLETE_SNAPSHOT_RECONCILED -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.Online,
                    effects = effects("keep", "replace", "replace"),
                    coverage = { SnapshotCoverage.Complete },
                )
            }

            CompanionTransitionEvent.PROOF_AND_DEGRADED_SNAPSHOT_RECONCILED -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.Degraded,
                    effects = effects("keep", "replace", "replace"),
                    coverage = { SnapshotCoverage.Degraded },
                )
            }

            CompanionTransitionEvent.ACTIVE_REFRESH_RECONCILED -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Degraded,
                            CompanionRootState.Online,
                        ),
                    to = CompanionRootState.Refreshing,
                    effects = effects("keep", "keep", "keep"),
                )
            }

            CompanionTransitionEvent.PROOF_AND_ACTIVE_REFRESH_RECONCILED -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.Refreshing,
                    effects = effects("keep", "keep", "replace"),
                )
            }

            CompanionTransitionEvent.TERMINAL_REFRESH_AND_COMPLETE_SNAPSHOT -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Refreshing),
                    to = CompanionRootState.Online,
                    effects = effects("keep", "replace", "keep"),
                    coverage = { SnapshotCoverage.Complete },
                )
            }

            CompanionTransitionEvent.TERMINAL_REFRESH_AND_DEGRADED_SNAPSHOT -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Refreshing),
                    to = CompanionRootState.Degraded,
                    effects = effects("keep", "replace", "keep"),
                    coverage = { SnapshotCoverage.Degraded },
                )
            }

            CompanionTransitionEvent.TRANSPORT_RETRY_BUDGET_EXHAUSTED -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Connecting,
                            CompanionRootState.Degraded,
                            CompanionRootState.Online,
                            CompanionRootState.Refreshing,
                        ),
                    to = CompanionRootState.Unreachable,
                    effects = effects("keep", "keep", "keep_until_expiry"),
                )
            }

            CompanionTransitionEvent.TRANSPORT_RESTORED -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Unreachable),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "keep", "keep_until_expiry"),
                )
            }

            CompanionTransitionEvent.ACCESS_SESSION_UNAVAILABLE -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Connecting,
                            CompanionRootState.Degraded,
                            CompanionRootState.Online,
                            CompanionRootState.Refreshing,
                        ),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "keep", "delete"),
                )
            }

            CompanionTransitionEvent.PROACTIVE_RENEWAL_STARTED -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Degraded,
                            CompanionRootState.Online,
                            CompanionRootState.Refreshing,
                        ),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "keep", "keep_until_expiry"),
                )
            }

            CompanionTransitionEvent.WEBSOCKET_CLOSE_1008 -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Connecting,
                            CompanionRootState.Degraded,
                            CompanionRootState.Online,
                            CompanionRootState.Refreshing,
                        ),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "keep", "delete"),
                )
            }

            CompanionTransitionEvent.LOCKED_ENGINE -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.EngineLocked,
                    effects = effects("keep", "keep", "delete"),
                )
            }

            CompanionTransitionEvent.PROFILE_MISMATCH -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.ProfileMismatch,
                    effects = effects("keep", "keep", "delete"),
                )
            }

            CompanionTransitionEvent.NO_SHARED_PROTOCOL_OR_DEVICE_SESSIONS_CAPABILITY -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.Incompatible,
                    effects = effects("keep", "keep", "delete"),
                )
            }

            CompanionTransitionEvent.NOT_AUTHORIZED -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Connecting,
                            CompanionRootState.Degraded,
                            CompanionRootState.Online,
                            CompanionRootState.Refreshing,
                            CompanionRootState.Unreachable,
                        ),
                    to = CompanionRootState.Revoked,
                    effects = effects("delete", "delete", "delete"),
                    coverage = { SnapshotCoverage.Absent },
                )
            }

            CompanionTransitionEvent.CHALLENGE_UNAVAILABLE -> {
                TransitionRule(
                    from = setOf(CompanionRootState.Connecting),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "keep", "keep_until_expiry"),
                )
            }

            CompanionTransitionEvent.EXPLICIT_FOREGROUND_RETRY -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.EngineLocked,
                            CompanionRootState.Incompatible,
                            CompanionRootState.ProfileMismatch,
                        ),
                    to = CompanionRootState.Connecting,
                    effects = effects("keep", "keep", "absent"),
                )
            }

            CompanionTransitionEvent.LOCAL_UNPAIR -> {
                TransitionRule(
                    from =
                        setOf(
                            CompanionRootState.Connecting,
                            CompanionRootState.Degraded,
                            CompanionRootState.DeviceLocked,
                            CompanionRootState.EngineLocked,
                            CompanionRootState.Incompatible,
                            CompanionRootState.Online,
                            CompanionRootState.ProfileMismatch,
                            CompanionRootState.Refreshing,
                            CompanionRootState.Revoked,
                            CompanionRootState.Unreachable,
                        ),
                    to = CompanionRootState.Unpaired,
                    effects = effects("delete", "delete", "delete"),
                    coverage = { SnapshotCoverage.Absent },
                )
            }
        }

    private fun requireValidStatus(status: CompanionStatus) {
        when (status.rootState) {
            CompanionRootState.Online -> require(status.snapshotCoverage == SnapshotCoverage.Complete)

            CompanionRootState.Degraded -> require(status.snapshotCoverage == SnapshotCoverage.Degraded)

            CompanionRootState.Revoked,
            CompanionRootState.Unpaired,
            -> require(status.snapshotCoverage == SnapshotCoverage.Absent)

            else -> Unit
        }
    }

    private data class TransitionRule(
        val from: Set<CompanionRootState>,
        val to: CompanionRootState,
        val effects: CompanionTransitionEffects,
        val coverage: (SnapshotCoverage) -> SnapshotCoverage = { previous -> previous },
    )
}

private fun effects(
    deviceSession: String,
    snapshot: String,
    bearer: String,
): CompanionTransitionEffects =
    CompanionTransitionEffects(
        deviceSession = DeviceSessionEffect.entries.single { effect -> effect.code == deviceSession },
        snapshot = SnapshotEffect.entries.single { effect -> effect.code == snapshot },
        bearer = BearerEffect.entries.single { effect -> effect.code == bearer },
    )
