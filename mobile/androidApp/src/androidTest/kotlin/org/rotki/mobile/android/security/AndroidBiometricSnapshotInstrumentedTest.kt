package org.rotki.mobile.android.security

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rotki.mobile.android.MainActivity
import org.rotki.mobile.core.ports.SecureSnapshotReadOutcome
import org.rotki.mobile.core.ports.SecureSnapshotWriteOutcome

/**
 * Opt-in enrolled-biometric checks. Run with `-e rotki.biometric true` and authorize every
 * visible prompt with the enrolled strong biometric. They intentionally stay out of clean CI.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBiometricSnapshotInstrumentedTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var keyStore: AndroidSnapshotKeyStore

    @Before
    fun setUp(): Unit {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "true",
        )
        keyStore = AndroidSnapshotKeyStore(context)
        keyStore.delete()
        assertTrue("Strong biometric enrollment is required", keyStore.createForPairing())
    }

    @After
    fun tearDown(): Unit {
        if (::keyStore.isInitialized) keyStore.delete()
    }

    @Test
    fun aesKeyHasExactPerUseBiometricOnlyPolicy(): Unit {
        val policy = keyStore.policyForTest()

        assertTrue(policy.opaque)
        assertEquals(256, policy.keySize)
        assertEquals(KeyProperties.ORIGIN_GENERATED, policy.origin)
        assertEquals(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            policy.purposes,
        )
        assertEquals(setOf(KeyProperties.BLOCK_MODE_GCM), policy.blockModes)
        assertEquals(setOf(KeyProperties.ENCRYPTION_PADDING_NONE), policy.encryptionPaddings)
        assertTrue(policy.userAuthenticationRequired)
        assertTrue(policy.authenticationValiditySeconds <= 0)
        assertTrue(policy.invalidatedByBiometricEnrollment)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, policy.authenticationType)
        } else {
            assertNull(policy.authenticationType)
            assertEquals(-1, policy.authenticationValiditySeconds)
        }
    }

    @Test
    fun productionStoreUsesFreshIvRoundTripsAndRejectsTamper(): Unit {
        val plaintext = "biometric-only snapshot bytes".toByteArray()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val snapshotFile = AtomicSnapshotFile(context).also { file -> file.delete() }
        try {
            val broker = AndroidBiometricCryptoBroker(
                activity = activity,
                promptCopy = AndroidBiometricPromptCopy(
                    title = "Rotki snapshot security test",
                    subtitle = "Authorize the encrypted Snapshot operation",
                    cancel = "Cancel",
                ),
            )
            val cleaner = object : LocalMaterialCleaner {
                override suspend fun destroyAll(): Boolean = true
            }
            val store = AndroidSecureSnapshotStore(
                context = context,
                keyStore = keyStore,
                biometricBroker = broker,
                materialCleaner = cleaner,
                file = snapshotFile,
            )

            assertEquals(
                SecureSnapshotWriteOutcome.Stored,
                runBlocking { store.replace(plaintext) },
            )
            val first = readStoredEnvelope(snapshotFile)
            assertFalse(snapshotFile.baseFileForTest().readBytes().containsSubsequence(plaintext))

            assertEquals(
                SecureSnapshotWriteOutcome.Stored,
                runBlocking { store.replace(plaintext) },
            )
            val second = readStoredEnvelope(snapshotFile)
            assertFalse(
                first.initializationVectorCopy().contentEquals(second.initializationVectorCopy()),
            )

            val reloaded = AndroidSecureSnapshotStore(
                context = context,
                keyStore = keyStore,
                biometricBroker = broker,
                materialCleaner = cleaner,
                file = snapshotFile,
            )
            val read = runBlocking { reloaded.readAfterDeviceAuthentication() }
                as SecureSnapshotReadOutcome.Unlocked
            assertArrayEquals(plaintext, checkNotNull(read.documentCopy()))
            read.discard()

            val tampered = second.ciphertextAndTagCopy().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
            assertTrue(
                snapshotFile.replace(
                    SnapshotEnvelopeCodec.encode(
                        SnapshotEnvelope(second.initializationVectorCopy(), tampered),
                    ),
                ),
            )
            assertEquals(
                SecureSnapshotReadOutcome.Corrupt,
                runBlocking { reloaded.readAfterDeviceAuthentication() },
            )
        } finally {
            snapshotFile.delete()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun readStoredEnvelope(file: AtomicSnapshotFile): SnapshotEnvelope =
        SnapshotEnvelopeCodec.decode(
            (file.read() as AtomicSnapshotReadOutcome.Present).bytesCopy(),
        )

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        candidate.isNotEmpty() && candidate.size <= size &&
            (0..size - candidate.size).any { offset ->
                candidate.indices.all { index -> this[offset + index] == candidate[index] }
            }

    private companion object {
        const val OPT_IN_ARGUMENT: String = "rotki.biometric"
    }
}
