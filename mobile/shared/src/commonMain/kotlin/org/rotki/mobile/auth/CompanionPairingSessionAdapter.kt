package org.rotki.mobile.auth

import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.PairingCleanupHandle
import org.rotki.mobile.PendingPairingLease
import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionTransitionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingAdmission
import org.rotki.mobile.feature.pairing.domain.PairingAttemptPort
import org.rotki.mobile.feature.pairing.domain.PairingSessionPort

/** Keeps Pairing feature code behind a facade-scoped, non-exported domain boundary. */
internal class CompanionPairingSessionAdapter(
    private val facade: CompanionFacade,
) : PairingSessionPort<PairingQr>,
    PairingAttemptPort<PendingPairingLease, PairingCleanupHandle> {
    override fun isConnecting(): Boolean = facade.status.value.rootState == CompanionRootState.Connecting

    override fun isUnpaired(): Boolean = facade.status.value.rootState == CompanionRootState.Unpaired

    override fun admit(material: PairingQr): PairingAdmission =
        if (facade.acceptPairing(material) is CompanionTransitionOutcome.Applied) {
            PairingAdmission.ACCEPTED
        } else {
            PairingAdmission.IGNORED
        }

    override suspend fun <T> withConnectionOwnership(operation: suspend () -> T): T =
        facade.withPairingConnectionOwnership(operation)

    override fun hasPendingCleanup(): Boolean = facade.hasPendingPairingCleanup()

    override fun takePending(): PendingPairingLease? = facade.takePendingPairingForConnection()

    override fun isCurrent(attempt: PendingPairingLease): Boolean = facade.isPendingPairing(attempt)

    override suspend fun awaitLoss(attempt: PendingPairingLease) {
        facade.awaitPendingPairingLoss(attempt)
    }

    override fun markCleanupRequired(attempt: PendingPairingLease): PairingCleanupHandle? =
        facade.markPairingCleanupRequired(attempt)

    override fun markDurable(attempt: PendingPairingLease): Boolean = facade.markPendingPairingDurable(attempt)

    override fun commit(attempt: PendingPairingLease): Boolean = facade.commitPendingPairing(attempt)

    override fun abort(attempt: PendingPairingLease): Boolean = facade.abortPendingPairing(attempt)

    override fun completeCleanup(cleanup: PairingCleanupHandle): Boolean = facade.completePendingPairingCleanup(cleanup)

    override fun abandonCleanup(cleanup: PairingCleanupHandle): Boolean = facade.abandonPendingPairingCleanup(cleanup)

    override fun claimRecoveredCleanup(): PairingCleanupHandle? = facade.claimRecoveredPairingCleanup()

    override fun toString(): String = "CompanionPairingSessionAdapter(redacted)"
}
