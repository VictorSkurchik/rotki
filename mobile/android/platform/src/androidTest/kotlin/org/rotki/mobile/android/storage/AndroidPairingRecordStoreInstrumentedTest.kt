package org.rotki.mobile.android.storage

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordWriteOutcome
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPairingRecordStoreInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp(): Unit =
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            createAndroidPairingRecordStore(context).delete()
        }

    @After
    fun tearDown(): Unit =
        runBlocking {
            createAndroidPairingRecordStore(context).delete()
        }

    @Test
    fun recordIsBackupExcludedSurvivesRecreationAndDeletesIdempotently(): Unit =
        runBlocking {
            val store = createAndroidPairingRecordStore(context)
            val expected = record("https://rotki.example:4242")
            val recordFile = pairingRecordFile()

            assertEquals(PairingRecordReadOutcome.Missing, store.read())
            assertEquals(PairingRecordWriteOutcome.Stored, store.write(expected))
            assertEquals(context.noBackupFilesDir, recordFile.parentFile)
            assertEquals(PAIRING_RECORD_FILE_NAME, recordFile.name)
            val recreated = createAndroidPairingRecordStore(context).read()
            assertEquals(
                PairingRecordReadOutcome.Present(expected),
                recreated,
            )
            assertFalse("rotki.example" in recreated.toString())
            assertFalse(DEVICE_SESSION_ID in recreated.toString())

            assertEquals(PairingRecordDeleteOutcome.Deleted, store.delete())
            assertEquals(PairingRecordDeleteOutcome.Deleted, store.delete())
            assertEquals(
                PairingRecordReadOutcome.Missing,
                createAndroidPairingRecordStore(context).read(),
            )
            assertFalse(recordFile.exists())
        }

    @Test
    fun corruptTruncatedAndOversizedFilesAreRejectedByTheBoundedRead(): Unit =
        runBlocking {
            val store = createAndroidPairingRecordStore(context)
            val expected = record("https://rotki.example:4242")
            assertEquals(PairingRecordWriteOutcome.Stored, store.write(expected))
            val valid = pairingRecordFile().readBytes()
            val mutations =
                listOf(
                    "magic" to valid.copyOf().also { bytes -> bytes[0] = 'X'.code.toByte() },
                    "version" to valid.copyOf().also { bytes -> bytes[4] = 2 },
                    "origin length" to valid.copyOf().also { bytes -> bytes[6] = 0 },
                    "session length" to valid.copyOf().also { bytes -> bytes[7] = 42 },
                    "trailing byte" to valid + 0,
                    "truncated" to valid.copyOf(valid.lastIndex),
                    "non ASCII origin" to valid.copyOf().also { bytes -> bytes[8] = 0 },
                    "invalid session ID" to
                        valid.copyOf().also { bytes -> bytes[valid.lastIndex] = '='.code.toByte() },
                    "oversized" to ByteArray(MAX_ENCODED_RECORD_BYTES + 1) { 0x41 },
                )

            mutations.forEach { (name, encoded) ->
                store.delete()
                pairingRecordFile().writeBytes(encoded)
                assertEquals(name, PairingRecordReadOutcome.Corrupt, store.read())
            }
        }

    @Test
    fun interruptedAtomicWriteRestoresTheLastCommittedRecordAcrossRecreation(): Unit =
        runBlocking {
            val store = createAndroidPairingRecordStore(context)
            val committed = record("https://committed.rotki.example:4242")
            val interrupted = record("https://interrupted.rotki.example:4242")

            assertEquals(PairingRecordWriteOutcome.Stored, store.write(interrupted))
            val interruptedBytes = pairingRecordFile().readBytes()
            assertEquals(PairingRecordWriteOutcome.Stored, store.write(committed))
            val interruptedAtomicFile = AtomicFile(pairingRecordFile())
            val output = interruptedAtomicFile.startWrite()
            output.write(interruptedBytes)
            output.flush()
            output.fd.sync()
            output.close()

            assertEquals(
                PairingRecordReadOutcome.Present(committed),
                createAndroidPairingRecordStore(context).read(),
            )
        }

    @Test
    fun concurrentFactoryInstancesAlwaysLeaveOneCompleteRecord(): Unit =
        runBlocking {
            val candidates =
                listOf(
                    record("https://first.rotki.example:4242"),
                    record("https://second.rotki.example:4242"),
                )
            val start = CompletableDeferred<Unit>()
            val writes =
                List(CONCURRENT_WRITES) { index ->
                    async(Dispatchers.IO) {
                        val store = createAndroidPairingRecordStore(context)
                        start.await()
                        store.write(candidates[index % candidates.size])
                    }
                }

            start.complete(Unit)
            assertEquals(
                List(CONCURRENT_WRITES) { PairingRecordWriteOutcome.Stored },
                writes.awaitAll(),
            )
            val final = createAndroidPairingRecordStore(context).read()
            assertTrue(candidates.any { candidate -> final == PairingRecordReadOutcome.Present(candidate) })
        }

    private fun record(originText: String): PairingRecord {
        val parsedOrigin = EngineOrigin.parse(originText)
        check(parsedOrigin is EngineOriginParseOutcome.Accepted)
        val parsedDeviceSessionId =
            DeviceSessionId.parse(DEVICE_SESSION_ID)
        check(parsedDeviceSessionId is ProtocolValueParseOutcome.Accepted<DeviceSessionId>)
        return PairingRecord(parsedOrigin.origin, parsedDeviceSessionId.value)
    }

    private fun pairingRecordFile(): File = File(context.noBackupFilesDir, PAIRING_RECORD_FILE_NAME)

    private companion object {
        const val CONCURRENT_WRITES: Int = 32
        const val DEVICE_SESSION_ID: String =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        const val MAX_ENCODED_RECORD_BYTES: Int = 2_104
        const val PAIRING_RECORD_FILE_NAME: String = "pairing-record.bin"
    }
}
