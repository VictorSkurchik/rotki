package org.rotki.mobile.spikes.security

import android.app.Instrumentation
import android.content.Context
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in interactive tests. Start with `-e p03.biometric true`, wait for the prompt, then run
 * `adb emu finger touch 1` followed by `adb emu finger remove` for every prompt.
 */
@RunWith(AndroidJUnit4::class)
class BiometricAesGcmInstrumentedTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var probe: AndroidKeyStoreProbe
    private lateinit var store: EncryptedBlobStore

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("p03.biometric") == "true")
        probe = AndroidKeyStoreProbe(context)
        store = EncryptedBlobStore(context)
        assumeTrue(probe.biometricAvailability == BiometricAvailability.AVAILABLE)
        destroyMaterial()
        probe.ensureSigningKey()
        probe.ensureAesKey()
    }

    @After
    fun tearDown() {
        if (::probe.isInitialized) {
            destroyMaterial()
        }
    }

    @Test
    fun aesKeyHasExactPerUseBiometricPolicyAndIsOpaque() {
        val diagnostics = probe.aesKeyDiagnostics()

        assertTrue(diagnostics.opaqueSecretKey)
        assertEquals(256, diagnostics.keySize)
        assertEquals(KeyProperties.ORIGIN_GENERATED, diagnostics.origin)
        assertEquals(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            diagnostics.purposes,
        )
        assertEquals(setOf(KeyProperties.BLOCK_MODE_GCM), diagnostics.blockModes)
        assertEquals(setOf(KeyProperties.ENCRYPTION_PADDING_NONE), diagnostics.encryptionPaddings)
        assertTrue(diagnostics.userAuthenticationRequired)
        assertTrue(diagnostics.authenticationValiditySeconds <= 0)
        assertTrue(diagnostics.invalidatedByBiometricEnrollment)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, diagnostics.authenticationType)
        } else {
            assertNull(diagnostics.authenticationType)
            assertEquals(-1, diagnostics.authenticationValiditySeconds)
        }
    }

    @Test
    fun eachEncryptAndDecryptUsesAnAuthenticatedCryptoObject() {
        val plaintext = "biometric-only snapshot bytes".toByteArray()
        val first = authenticateEncrypt(plaintext)
        val second = authenticateEncrypt(plaintext)

        assertFalse(first.iv.contentEquals(second.iv))
        assertArrayEquals(plaintext, authenticateDecrypt(first))
        assertArrayEquals(plaintext, authenticateDecrypt(second))
        store.write(first)
        assertArrayEquals(plaintext, authenticateDecrypt(checkNotNull(EncryptedBlobStore(context).read())))
    }

    @Test
    fun authenticatedGcmRejectsTamperedCiphertext() {
        val blob = authenticateEncrypt("tamper evidence".toByteArray())
        val tampered = blob.ciphertext.copyOf().also { it[it.lastIndex] = (it.last() xor 1) }
        val cipher = authenticateCipher(probe.newDecryptCipher(EncryptedBlob(blob.iv, tampered)))

        assertThrows(AEADBadTagException::class.java) {
            probe.decryptWithAuthenticatedCipher(cipher, tampered)
        }
    }

    private fun authenticateEncrypt(plaintext: ByteArray): EncryptedBlob {
        val cipher = authenticateCipher(probe.newEncryptCipher())
        return probe.encryptWithAuthenticatedCipher(cipher, plaintext)
    }

    private fun authenticateDecrypt(blob: EncryptedBlob): ByteArray {
        val cipher = authenticateCipher(probe.newDecryptCipher(blob))
        return probe.decryptWithAuthenticatedCipher(cipher, blob.ciphertext)
    }

    private fun authenticateCipher(cipher: javax.crypto.Cipher): javax.crypto.Cipher {
        val latch = CountDownLatch(1)
        val authenticated = AtomicReference<javax.crypto.Cipher>()
        val failure = AtomicReference<Throwable>()
        val activity = instrumentation.startActivitySync(
            android.content.Intent(context, SecuritySpikeActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as SecuritySpikeActivity
        instrumentation.runOnMainSync {
            androidx.biometric.BiometricPrompt(
                activity,
                activity.mainExecutor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                    ) {
                        if (result.authenticationType ==
                            androidx.biometric.BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
                        ) {
                            failure.set(AssertionError("Device credential must never authorize the key"))
                        } else {
                            authenticated.set(result.cryptoObject?.cipher)
                        }
                        latch.countDown()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        failure.set(AssertionError("Biometric error $errorCode: $errString"))
                        latch.countDown()
                    }
                },
            ).authenticate(
                androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle("P0.3 test operation")
                    .setAllowedAuthenticators(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    )
                    .setNegativeButtonText("Cancel")
                    .build(),
                androidx.biometric.BiometricPrompt.CryptoObject(cipher),
            )
        }
        try {
            assertTrue("Timed out waiting for biometric injection", latch.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError(it) }
            return checkNotNull(authenticated.get())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun destroyMaterial() {
        store.delete()
        probe.deleteAllKeys()
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()
}
