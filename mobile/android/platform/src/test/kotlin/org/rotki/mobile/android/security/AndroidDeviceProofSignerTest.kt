package org.rotki.mobile.android.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CancellationException

class AndroidDeviceProofSignerTest {
    @Test
    fun `current and sign never create a missing key`() =
        runBlocking {
            val keyStore = FakeDeviceSigningKeyStore()
            val signer = signer(keyStore)

            assertSame(DeviceProofPublicKeyOutcome.PairingRequired, signer.currentPublicKeyX963())
            assertSame(
                DeviceProofSigningOutcome.PairingRequired,
                signer.sign("transcript".toByteArray()),
            )
            assertEquals(0, keyStore.createCalls)
            assertEquals(1, keyStore.currentCalls)
            assertEquals(1, keyStore.signCalls)
            assertFalse(keyStore.hasKey)
        }

    @Test
    fun `create is the only creation path and returns a reusable X963 key`() =
        runBlocking {
            val keyStore = FakeDeviceSigningKeyStore()
            val signer = signer(keyStore)

            val created = requirePublicKey(signer.createKeyForPairing())
            val current = requirePublicKey(signer.currentPublicKeyX963())
            val createdAgain = requirePublicKey(signer.createKeyForPairing())

            assertEquals(created, current)
            assertEquals(created, createdAgain)
            assertEquals(2, keyStore.createCalls)
            assertEquals(1, keyStore.generationCount)
        }

    @Test
    fun `sign hashes the unchanged transcript once and returns protocol P1363`() =
        runBlocking {
            val keyStore = FakeDeviceSigningKeyStore().apply { createOrCurrent() }
            val signer = signer(keyStore)
            val transcript = "complete-device-proof-transcript".toByteArray()
            val original = transcript.copyOf()

            val signed = requireSigned(signer.sign(transcript))
            val p1363 = Base64.getUrlDecoder().decode(signed.signature.encoded)

            assertArrayEquals(original, transcript)
            assertArrayEquals(original, keyStore.lastSignedTranscript)
            assertEquals(1, keyStore.signCalls)
            assertTrue(
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(keyStore.keyPair.public)
                    update(transcript)
                    verify(AndroidSecurityTestBridge.p1363ToDerEcdsa(p1363))
                },
            )
        }

    @Test
    fun `native and encoding failures map to typed outcomes`() =
        runBlocking {
            val currentFailure =
                FakeDeviceSigningKeyStore().apply {
                    failure = IllegalStateException("native failure")
                }
            assertSame(
                DeviceProofPublicKeyOutcome.UnexpectedFailure,
                signer(currentFailure).currentPublicKeyX963(),
            )

            val invalidPublicKey = FakeDeviceSigningKeyStore("secp384r1")
            assertSame(
                DeviceProofPublicKeyOutcome.UnexpectedFailure,
                signer(invalidPublicKey).createKeyForPairing(),
            )

            val invalidSignature =
                FakeDeviceSigningKeyStore().apply {
                    createOrCurrent()
                    signatureOverride = byteArrayOf(0x30, 0)
                }
            assertSame(
                DeviceProofSigningOutcome.UnexpectedFailure,
                signer(invalidSignature).sign("transcript".toByteArray()),
            )

            val deleteFailure =
                FakeDeviceSigningKeyStore().apply {
                    failure = IllegalStateException("native failure")
                }
            assertSame(DeviceProofKeyDeleteOutcome.Unavailable, signer(deleteFailure).deleteKey())
        }

    @Test
    fun `delete is idempotent and cancellation is never downgraded`() {
        runBlocking {
            val keyStore = FakeDeviceSigningKeyStore().apply { createOrCurrent() }
            val signer = signer(keyStore)

            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
            assertFalse(keyStore.hasKey)

            keyStore.failure = CancellationException("cancelled")
            assertThrows(CancellationException::class.java) {
                runBlocking { signer.currentPublicKeyX963() }
            }
        }
    }

    @Test
    fun `signer and key material diagnostic text is redacted`() {
        val keyPair = p256KeyPair()

        assertEquals(
            "AndroidSigningKeyMaterial(redacted)",
            AndroidSecurityTestBridge.signingKeyMaterialDiagnostic(keyPair),
        )
        assertEquals(
            "AndroidDeviceProofSigner(redacted)",
            signer(FakeDeviceSigningKeyStore()).toString(),
        )
    }

    private fun signer(keyStore: AndroidSecurityTestBridge.TestDeviceSigningKeyStore): DeviceProofSigner =
        AndroidSecurityTestBridge.createDeviceProofSigner(keyStore, Dispatchers.Unconfined)

    private fun requirePublicKey(outcome: DeviceProofPublicKeyOutcome) =
        when (outcome) {
            is DeviceProofPublicKeyOutcome.PublicKey -> outcome.value
            else -> throw AssertionError("Expected public key, got $outcome")
        }

    private fun requireSigned(outcome: DeviceProofSigningOutcome) =
        when (outcome) {
            is DeviceProofSigningOutcome.Signed -> outcome
            else -> throw AssertionError("Expected signature, got $outcome")
        }

    private class FakeDeviceSigningKeyStore(
        private val curve: String = "secp256r1",
    ) : AndroidSecurityTestBridge.TestDeviceSigningKeyStore {
        val keyPair: KeyPair =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec(curve))
                generateKeyPair()
            }
        var createCalls: Int = 0
        var currentCalls: Int = 0
        var signCalls: Int = 0
        var generationCount: Int = 0
        var failure: RuntimeException? = null
        var signatureOverride: ByteArray? = null
        var lastSignedTranscript: ByteArray? = null
        var hasKey: Boolean = false

        override fun createOrCurrent(): KeyPair {
            createCalls += 1
            failure?.let { throw it }
            if (!hasKey) {
                hasKey = true
                generationCount += 1
            }
            return keyPair
        }

        override fun currentOrNull(): KeyPair? {
            currentCalls += 1
            failure?.let { throw it }
            return keyPair.takeIf { hasKey }
        }

        override fun signIfPresent(transcript: ByteArray): ByteArray? {
            signCalls += 1
            failure?.let { throw it }
            if (!hasKey) {
                return null
            }
            lastSignedTranscript = transcript.copyOf()
            signatureOverride?.let { return it.copyOf() }
            return Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(transcript)
                sign()
            }
        }

        override fun delete() {
            failure?.let { throw it }
            hasKey = false
        }
    }

    private companion object {
        fun p256KeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
    }
}
