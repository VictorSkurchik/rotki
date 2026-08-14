package org.rotki.mobile.android.storage

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.PairingCleanupJournal
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Atomic, backup-excluded, secret-free journal for unfinished Pairing cleanup. */
internal class AndroidPairingCleanupJournal private constructor(
    private val file: AtomicFile,
) : PairingCleanupJournal {
    private val operationMutex: Mutex = Mutex()

    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, FILE_NAME)),
    )

    override suspend fun read(): PairingCleanupJournalReadOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) { readUnlocked() }
        }

    // AtomicFile must roll back its temporary write before propagating any unexpected runtime failure.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun markCleanupRequired(): PairingCleanupJournalWriteOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val output =
                    try {
                        file.startWrite()
                    } catch (_: IOException) {
                        return@withContext PairingCleanupJournalWriteOutcome.Unavailable
                    } catch (_: SecurityException) {
                        return@withContext PairingCleanupJournalWriteOutcome.Unavailable
                    }
                try {
                    output.write(MARKER)
                    file.finishWrite(output)
                } catch (_: IOException) {
                    file.failWrite(output)
                    return@withContext PairingCleanupJournalWriteOutcome.Unavailable
                } catch (_: SecurityException) {
                    file.failWrite(output)
                    return@withContext PairingCleanupJournalWriteOutcome.Unavailable
                } catch (error: RuntimeException) {
                    file.failWrite(output)
                    throw error
                }
                if (readUnlocked() == PairingCleanupJournalReadOutcome.CleanupRequired) {
                    PairingCleanupJournalWriteOutcome.Stored
                } else {
                    PairingCleanupJournalWriteOutcome.Unavailable
                }
            }
        }

    override suspend fun clear(): PairingCleanupJournalClearOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    file.delete()
                } catch (_: SecurityException) {
                    return@withContext PairingCleanupJournalClearOutcome.Unavailable
                }
                if (readUnlocked() == PairingCleanupJournalReadOutcome.Clear) {
                    PairingCleanupJournalClearOutcome.Cleared
                } else {
                    PairingCleanupJournalClearOutcome.Unavailable
                }
            }
        }

    private fun readUnlocked(): PairingCleanupJournalReadOutcome {
        try {
            readBounded()
        } catch (_: FileNotFoundException) {
            return PairingCleanupJournalReadOutcome.Clear
        } catch (_: IOException) {
            return PairingCleanupJournalReadOutcome.Unavailable
        } catch (_: SecurityException) {
            return PairingCleanupJournalReadOutcome.Unavailable
        }
        // Any file content is fail-closed. The canonical marker remains constant and secret-free.
        return PairingCleanupJournalReadOutcome.CleanupRequired
    }

    private fun readBounded(): ByteArray? =
        file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                if (output.size() > MAX_MARKER_BYTES - count) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    internal fun baseFileForTest(): File = file.baseFile

    private companion object {
        const val FILE_NAME: String = "pairing-cleanup.marker"
        const val MAX_MARKER_BYTES: Int = 128
        val MARKER: ByteArray = "rotki-pairing-cleanup-v1\n".encodeToByteArray()
    }
}
