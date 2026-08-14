package org.rotki.mobile.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPairingCleanupJournalInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp(): Unit =
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            createAndroidPairingCleanupJournal(context).clear()
        }

    @After
    fun tearDown(): Unit =
        runBlocking {
            createAndroidPairingCleanupJournal(context).clear()
        }

    @Test
    fun markerIsAtomicSecretFreeBackupExcludedAndIdempotentlyCleared(): Unit =
        runBlocking {
            val journal = createAndroidPairingCleanupJournal(context)

            assertEquals(PairingCleanupJournalReadOutcome.Clear, journal.read())
            assertEquals(
                PairingCleanupJournalWriteOutcome.Stored,
                journal.markCleanupRequired(),
            )
            assertEquals(PairingCleanupJournalReadOutcome.CleanupRequired, journal.read())

            val markerFile = cleanupMarkerFile()
            val marker = markerFile.readBytes()
            assertEquals(context.noBackupFilesDir, markerFile.parentFile)
            assertEquals(CLEANUP_MARKER_FILE_NAME, markerFile.name)
            assertArrayEquals("rotki-pairing-cleanup-v1\n".encodeToByteArray(), marker)
            assertFalse("https://" in marker.decodeToString())

            assertEquals(PairingCleanupJournalClearOutcome.Cleared, journal.clear())
            assertEquals(PairingCleanupJournalClearOutcome.Cleared, journal.clear())
            assertEquals(
                PairingCleanupJournalReadOutcome.Clear,
                createAndroidPairingCleanupJournal(context).read(),
            )
            assertFalse(markerFile.exists())
        }

    @Test
    fun arbitraryAndOversizedMarkersRemainFailClosed(): Unit =
        runBlocking {
            val journal = createAndroidPairingCleanupJournal(context)
            val markerFile = cleanupMarkerFile()

            markerFile.writeBytes("not-a-canonical-marker".encodeToByteArray())
            assertEquals(PairingCleanupJournalReadOutcome.CleanupRequired, journal.read())

            journal.clear()
            markerFile.writeBytes(ByteArray(OVERSIZED_MARKER_BYTES) { 0x41 })
            assertEquals(PairingCleanupJournalReadOutcome.CleanupRequired, journal.read())
        }

    @Test
    fun concurrentFactoriesLeaveOnlyACompleteMarkerState(): Unit =
        runBlocking {
            val start = CompletableDeferred<Unit>()
            val operations =
                List(CONCURRENT_OPERATIONS) { index ->
                    async(Dispatchers.IO) {
                        val journal = createAndroidPairingCleanupJournal(context)
                        start.await()
                        if (index % 2 == 0) {
                            journal.markCleanupRequired()
                        } else {
                            journal.clear()
                        }
                    }
                }

            start.complete(Unit)
            operations.awaitAll().forEach { outcome ->
                assertTrue(
                    outcome == PairingCleanupJournalWriteOutcome.Stored ||
                        outcome == PairingCleanupJournalClearOutcome.Cleared,
                )
            }
            when (createAndroidPairingCleanupJournal(context).read()) {
                PairingCleanupJournalReadOutcome.Clear -> {
                    assertFalse(cleanupMarkerFile().exists())
                }

                PairingCleanupJournalReadOutcome.CleanupRequired -> {
                    assertArrayEquals(
                        "rotki-pairing-cleanup-v1\n".encodeToByteArray(),
                        cleanupMarkerFile().readBytes(),
                    )
                }

                PairingCleanupJournalReadOutcome.Unavailable -> {
                    throw AssertionError("concurrent journal operations became unavailable")
                }
            }
        }

    private fun cleanupMarkerFile(): File = File(context.noBackupFilesDir, CLEANUP_MARKER_FILE_NAME)

    private companion object {
        const val CLEANUP_MARKER_FILE_NAME: String = "pairing-cleanup.marker"
        const val CONCURRENT_OPERATIONS: Int = 32
        const val OVERSIZED_MARKER_BYTES: Int = 129
    }
}
