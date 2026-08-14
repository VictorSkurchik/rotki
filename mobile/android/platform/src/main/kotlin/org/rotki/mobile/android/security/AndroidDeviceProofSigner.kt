package org.rotki.mobile.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Date
import java.util.concurrent.CancellationException
import javax.security.auth.x500.X500Principal

/**
 * Creates an Android Keystore-backed Device-proof signer for the application process.
 *
 * The adapter retains no Context or Activity. Independently created adapters coordinate access to
 * the same canonical, non-exportable key through one process-wide lock; the application should
 * retain the returned port for its process lifetime.
 */
public fun createAndroidDeviceProofSigner(): DeviceProofSigner =
    DefaultAndroidDeviceProofSigner(
        keyStore = DefaultAndroidSigningKeyStore(),
        dispatcher = Dispatchers.IO,
    )

private class DefaultAndroidDeviceProofSigner(
    private val keyStore: DeviceSigningKeyStore,
    private val dispatcher: CoroutineDispatcher,
) : DeviceProofSigner {
    override suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome =
        try {
            val material = onCryptoDispatcher { keyStore.createOrCurrent() }
            material.publicKey.toProtocolOutcome()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofPublicKeyOutcome.UnexpectedFailure
        }

    override suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome =
        try {
            val material = onCryptoDispatcher { keyStore.currentOrNull() }
            if (material == null) {
                DeviceProofPublicKeyOutcome.PairingRequired
            } else {
                material.publicKey.toProtocolOutcome()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofPublicKeyOutcome.UnexpectedFailure
        }

    override suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome {
        val frozenTranscript = transcript.copyOf()
        return try {
            val derSignature =
                onCryptoDispatcher {
                    keyStore.signIfPresent(frozenTranscript)
                } ?: return DeviceProofSigningOutcome.PairingRequired
            when (
                val parsed =
                    P1363Signature.fromBytes(
                        AndroidP256Encoding.derEcdsaToP1363(derSignature),
                    )
            ) {
                is ProtocolValueParseOutcome.Accepted -> {
                    DeviceProofSigningOutcome.Signed(parsed.value)
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    DeviceProofSigningOutcome.UnexpectedFailure
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofSigningOutcome.UnexpectedFailure
        } finally {
            frozenTranscript.fill(0)
        }
    }

    override suspend fun deleteKey(): DeviceProofKeyDeleteOutcome =
        try {
            onCryptoDispatcher { keyStore.delete() }
            DeviceProofKeyDeleteOutcome.Deleted
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofKeyDeleteOutcome.Unavailable
        }

    override fun toString(): String = "AndroidDeviceProofSigner(redacted)"

    private suspend fun <T> onCryptoDispatcher(operation: () -> T): T = withContext(dispatcher) { operation() }

    private fun PublicKey.toProtocolOutcome(): DeviceProofPublicKeyOutcome =
        when (val parsed = X963PublicKey.fromBytes(AndroidP256Encoding.publicKeyToX963(this))) {
            is ProtocolValueParseOutcome.Accepted -> {
                DeviceProofPublicKeyOutcome.PublicKey(parsed.value)
            }

            is ProtocolValueParseOutcome.Rejected -> {
                DeviceProofPublicKeyOutcome.UnexpectedFailure
            }
        }
}

private class AndroidSigningKeyMaterial(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
) {
    override fun toString(): String = "AndroidSigningKeyMaterial(redacted)"
}

private interface DeviceSigningKeyStore {
    fun createOrCurrent(): AndroidSigningKeyMaterial

    fun currentOrNull(): AndroidSigningKeyMaterial?

    fun signIfPresent(transcript: ByteArray): ByteArray?

    fun delete()
}

/** Persistent non-exportable P-256 Device Key storage backed by AndroidKeyStore. */
private class DefaultAndroidSigningKeyStore : DeviceSigningKeyStore {
    // Rollback must cover every provider failure after the KeyStore generates the key pair.
    @Suppress("TooGenericExceptionCaught")
    override fun createOrCurrent(): AndroidSigningKeyMaterial =
        synchronized(keyAccessLock) {
            val store = loadKeyStore()
            readMaterial(store)?.let { material -> return@synchronized material }

            var generated: Boolean = false
            try {
                val generator =
                    KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        ANDROID_KEY_STORE_PROVIDER,
                    )
                generator.initialize(
                    KeyGenParameterSpec
                        .Builder(
                            ANDROID_DEVICE_PROOF_SIGNING_ALIAS,
                            KeyProperties.PURPOSE_SIGN,
                        ).setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE_NAME))
                        .setKeySize(P256_KEY_SIZE_BITS)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .setCertificateSubject(X500Principal(CERTIFICATE_SUBJECT))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(Date(CERTIFICATE_NOT_BEFORE_MILLIS))
                        .setCertificateNotAfter(Date(CERTIFICATE_NOT_AFTER_MILLIS))
                        .build(),
                )
                generator.generateKeyPair()
                generated = true
                checkNotNull(readMaterial(loadKeyStore())) {
                    "AndroidKeyStore did not persist a valid Device Key"
                }
            } catch (error: Exception) {
                if (generated) {
                    runCatching {
                        loadKeyStore().deleteEntry(ANDROID_DEVICE_PROOF_SIGNING_ALIAS)
                    }.onFailure(error::addSuppressed)
                }
                throw error
            }
        }

    override fun currentOrNull(): AndroidSigningKeyMaterial? =
        synchronized(keyAccessLock) {
            readMaterial(loadKeyStore())
        }

    override fun signIfPresent(transcript: ByteArray): ByteArray? =
        synchronized(keyAccessLock) {
            val material = readMaterial(loadKeyStore()) ?: return@synchronized null
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(material.privateKey)
                update(transcript)
                sign()
            }
        }

    override fun delete(): Unit =
        synchronized(keyAccessLock) {
            val store = loadKeyStore()
            if (store.containsAlias(ANDROID_DEVICE_PROOF_SIGNING_ALIAS)) {
                store.deleteEntry(ANDROID_DEVICE_PROOF_SIGNING_ALIAS)
            }
            check(!store.containsAlias(ANDROID_DEVICE_PROOF_SIGNING_ALIAS)) {
                "AndroidKeyStore Device Key deletion could not be confirmed"
            }
        }

    private fun readMaterial(store: KeyStore): AndroidSigningKeyMaterial? {
        if (!store.containsAlias(ANDROID_DEVICE_PROOF_SIGNING_ALIAS)) {
            return null
        }
        val entry = store.getEntry(ANDROID_DEVICE_PROOF_SIGNING_ALIAS, null)
        check(entry is KeyStore.PrivateKeyEntry) {
            "AndroidKeyStore Device Key entry has an unexpected type"
        }
        val privateKey = entry.privateKey
        check(privateKey.algorithm.equals(KeyProperties.KEY_ALGORITHM_EC, ignoreCase = true)) {
            "AndroidKeyStore Device Key is not EC"
        }
        check(privateKey.format == null && privateKey.encoded == null) {
            "AndroidKeyStore Device Key is unexpectedly exportable"
        }
        val publicKey = entry.certificate.publicKey
        AndroidP256Encoding.publicKeyToX963(publicKey)
        return AndroidSigningKeyMaterial(privateKey, publicKey)
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER).apply {
            load(null)
        }

    private companion object {
        const val ANDROID_DEVICE_PROOF_SIGNING_ALIAS =
            "rotki_companion_device_proof_signing_v1"
        const val ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val P256_CURVE_NAME = "secp256r1"
        const val P256_KEY_SIZE_BITS = 256
        const val CERTIFICATE_SUBJECT = "CN=rotki Companion Device Key"
        const val CERTIFICATE_NOT_BEFORE_MILLIS = 0L
        const val CERTIFICATE_NOT_AFTER_MILLIS = 4_102_444_800_000L
        val keyAccessLock: Any = Any()
    }
}

