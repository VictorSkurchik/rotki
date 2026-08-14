package org.rotki.mobile.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class AndroidDeviceProofSignerInstrumentedTest {
    private lateinit var signer: DeviceProofSigner
    private lateinit var aliasesBeforeTest: Set<String>

    @Before
    fun setUp() {
        signer = createAndroidDeviceProofSigner()
        runBlocking {
            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
        }
        aliasesBeforeTest = keyStoreAliases()
    }

    @After
    fun tearDown() {
        runBlocking {
            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
        }
        assertEquals(aliasesBeforeTest, keyStoreAliases())
    }

    @Test
    fun deviceKeyIsPersistentOpaqueP256AndPurposeRestrictedAcrossFactories() =
        runBlocking {
            val created = requirePublicKey(signer.createKeyForPairing())
            val aliasesAfterCreate = keyStoreAliases()
            assertEquals(setOf(EXPECTED_SIGNING_ALIAS), aliasesAfterCreate - aliasesBeforeTest)
            assertTrue(aliasesAfterCreate.containsAll(aliasesBeforeTest))

            val entry = signingEntry(EXPECTED_SIGNING_ALIAS)
            val privateKey = entry.privateKey
            val info =
                KeyFactory
                    .getInstance(privateKey.algorithm, ANDROID_KEY_STORE_PROVIDER)
                    .getKeySpec(privateKey, KeyInfo::class.java)

            assertNull(privateKey.format)
            assertNull(privateKey.encoded)
            assertEquals(256, info.keySize)
            assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
            assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes)
            assertEquals(setOf(KeyProperties.DIGEST_SHA256), info.digests.toSet())
            assertFalse(info.isUserAuthenticationRequired)
            assertArrayEquals(
                publicKeyToX963ForTest(entry.certificate.publicKey),
                Base64.getUrlDecoder().decode(created.encoded),
            )

            val current = requirePublicKey(createAndroidDeviceProofSigner().currentPublicKeyX963())
            val createdAgain = requirePublicKey(createAndroidDeviceProofSigner().createKeyForPairing())
            assertEquals(created, current)
            assertEquals(created, createdAgain)
            assertEquals("AndroidDeviceProofSigner(redacted)", signer.toString())
            assertFalse(signer.toString().contains(created.encoded))
        }

    @Test
    fun missingCurrentAndSignNeverCreateAKeyAndDeleteIsIdempotent() =
        runBlocking {
            val transcript = "transcript-that-must-not-create-a-key".toByteArray()

            assertSame(DeviceProofPublicKeyOutcome.PairingRequired, signer.currentPublicKeyX963())
            assertSame(DeviceProofSigningOutcome.PairingRequired, signer.sign(transcript))
            assertEquals(aliasesBeforeTest, keyStoreAliases())

            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
            assertEquals(aliasesBeforeTest, keyStoreAliases())
        }

    @Test
    fun completeTranscriptIsSignedExactlyOnceAsProtocolP1363WithoutMutatingCaller() =
        runBlocking {
            signer.createKeyForPairing()
            val transcript = deviceProofTranscriptFixture()
            val original = transcript.copyOf()
            assertEquals(
                EXPECTED_TRANSCRIPT_SHA256,
                MessageDigest.getInstance("SHA-256").digest(transcript).toHex(),
            )

            val signed = requireSigned(signer.sign(transcript))
            val p1363 = Base64.getUrlDecoder().decode(signed.signature.encoded)
            val publicKey = signingEntry(EXPECTED_SIGNING_ALIAS).certificate.publicKey

            assertArrayEquals(original, transcript)
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
            assertFalse(signed.toString().contains(signed.signature.encoded))
        }

    @Test
    fun unexpectedAliasEntryFailsClosedWithoutReplacingPairingIdentity() =
        runBlocking {
            val alias = discoverCanonicalAlias()
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE_PROVIDER)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            ).setKeySize(128)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }.generateKey()
            val transcript = "caller-owned-transcript".toByteArray()
            val original = transcript.copyOf()

            assertSame(DeviceProofPublicKeyOutcome.UnexpectedFailure, signer.currentPublicKeyX963())
            assertSame(DeviceProofPublicKeyOutcome.UnexpectedFailure, signer.createKeyForPairing())
            assertSame(DeviceProofSigningOutcome.UnexpectedFailure, signer.sign(transcript))
            assertArrayEquals(original, transcript)
            assertTrue(keyStore().containsAlias(alias))
            assertTrue(keyStore().getEntry(alias, null) is KeyStore.SecretKeyEntry)

            assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
            assertFalse(keyStore().containsAlias(alias))
            assertEquals(aliasesBeforeTest, keyStoreAliases())
        }

    @Test
    fun cancellationIsRethrownByEverySuspendOperation() =
        runBlocking {
            assertCancellationPropagates { signer.createKeyForPairing() }
            assertCancellationPropagates { signer.currentPublicKeyX963() }
            assertCancellationPropagates { signer.sign("cancelled-transcript".toByteArray()) }
            assertCancellationPropagates { signer.deleteKey() }
            assertEquals(aliasesBeforeTest, keyStoreAliases())
        }

    @Test
    fun concurrentFactoriesReuseOneKeyAndProduceVerifiableSignatures() =
        runBlocking {
            val signers = List(CONCURRENT_SIGNER_COUNT) { createAndroidDeviceProofSigner() }
            val publicKeys =
                coroutineScope {
                    signers
                        .map { concurrentSigner ->
                            async(Dispatchers.Default) {
                                requirePublicKey(concurrentSigner.createKeyForPairing())
                            }
                        }.awaitAll()
                }
            assertEquals(1, publicKeys.toSet().size)
            assertEquals(setOf(EXPECTED_SIGNING_ALIAS), keyStoreAliases() - aliasesBeforeTest)

            val publicKey = signingEntry(EXPECTED_SIGNING_ALIAS).certificate.publicKey
            val signed =
                coroutineScope {
                    signers
                        .mapIndexed { index, concurrentSigner ->
                            async(Dispatchers.Default) {
                                val transcript = "concurrent-device-proof-$index".toByteArray()
                                transcript to requireSigned(concurrentSigner.sign(transcript))
                            }
                        }.awaitAll()
                }
            signed.forEach { (transcript, outcome) ->
                val p1363 = Base64.getUrlDecoder().decode(outcome.signature.encoded)
                assertEquals(64, p1363.size)
                assertTrue(verify(publicKey, transcript, p1363))
            }
        }

    private suspend fun discoverCanonicalAlias(): String {
        val before = keyStoreAliases()
        requirePublicKey(signer.createKeyForPairing())
        val after = keyStoreAliases()
        val added = after - before
        assertEquals(setOf(EXPECTED_SIGNING_ALIAS), added)
        assertTrue(after.containsAll(before))
        assertSame(DeviceProofKeyDeleteOutcome.Deleted, signer.deleteKey())
        assertEquals(before, keyStoreAliases())
        return added.single()
    }

    private suspend fun assertCancellationPropagates(operation: suspend () -> Any?) {
        val returned = AtomicBoolean(false)
        val thrown = AtomicReference<Throwable>()
        coroutineScope {
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel(CancellationException(CANCELLATION_SENTINEL))
                    try {
                        operation()
                        returned.set(true)
                    } catch (error: Throwable) {
                        thrown.set(error)
                    }
                }
            job.join()
        }

        assertFalse("Cancelled operation returned a typed outcome", returned.get())
        assertTrue(thrown.get() is CancellationException)
        assertEquals(CANCELLATION_SENTINEL, thrown.get()?.message)
    }

    private fun verify(
        publicKey: PublicKey,
        transcript: ByteArray,
        p1363: ByteArray,
    ): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(transcript)
            verify(p1363ToDerForTest(p1363))
        }

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

    private fun signingEntry(alias: String): KeyStore.PrivateKeyEntry =
        keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw AssertionError("Expected a private-key entry for $alias")

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER).apply {
            load(null)
        }

    private fun keyStoreAliases(): Set<String> {
        val aliases = keyStore().aliases()
        return buildSet {
            while (aliases.hasMoreElements()) {
                add(aliases.nextElement())
            }
        }
    }

    private fun deviceProofTranscriptFixture(): ByteArray {
        val fixture =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("golden_vectors.json")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> JSONObject(reader.readText()) }
                .getJSONObject("device_proof")
        assertEquals(EXPECTED_TRANSCRIPT_SHA256, fixture.getString("transcript_sha256"))
        return fixture.getString("transcript_hex").hexToByteArray()
    }

    private fun publicKeyToX963ForTest(publicKey: PublicKey): ByteArray {
        val ec = publicKey as? ECPublicKey ?: error("Expected EC public key")
        return byteArrayOf(0x04) + fixedWidthUnsigned(ec.w.affineX) + fixedWidthUnsigned(ec.w.affineY)
    }

    private fun fixedWidthUnsigned(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val magnitude =
            if (encoded.size > 1 && encoded.first() == 0.toByte()) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
        require(magnitude.size <= P256_COMPONENT_BYTES)
        return ByteArray(P256_COMPONENT_BYTES - magnitude.size) + magnitude
    }

    private fun p1363ToDerForTest(p1363: ByteArray): ByteArray {
        require(p1363.size == P1363_SIGNATURE_BYTES)
        val r = encodeDerIntegerForTest(p1363.copyOfRange(0, P256_COMPONENT_BYTES))
        val s = encodeDerIntegerForTest(p1363.copyOfRange(P256_COMPONENT_BYTES, p1363.size))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun encodeDerIntegerForTest(fixed: ByteArray): ByteArray {
        val firstNonZero = fixed.indexOfFirst { byte -> byte != 0.toByte() }
        require(firstNonZero >= 0)
        val magnitude = fixed.copyOfRange(firstNonZero, fixed.size)
        return if (magnitude.first().toInt() and 0x80 != 0) {
            byteArrayOf(0) + magnitude
        } else {
            magnitude
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore"
        const val EXPECTED_SIGNING_ALIAS = "rotki_companion_device_proof_signing_v1"
        const val EXPECTED_TRANSCRIPT_SHA256 =
            "f58760e2c57f148ba4b715d689f5648688c9d74f1e3d5d357fd81a64591cf4e0"
        const val CANCELLATION_SENTINEL = "device-proof-operation-cancelled"
        const val P256_COMPONENT_BYTES = 32
        const val P1363_SIGNATURE_BYTES = P256_COMPONENT_BYTES * 2
        const val CONCURRENT_SIGNER_COUNT = 8
    }
}
