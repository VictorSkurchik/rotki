package org.rotki.mobile.android.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome

@RunWith(AndroidJUnit4::class)
class AndroidPairingCleanupJournalInstrumentedTest {
    @Test
    fun markerIsAtomicSecretFreeBackupExcludedAndIdempotentlyCleared(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val journal = AndroidPairingCleanupJournal(context)
        journal.clear()

        assertEquals(PairingCleanupJournalReadOutcome.Clear, journal.read())
        assertEquals(
            PairingCleanupJournalWriteOutcome.Stored,
            journal.markCleanupRequired(),
        )
        assertEquals(PairingCleanupJournalReadOutcome.CleanupRequired, journal.read())
        val markerFile = journal.baseFileForTest()
        assertEquals(context.noBackupFilesDir, markerFile.parentFile)
        assertEquals("rotki-pairing-cleanup-v1\n", markerFile.readText())
        assertFalse("https://" in markerFile.readText())

        assertEquals(PairingCleanupJournalClearOutcome.Cleared, journal.clear())
        assertEquals(PairingCleanupJournalClearOutcome.Cleared, journal.clear())
        assertEquals(PairingCleanupJournalReadOutcome.Clear, journal.read())
        assertTrue(!markerFile.exists())
    }
}
