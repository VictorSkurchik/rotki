package org.rotki.mobile.android.security

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal sealed interface AtomicSnapshotReadOutcome {
    data object Missing : AtomicSnapshotReadOutcome

    class Present(bytes: ByteArray) : AtomicSnapshotReadOutcome {
        private val storedBytes: ByteArray = bytes.copyOf()

        fun bytesCopy(): ByteArray = storedBytes.copyOf()

        override fun toString(): String = "Present(redacted)"
    }

    data object Corrupt : AtomicSnapshotReadOutcome

    data object Unavailable : AtomicSnapshotReadOutcome
}

/** Atomic last-good replacement in Android's backup-excluded application directory. */
internal class AtomicSnapshotFile private constructor(
    private val file: AtomicFile,
) {
    private val operationLock: Any = Any()

    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, FILE_NAME)),
    )

    fun read(): AtomicSnapshotReadOutcome = synchronized(operationLock) {
        val encoded = try {
            file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    val remainingCapacity =
                        SnapshotEnvelopeCodec.DEFENSIVE_MAX_ENCODED_BYTES - output.size()
                    if (count > remainingCapacity) {
                        return@synchronized AtomicSnapshotReadOutcome.Corrupt
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            return@synchronized AtomicSnapshotReadOutcome.Missing
        } catch (_: IOException) {
            return@synchronized AtomicSnapshotReadOutcome.Unavailable
        } catch (_: SecurityException) {
            return@synchronized AtomicSnapshotReadOutcome.Unavailable
        }
        AtomicSnapshotReadOutcome.Present(encoded)
    }

    fun replace(encoded: ByteArray): Boolean = synchronized(operationLock) {
        require(encoded.size <= SnapshotEnvelopeCodec.DEFENSIVE_MAX_ENCODED_BYTES) {
            "Snapshot envelope exceeds the supported bound"
        }
        val output = try {
            file.startWrite()
        } catch (_: IOException) {
            return@synchronized false
        } catch (_: SecurityException) {
            return@synchronized false
        }
        return try {
            output.write(encoded)
            file.finishWrite(output)
            true
        } catch (_: IOException) {
            file.failWrite(output)
            false
        } catch (_: SecurityException) {
            file.failWrite(output)
            false
        } catch (error: RuntimeException) {
            file.failWrite(output)
            throw error
        }
    }

    fun delete(): Boolean = synchronized(operationLock) {
        try {
            file.delete()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun baseFileForTest(): File = file.baseFile

    @Throws(IOException::class)
    internal fun leaveInterruptedWriteForTest(encoded: ByteArray): Unit =
        synchronized(operationLock) {
            val output = file.startWrite()
            output.write(encoded)
            output.flush()
            output.fd.sync()
            output.close()
        }

    private companion object {
        const val FILE_NAME: String = "portfolio-snapshot.envelope"
    }
}
