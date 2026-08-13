package org.rotki.mobile.android.security

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome

@RunWith(AndroidJUnit4::class)
class AndroidDeviceProofSignerInstrumentedTest {
    private lateinit var keyStore: AndroidSigningKeyStore
    private lateinit var signer: AndroidDeviceProofSigner

    @Before
    fun setUp(): Unit {
        runBlocking {
            keyStore = AndroidSigningKeyStore()
            signer = AndroidDeviceProofSigner(keyStore)
            signer.deleteKey()
        }
    }

    @After
    fun tearDown(): Unit {
        runBlocking { signer.deleteKey() }
    }

    @Test
    fun deviceKeyIsPersistentOpaqueP256AndPurposeRestricted() = runBlocking {
        val created = requirePublicKey(signer.createKeyForPairing())
        val material = checkNotNull(keyStore.currentOrNull())
        val info = KeyFactory.getInstance(
            material.privateKey.algorithm,
            ANDROID_KEY_STORE_PROVIDER,
        ).getKeySpec(material.privateKey, KeyInfo::class.java)

        assertNull(material.privateKey.format)
        assertNull(material.privateKey.encoded)
        assertEquals(256, info.keySize)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes)
        assertEquals(setOf(KeyProperties.DIGEST_SHA256), info.digests.toSet())
        assertFalse(info.isUserAuthenticationRequired)
        assertTrue(
            KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER).apply { load(null) }
                .containsAlias(ANDROID_DEVICE_PROOF_SIGNING_ALIAS),
        )

        val current = requirePublicKey(AndroidDeviceProofSigner().currentPublicKeyX963())
        val createdAgain = requirePublicKey(AndroidDeviceProofSigner().createKeyForPairing())
        assertEquals(created, current)
        assertEquals(created, createdAgain)
    }

    @Test
    fun missingCurrentAndSignNeverCreateAKeyAndDeleteIsIdempotent() = runBlocking {
        assertSame(DeviceProofPublicKeyOutcome.PairingRequired, signer.currentPublicKeyX963())
        assertSame(
            DeviceProofSigningOutcome.PairingRequired,
            signer.sign("transcript".toByteArray()),
        )
        assertFalse(
            KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER).apply { load(null) }
                .containsAlias(ANDROID_DEVICE_PROOF_SIGNING_ALIAS),
        )

        assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
        assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
    }

    @Test
    fun completeTranscriptIsSignedExactlyOnceAsProtocolP1363() = runBlocking {
        signer.createKeyForPairing()
        val transcript = deviceProofTranscriptFixture()
        assertEquals(
            EXPECTED_TRANSCRIPT_SHA256,
            MessageDigest.getInstance("SHA-256").digest(transcript).toHex(),
        )
        val signed = requireSigned(signer.sign(transcript))
        val p1363 = Base64.getUrlDecoder().decode(signed.signature.encoded)
        val publicKey = checkNotNull(keyStore.currentOrNull()).publicKey

        assertEquals(64, p1363.size)
        assertTrue(verify(publicKey, transcript, p1363))
        assertFalse(
            verify(
                publicKey,
                transcript.copyOf().also { bytes ->
                    bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
                },
                p1363,
            ),
        )
        assertFalse(
            verify(
                publicKey,
                MessageDigest.getInstance("SHA-256").digest(transcript),
                p1363,
            ),
        )
    }

    private fun verify(
        publicKey: java.security.PublicKey,
        transcript: ByteArray,
        p1363: ByteArray,
    ): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey)
        update(transcript)
        verify(AndroidP256Encoding.p1363ToDerEcdsa(p1363))
    }

    private fun requirePublicKey(outcome: DeviceProofPublicKeyOutcome) = when (outcome) {
        is DeviceProofPublicKeyOutcome.PublicKey -> outcome.value
        else -> throw AssertionError("Expected public key, got $outcome")
    }

    private fun requireSigned(outcome: DeviceProofSigningOutcome) = when (outcome) {
        is DeviceProofSigningOutcome.Signed -> outcome
        else -> throw AssertionError("Expected signature, got $outcome")
    }

    private fun deviceProofTranscriptFixture(): ByteArray {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets
            .open("golden_vectors.json")
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> JSONObject(reader.readText()) }
            .getJSONObject("device_proof")
        assertEquals(EXPECTED_TRANSCRIPT_SHA256, fixture.getString("transcript_sha256"))
        return fixture.getString("transcript_hex").hexToByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore"
        const val EXPECTED_TRANSCRIPT_SHA256 =
            "f58760e2c57f148ba4b715d689f5648688c9d74f1e3d5d357fd81a64591cf4e0"
    }
}
