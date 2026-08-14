package org.rotki.mobile.android.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSnapshotStorageInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var snapshotFile: AtomicSnapshotFile
    private lateinit var keyStore: AndroidSnapshotKeyStore

    @Before
    fun setUp() {
        snapshotFile = AtomicSnapshotFile(context)
        keyStore = AndroidSnapshotKeyStore(context)
        snapshotFile.delete()
        keyStore.delete()
    }

    @After
    fun tearDown() {
        snapshotFile.delete()
        keyStore.delete()
    }

    @Test
    fun noBackupFileContainsOnlyBoundedVersionedCiphertext() {
        val knownPlaintext = "known portfolio plaintext must not be stored".toByteArray()
        val envelope =
            SnapshotEnvelope(
                initializationVector = ByteArray(12) { index -> (index + 1).toByte() },
                ciphertextAndTag = ByteArray(48) { index -> (index + 70).toByte() },
            )
        assertTrue(snapshotFile.replace(SnapshotEnvelopeCodec.encode(envelope)))

        val path = snapshotFile.baseFileForTest().canonicalPath
        assertTrue(path.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(snapshotFile.baseFileForTest().readBytes().containsSubsequence(knownPlaintext))
        val decoded =
            SnapshotEnvelopeCodec.decode(
                (snapshotFile.read() as AtomicSnapshotReadOutcome.Present).bytesCopy(),
            )
        assertArrayEquals(envelope.initializationVectorCopy(), decoded.initializationVectorCopy())
        assertArrayEquals(envelope.ciphertextAndTagCopy(), decoded.ciphertextAndTagCopy())
    }

    @Test
    fun interruptedReplacementRetainsLastGoodEnvelope() {
        val lastGood = SnapshotEnvelope(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val interrupted = SnapshotEnvelope(ByteArray(12) { 3 }, ByteArray(48) { 4 })
        assertTrue(snapshotFile.replace(SnapshotEnvelopeCodec.encode(lastGood)))

        snapshotFile.leaveInterruptedWriteForTest(SnapshotEnvelopeCodec.encode(interrupted))

        val recovered =
            SnapshotEnvelopeCodec.decode(
                (snapshotFile.read() as AtomicSnapshotReadOutcome.Present).bytesCopy(),
            )
        assertArrayEquals(lastGood.initializationVectorCopy(), recovered.initializationVectorCopy())
        assertArrayEquals(lastGood.ciphertextAndTagCopy(), recovered.ciphertextAndTagCopy())
    }

    @Test
    fun corruptStoredEnvelopeIsRejectedWithoutReturningBytes() {
        val valid = SnapshotEnvelope(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val corrupt =
            SnapshotEnvelopeCodec.encode(valid).also { bytes ->
                bytes[0] = 'X'.code.toByte()
            }
        assertTrue(snapshotFile.replace(corrupt))

        val stored = snapshotFile.read() as AtomicSnapshotReadOutcome.Present

        assertThrows(IllegalArgumentException::class.java) {
            SnapshotEnvelopeCodec.decode(stored.bytesCopy())
        }
    }

    @Test
    fun cleanDeviceWithoutStrongEnrollmentRefusesKeyCreation() {
        val availability =
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            )
        assertTrue(Build.VERSION.SDK_INT >= 28)
        assertTrue(availability != BiometricManager.BIOMETRIC_SUCCESS)
        assertFalse(keyStore.createForPairing())
        assertTrue(keyStore.presence() == SnapshotKeyPresence.Missing)
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        candidate.isNotEmpty() && candidate.size <= size &&
            (0..size - candidate.size).any { offset ->
                candidate.indices.all { index -> this[offset + index] == candidate[index] }
            }
}
