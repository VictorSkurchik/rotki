package org.rotki.mobile.android.security

import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.PairingCleanupJournal
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordStore

internal interface LocalMaterialCleaner {
    suspend fun destroyAll(): Boolean
}

/** Best-effort cleanup that attempts every independent local-material deletion. */
internal class AndroidLocalMaterialCleaner(
    private val snapshotFile: AtomicSnapshotFile,
    private val snapshotKeyStore: SnapshotKeyStore,
    private val pairingRecordStore: PairingRecordStore,
    private val deviceProofSigner: DeviceProofSigner,
    private val pairingCleanupJournal: PairingCleanupJournal,
) : LocalMaterialCleaner {
    private val cleanupMutex: Mutex = Mutex()

    override suspend fun destroyAll(): Boolean = cleanupMutex.withLock {
        withContext(NonCancellable) {
            val snapshotFileDeleted = bestEffortSync { snapshotFile.delete() } ?: false
            val snapshotKeyDeleted = bestEffortSync { snapshotKeyStore.delete() } ?: false
            val cleanupJournalStored =
                bestEffortSuspend { pairingCleanupJournal.markCleanupRequired() } ==
                    PairingCleanupJournalWriteOutcome.Stored
            val pairingRecordDeleted = cleanupJournalStored &&
                bestEffortSuspend { pairingRecordStore.delete() } ==
                PairingRecordDeleteOutcome.Deleted
            val signingKeyDeleted = cleanupJournalStored &&
                bestEffortSuspend { deviceProofSigner.deleteKey() } ==
                DeviceProofKeyDeleteOutcome.Deleted
            val cleanupJournalCleared = if (pairingRecordDeleted && signingKeyDeleted) {
                bestEffortSuspend { pairingCleanupJournal.clear() } ==
                    PairingCleanupJournalClearOutcome.Cleared
            } else {
                false
            }
            snapshotFileDeleted &&
                snapshotKeyDeleted &&
                pairingRecordDeleted &&
                signingKeyDeleted &&
                cleanupJournalCleared
        }
    }

    private inline fun <T> bestEffortSync(operation: () -> T): T? = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend inline fun <T> bestEffortSuspend(
        crossinline operation: suspend () -> T,
    ): T? = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
