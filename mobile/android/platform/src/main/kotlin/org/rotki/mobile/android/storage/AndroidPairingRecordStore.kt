package org.rotki.mobile.android.storage

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.ports.PairingRecordWriteOutcome
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Creates a backup-excluded, atomic Pairing-record store for this application process.
 *
 * The adapter resolves its file eagerly and does not retain [context]. All instances coordinate
 * through one process-wide lock because they address the same canonical file.
 */
public fun createAndroidPairingRecordStore(context: Context): PairingRecordStore =
    DefaultAndroidPairingRecordStore(
        AtomicFile(File(context.noBackupFilesDir, PAIRING_RECORD_FILE_NAME)),
    )

private const val PAIRING_RECORD_FILE_NAME: String = "pairing-record.bin"

private class DefaultAndroidPairingRecordStore(
    private val file: AtomicFile,
) : PairingRecordStore {
    override suspend fun read(): PairingRecordReadOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val encoded =
                    try {
                        readBounded()
                    } catch (_: FileNotFoundException) {
                        return@withContext PairingRecordReadOutcome.Missing
                    } catch (_: IOException) {
                        return@withContext PairingRecordReadOutcome.Unavailable
                    } catch (_: SecurityException) {
                        return@withContext PairingRecordReadOutcome.Unavailable
                    } ?: return@withContext PairingRecordReadOutcome.Corrupt

                val record = PairingRecordCodec.decode(encoded)
                if (record == null) {
                    PairingRecordReadOutcome.Corrupt
                } else {
                    PairingRecordReadOutcome.Present(record)
                }
            }
        }

    // AtomicFile must roll back its temporary write before propagating any unexpected runtime failure.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val encoded = PairingRecordCodec.encode(record)
                val output =
                    try {
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

    override suspend fun delete(): PairingRecordDeleteOutcome =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    file.delete()
                    PairingRecordDeleteOutcome.Deleted
                } catch (_: SecurityException) {
                    PairingRecordDeleteOutcome.Unavailable
                }
            }
        }

    private fun readBounded(): ByteArray? =
        file.openRead().use { input ->
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

    private companion object {
        val operationMutex: Mutex = Mutex()
    }
}

/** Strict binary codec whose only variable fields are canonical protocol strings. */
private object PairingRecordCodec {
    const val MAX_ENCODED_BYTES: Int = 2_104

    private const val VERSION: Int = 1
    private const val HEADER_BYTES: Int = 4 + 1 + Short.SIZE_BYTES + 1
    private const val MAX_ORIGIN_BYTES: Int = 2_048
    private const val DEVICE_SESSION_ID_BYTES: Int = 43
    private val magic: ByteArray =
        byteArrayOf(
            'R'.code.toByte(),
            'K'.code.toByte(),
            'P'.code.toByte(),
            'R'.code.toByte(),
        )

    fun encode(record: PairingRecord): ByteArray {
        val origin = record.engineOrigin.canonical.toByteArray(Charsets.US_ASCII)
        val deviceSessionId = record.deviceSessionId.encoded.toByteArray(Charsets.US_ASCII)
        require(origin.size in 1..MAX_ORIGIN_BYTES) { "Engine origin is outside the supported bound" }
        require(deviceSessionId.size == DEVICE_SESSION_ID_BYTES) {
            "Device Session ID has an invalid encoded length"
        }
        return ByteBuffer
            .allocate(HEADER_BYTES + origin.size + deviceSessionId.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION.toByte())
            .putShort(origin.size.toShort())
            .put(deviceSessionId.size.toByte())
            .put(origin)
            .put(deviceSessionId)
            .array()
    }

    fun decode(encoded: ByteArray): PairingRecord? {
        if (encoded.size !in minimumEncodedBytes()..MAX_ENCODED_BYTES) {
            return null
        }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size).also(input::get)
        if (!actualMagic.contentEquals(magic) || input.get().toInt() and 0xff != VERSION) {
            return null
        }
        val originSize = input.short.toInt() and 0xffff
        val deviceSessionIdSize = input.get().toInt() and 0xff
        if (originSize !in 1..MAX_ORIGIN_BYTES ||
            deviceSessionIdSize != DEVICE_SESSION_ID_BYTES ||
            input.remaining() != originSize + deviceSessionIdSize
        ) {
            return null
        }
        val originBytes = ByteArray(originSize).also(input::get)
        val deviceSessionIdBytes = ByteArray(deviceSessionIdSize).also(input::get)
        val originText = originBytes.strictAsciiOrNull() ?: return null
        val deviceSessionIdText =
            deviceSessionIdBytes.strictAsciiOrNull()
                ?: return null
        val origin =
            when (val parsed = EngineOrigin.parse(originText)) {
                is EngineOriginParseOutcome.Accepted -> parsed.origin
                is EngineOriginParseOutcome.Rejected -> return null
            }
        val deviceSessionId =
            when (val parsed = DeviceSessionId.parse(deviceSessionIdText)) {
                is ProtocolValueParseOutcome.Accepted -> parsed.value
                is ProtocolValueParseOutcome.Rejected -> return null
            }
        return PairingRecord(origin, deviceSessionId)
    }

    private fun minimumEncodedBytes(): Int = HEADER_BYTES + 1 + DEVICE_SESSION_ID_BYTES

    private fun ByteArray.strictAsciiOrNull(): String? {
        if (any { byte -> byte.toInt() and 0xff !in 0x21..0x7e }) return null
        return toString(Charsets.US_ASCII)
    }
}
