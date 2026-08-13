package org.rotki.mobile.android.storage

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.ports.PairingRecordWriteOutcome

class AndroidPairingRecordStore private constructor(
    private val file: AtomicFile,
) : PairingRecordStore {
    private val operationMutex: Mutex = Mutex()

    constructor(context: Context) : this(
        AtomicFile(File(context.noBackupFilesDir, FILE_NAME)),
    )

    override suspend fun read(): PairingRecordReadOutcome = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val encoded = try {
                readBounded()
            } catch (_: FileNotFoundException) {
                return@withContext PairingRecordReadOutcome.Missing
            } catch (_: IOException) {
                return@withContext PairingRecordReadOutcome.Unavailable
            } catch (_: SecurityException) {
                return@withContext PairingRecordReadOutcome.Unavailable
            } ?: return@withContext PairingRecordReadOutcome.Corrupt

            when (val decoded = PairingRecordCodec.decode(encoded)) {
                is PairingRecordDecodeOutcome.Accepted ->
                    PairingRecordReadOutcome.Present(decoded.record)
                PairingRecordDecodeOutcome.Rejected -> PairingRecordReadOutcome.Corrupt
            }
        }
    }

    override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val encoded = PairingRecordCodec.encode(record)
                val output = try {
                    file.startWrite()
                } catch (_: IOException) {
                    return@withContext PairingRecordWriteOutcome.Unavailable
                } catch (_: SecurityException) {
                    return@withContext PairingRecordWriteOutcome.Unavailable
                }
                try {
                    output.write(encoded)
                    file.finishWrite(output)
                    PairingRecordWriteOutcome.Stored
                } catch (_: IOException) {
                    file.failWrite(output)
                    PairingRecordWriteOutcome.Unavailable
                } catch (_: SecurityException) {
                    file.failWrite(output)
                    PairingRecordWriteOutcome.Unavailable
                } catch (error: RuntimeException) {
                    file.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun delete(): PairingRecordDeleteOutcome = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                file.delete()
                PairingRecordDeleteOutcome.Deleted
            } catch (_: SecurityException) {
                PairingRecordDeleteOutcome.Unavailable
            }
        }
    }

    private fun readBounded(): ByteArray? = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count == 0) continue
            if (output.size() > PairingRecordCodec.MAX_ENCODED_BYTES - count) return null
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    internal fun baseFileForTest(): File = file.baseFile

    @Throws(IOException::class)
    internal fun leaveInterruptedWriteForTest(record: PairingRecord): Unit {
        val output = file.startWrite()
        output.write(PairingRecordCodec.encode(record))
        output.flush()
        output.fd.sync()
        output.close()
    }

    private companion object {
        const val FILE_NAME: String = "pairing-record.bin"
    }
}
