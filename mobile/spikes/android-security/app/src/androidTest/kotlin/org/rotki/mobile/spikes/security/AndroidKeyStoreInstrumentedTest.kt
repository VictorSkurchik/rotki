package org.rotki.mobile.spikes.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var probe: AndroidKeyStoreProbe
    private lateinit var store: EncryptedBlobStore

    @Before
    fun setUp() {
        probe = AndroidKeyStoreProbe(context)
        store = EncryptedBlobStore(context)
        destroyMaterial()
    }

    @After
    fun tearDown() {
        destroyMaterial()
    }

    @Test
    fun signingKeyIsOpaqueGeneratedP256AndReusable() {
        val generated = probe.ensureSigningKey()
        val publicPoint = probe.signingPublicKeySec1()
        val diagnostics = probe.signingKeyDiagnostics()

        assertNull(generated.private.format)
        assertNull(generated.private.encoded)
        assertTrue(diagnostics.opaquePrivateKey)
        assertEquals(256, diagnostics.keySize)
        assertEquals(KeyProperties.ORIGIN_GENERATED, diagnostics.origin)
        assertEquals(KeyProperties.PURPOSE_SIGN, diagnostics.purposes)
        assertEquals(setOf(KeyProperties.DIGEST_SHA256), diagnostics.digests)
        assertEquals(65, publicPoint.size)
        assertEquals(0x04, publicPoint.first().toInt())

        assertArrayEquals(publicPoint, AndroidKeyStoreProbe(context).signingPublicKeySec1())
    }

    @Test
    fun signingCheckedTranscriptProducesProtocolP1363AndRejectsAlteration() {
        probe.ensureSigningKey()
        val transcript = ProofVector.transcript()
        assertEquals(
            ProofVector.TRANSCRIPT_SHA256,
            MessageDigest.getInstance("SHA-256").digest(transcript).toHex(),
        )

        val signature = probe.signDeviceProof(transcript)

        assertEquals(64, signature.size)
        assertTrue(probe.verifyDeviceProof(transcript, signature))
        assertFalse(
            probe.verifyDeviceProof(
                transcript.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
                signature,
            ),
        )
        assertFalse(
            probe.verifyDeviceProof(
                MessageDigest.getInstance("SHA-256").digest(transcript),
                signature,
            ),
        )
    }

    @Test
    fun cleanDeviceWithoutStrongBiometricBlocksAesCreation() {
        val availability = probe.biometricAvailability
        assertFalse(
            "The clean managed-device gate must start without enrolled BIOMETRIC_STRONG",
            availability == BiometricAvailability.AVAILABLE,
        )

        try {
            probe.ensureAesKey()
            throw AssertionError("AES key creation unexpectedly succeeded with $availability")
        } catch (error: BiometricUnavailableException) {
            assertEquals(availability, error.availability)
        }
        assertFalse(probe.hasAesKey)
    }

    @Test
    fun noBackupStoreUsesVersionedCiphertextOnlyEnvelope() {
        val plaintext = "known plaintext that must not be stored".toByteArray()
        val blob = EncryptedBlob(
            iv = ByteArray(12) { (it + 1).toByte() },
            ciphertext = ByteArray(48) { (it + 50).toByte() },
        )
        store.write(blob)

        assertTrue(store.pathForTest().canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(store.pathForTest().readBytes().containsSubsequence(plaintext))
        assertArrayEquals(blob.iv, checkNotNull(store.read()).iv)
        assertArrayEquals(blob.ciphertext, checkNotNull(store.read()).ciphertext)
    }

    @Test
    fun interruptedAtomicWriteRetainsLastGoodEnvelope() {
        val lastGood = EncryptedBlob(
            iv = ByteArray(12) { (it + 1).toByte() },
            ciphertext = ByteArray(48) { (it + 10).toByte() },
        )
        val interrupted = EncryptedBlob(
            iv = ByteArray(12) { (it + 20).toByte() },
            ciphertext = ByteArray(48) { (it + 40).toByte() },
        )
        store.write(lastGood)

        store.leaveInterruptedWriteForTest(interrupted)

        val recovered = checkNotNull(store.read())
        assertArrayEquals(lastGood.iv, recovered.iv)
        assertArrayEquals(lastGood.ciphertext, recovered.ciphertext)
    }

    @Test
    fun apiLevelIsTheRequestedCompatibilityFloorWhenRunOnGateDevice() {
        assertEquals(28, Build.VERSION.SDK_INT)
    }

    private fun destroyMaterial() {
        store.delete()
        probe.deleteAllKeys()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        candidate.isNotEmpty() && candidate.size <= size &&
            (0..size - candidate.size).any { offset ->
                candidate.indices.all { index -> this[offset + index] == candidate[index] }
            }
}