/** Strict Android/JCA encodings at the Companion Protocol P-256 boundary. */
private object AndroidP256Encoding {
    private const val DER_SEQUENCE = 0x30
    private const val DER_INTEGER = 0x02
    private const val P256_COMPONENT_BYTES = 32
    private const val P256_PUBLIC_KEY_BYTES = 65
    private const val MAX_P256_DER_SIGNATURE_BYTES = 72

    private val fieldPrime =
        BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
            16,
        )
    private val curveA =
        BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",
            16,
        )
    private val curveB =
        BigInteger(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
            16,
        )
    private val generatorX =
        BigInteger(
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
            16,
        )
    private val generatorY =
        BigInteger(
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
            16,
        )
    private val p256Order =
        BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
            16,
        )

    fun publicKeyToX963(publicKey: PublicKey): ByteArray {
        val ecPublicKey =
            publicKey as? ECPublicKey
                ?: throw IllegalArgumentException("Expected an EC public key")
        requireP256(ecPublicKey.params)

        val point = ecPublicKey.w
        require(point.affineX != null && point.affineY != null) { "P-256 point is at infinity" }
        val x = checkNotNull(point.affineX)
        val y = checkNotNull(point.affineY)
        require(x.signum() >= 0 && x < fieldPrime) { "P-256 X coordinate is out of range" }
        require(y.signum() >= 0 && y < fieldPrime) { "P-256 Y coordinate is out of range" }
        require(y.modPow(BigInteger.valueOf(2), fieldPrime) == curveEquation(x)) {
            "Public key is not on P-256"
        }

        return ByteArray(P256_PUBLIC_KEY_BYTES).also { encoded ->
            encoded[0] = 0x04
            fixedWidthUnsigned(x).copyInto(encoded, destinationOffset = 1)
            fixedWidthUnsigned(y).copyInto(encoded, destinationOffset = 1 + P256_COMPONENT_BYTES)
        }
    }

    /** Strict Android/JCA conversion to the Companion Protocol P-256 signature encoding. */
    fun derEcdsaToP1363(derSignature: ByteArray): ByteArray {
        require(derSignature.size in 8..MAX_P256_DER_SIGNATURE_BYTES) {
            "ECDSA DER signature has an invalid length"
        }
        val outer = DerReader(derSignature)
        val sequence = outer.readElement(DER_SEQUENCE)
        require(outer.isExhausted) { "Trailing data after ECDSA signature sequence" }

        val components = DerReader(sequence)
        val r = components.readPositiveP256Integer()
        val s = components.readPositiveP256Integer()
        require(components.isExhausted) { "ECDSA signature must contain exactly r and s" }
        return r + s
    }

    fun p1363ToDerEcdsa(p1363Signature: ByteArray): ByteArray {
        require(p1363Signature.size == P256_COMPONENT_BYTES * 2) {
            "P-256 P1363 signature must contain exactly 64 bytes"
        }
        val r = p1363Signature.copyOfRange(0, P256_COMPONENT_BYTES)
        val s = p1363Signature.copyOfRange(P256_COMPONENT_BYTES, p1363Signature.size)
        validateP256Scalar(r)
        validateP256Scalar(s)

        val encodedR = encodeDerInteger(r)
        val encodedS = encodeDerInteger(s)
        val body =
            byteArrayOf(DER_INTEGER.toByte(), encodedR.size.toByte()) + encodedR +
                byteArrayOf(DER_INTEGER.toByte(), encodedS.size.toByte()) + encodedS
        return byteArrayOf(DER_SEQUENCE.toByte(), body.size.toByte()) + body
    }

    private fun requireP256(parameters: ECParameterSpec) {
        val field =
            parameters.curve.field as? ECFieldFp
                ?: throw IllegalArgumentException("Expected a prime-field EC key")
        require(field.p == fieldPrime) { "Expected the NIST P-256 field" }
        require(parameters.curve.a == curveA && parameters.curve.b == curveB) {
            "Expected the NIST P-256 curve"
        }
        require(
            parameters.generator.affineX == generatorX &&
                parameters.generator.affineY == generatorY,
        ) { "Expected the NIST P-256 generator" }
        require(parameters.order == p256Order && parameters.cofactor == 1) {
            "Expected the NIST P-256 group"
        }
    }

    private fun curveEquation(x: BigInteger): BigInteger =
        x
            .modPow(BigInteger.valueOf(3), fieldPrime)
            .add(curveA.multiply(x))
            .add(curveB)
            .mod(fieldPrime)

    private fun fixedWidthUnsigned(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val magnitude =
            if (encoded.size > 1 && encoded.first() == 0.toByte()) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
        require(magnitude.size <= P256_COMPONENT_BYTES) { "P-256 integer exceeds 32 bytes" }
        return ByteArray(P256_COMPONENT_BYTES - magnitude.size) + magnitude
    }

    private fun validateP256Scalar(component: ByteArray) {
        val value = BigInteger(1, component)
        require(value.signum() > 0 && value < p256Order) {
            "ECDSA component is outside [1, n)"
        }
    }

    private fun encodeDerInteger(fixed: ByteArray): ByteArray {
        val firstNonZero = fixed.indexOfFirst { byte -> byte != 0.toByte() }
        val magnitude = fixed.copyOfRange(firstNonZero, fixed.size)
        return if (magnitude.first().toInt() and 0x80 != 0) {
            byteArrayOf(0) + magnitude
        } else {
            magnitude
        }
    }

    private class DerReader(
        private val input: ByteArray,
    ) {
        private var offset: Int = 0

        val isExhausted: Boolean
            get() = offset == input.size

        fun readElement(expectedTag: Int): ByteArray {
            require(offset < input.size) { "Missing DER tag" }
            val tag = input[offset++].toInt() and 0xff
            require(tag == expectedTag) { "Unexpected DER tag" }
            val length = readLength()
            require(length <= input.size - offset) { "Truncated DER element" }
            return input.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readPositiveP256Integer(): ByteArray {
            val encoded = readElement(DER_INTEGER)
            require(encoded.size in 1..P256_COMPONENT_BYTES + 1) {
                "ECDSA component exceeds P-256 width"
            }
            require(encoded.first().toInt() and 0x80 == 0) { "Negative DER INTEGER" }
            if (encoded.size > 1 && encoded.first() == 0.toByte()) {
                require(encoded[1].toInt() and 0x80 != 0) {
                    "Redundant DER INTEGER sign byte"
                }
            }

            val magnitude =
                if (encoded.size > 1 && encoded.first() == 0.toByte()) {
                    encoded.copyOfRange(1, encoded.size)
                } else {
                    encoded
                }
            require(magnitude.size <= P256_COMPONENT_BYTES) {
                "ECDSA component exceeds P-256 width"
            }
            val fixed = ByteArray(P256_COMPONENT_BYTES - magnitude.size) + magnitude
            validateP256Scalar(fixed)
            return fixed
        }

        private fun readLength(): Int {
            require(offset < input.size) { "Missing DER length" }
            val length = input[offset++].toInt() and 0xff
            require(length < 0x80) { "Non-minimal or unsupported DER length" }
            return length
        }
    }
}
