package org.rotki.mobile.android.pairing

import kotlinx.coroutines.CancellationException
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.PairingConnectionOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome

internal enum class AndroidPairingStartupState {
    PAIRED,
    UNPAIRED,
    CLEANUP_REQUIRED,
    FAIL_CLOSED,
}

/** Checks Pairing record, key, and durable cleanup intent before choosing the root state. */
internal class AndroidPairingStartupReconciler(
    private val readJournal: suspend () -> PairingCleanupJournalReadOutcome,
    private val readRecord: suspend () -> PairingRecordReadOutcome,
    private val readCurrentKey: suspend () -> DeviceProofPublicKeyOutcome,
    private val markCleanupRequired: suspend () -> PairingCleanupJournalWriteOutcome,
) {
    suspend fun reconcile(): AndroidPairingStartupState =
        when (safeReadJournal()) {
            PairingCleanupJournalReadOutcome.CleanupRequired -> {
                AndroidPairingStartupState.CLEANUP_REQUIRED
            }

            PairingCleanupJournalReadOutcome.Unavailable -> {
                AndroidPairingStartupState.FAIL_CLOSED
            }

            PairingCleanupJournalReadOutcome.Clear -> {
                reconcileMaterial()
            }
        }

    private suspend fun reconcileMaterial(): AndroidPairingStartupState {
        val record = safeReadRecord()
        val key = safeReadCurrentKey()
        return when (record) {
            is PairingRecordReadOutcome.Present -> {
                when (key) {
                    is DeviceProofPublicKeyOutcome.PublicKey -> {
                        AndroidPairingStartupState.PAIRED
                    }

                    DeviceProofPublicKeyOutcome.PairingRequired -> {
                        markCleanup()
                    }

                    DeviceProofPublicKeyOutcome.UnexpectedFailure -> {
                        AndroidPairingStartupState.FAIL_CLOSED
                    }
                }
            }

            PairingRecordReadOutcome.Missing -> {
                when (key) {
                    is DeviceProofPublicKeyOutcome.PublicKey -> {
                        markCleanup()
                    }

                    DeviceProofPublicKeyOutcome.PairingRequired -> {
                        AndroidPairingStartupState.UNPAIRED
                    }

                    DeviceProofPublicKeyOutcome.UnexpectedFailure -> {
                        AndroidPairingStartupState.FAIL_CLOSED
                    }
                }
            }

            PairingRecordReadOutcome.Corrupt -> {
                when (key) {
                    is DeviceProofPublicKeyOutcome.PublicKey,
                    DeviceProofPublicKeyOutcome.PairingRequired,
                    -> {
                        markCleanup()
                    }

                    DeviceProofPublicKeyOutcome.UnexpectedFailure -> {
                        AndroidPairingStartupState.FAIL_CLOSED
                    }
                }
            }

            PairingRecordReadOutcome.Unavailable -> {
                AndroidPairingStartupState.FAIL_CLOSED
            }
        }
    }

    private suspend fun markCleanup(): AndroidPairingStartupState =
        when (safeMarkCleanupRequired()) {
            PairingCleanupJournalWriteOutcome.Stored -> {
                AndroidPairingStartupState.CLEANUP_REQUIRED
            }

            PairingCleanupJournalWriteOutcome.Unavailable -> {
                AndroidPairingStartupState.FAIL_CLOSED
            }
        }

    private suspend fun safeReadJournal(): PairingCleanupJournalReadOutcome =
        try {
            readJournal()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PairingCleanupJournalReadOutcome.Unavailable
        }

    private suspend fun safeReadRecord(): PairingRecordReadOutcome =
        try {
            readRecord()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PairingRecordReadOutcome.Unavailable
        }

    private suspend fun safeReadCurrentKey(): DeviceProofPublicKeyOutcome =
        try {
            readCurrentKey()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DeviceProofPublicKeyOutcome.UnexpectedFailure
        }

    private suspend fun safeMarkCleanupRequired(): PairingCleanupJournalWriteOutcome =
        try {
            markCleanupRequired()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PairingCleanupJournalWriteOutcome.Unavailable
        }
}

/** Unknown startup authority is represented by DeviceLocked until reconciliation succeeds. */
internal fun AndroidPairingStartupState.createStartupFacade(): CompanionFacade =
    when (this) {
        AndroidPairingStartupState.PAIRED,
        AndroidPairingStartupState.FAIL_CLOSED,
        -> CompanionFacade.restorePaired(org.rotki.mobile.core.state.SnapshotCoverage.Absent)

        AndroidPairingStartupState.UNPAIRED,
        AndroidPairingStartupState.CLEANUP_REQUIRED,
        -> CompanionFacade()
    }

internal suspend fun AndroidPairingStartupState.applyRetryTo(
    facade: CompanionFacade,
    retryCleanup: suspend () -> PairingConnectionOutcome,
): PairingConnectionOutcome =
    when (this) {
        AndroidPairingStartupState.CLEANUP_REQUIRED -> {
            retryCleanup()
        }

        AndroidPairingStartupState.UNPAIRED -> {
            facade.unpair()
            PairingConnectionOutcome.NO_PENDING_PAIRING
        }

        AndroidPairingStartupState.PAIRED -> {
            PairingConnectionOutcome.NO_PENDING_PAIRING
        }

        AndroidPairingStartupState.FAIL_CLOSED -> {
            PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
        }
    }
