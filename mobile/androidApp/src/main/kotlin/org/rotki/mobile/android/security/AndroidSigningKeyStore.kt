package org.rotki.mobile.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

internal const val ANDROID_DEVICE_PROOF_SIGNING_ALIAS: String =
    "rotki_companion_device_proof_signing_v1"

internal class AndroidSigningKeyMaterial(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
) {
    override fun toString(): String = "AndroidSigningKeyMaterial(redacted)"
}

internal interface DeviceSigningKeyStore {
    fun createOrCurrent(): AndroidSigningKeyMaterial

    fun currentOrNull(): AndroidSigningKeyMaterial?

    fun signIfPresent(transcript: ByteArray): ByteArray?

    fun delete()
}

/** Persistent non-exportable P-256 Device Key storage backed by AndroidKeyStore. */
internal class AndroidSigningKeyStore : DeviceSigningKeyStore {
    override fun createOrCurrent(): AndroidSigningKeyMaterial = synchronized(keyAccessLock) {
        val store = loadKeyStore()
        readMaterial(store)?.let { material -> return@synchronized material }

        var generated: Boolean = false
        try {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEY_STORE_PROVIDER,
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ANDROID_DEVICE_PROOF_SIGNING_ALIAS,
                    KeyProperties.PURPOSE_SIGN,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE_NAME))
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

    override fun currentOrNull(): AndroidSigningKeyMaterial? = synchronized(keyAccessLock) {
        readMaterial(loadKeyStore())
    }

    override fun signIfPresent(transcript: ByteArray): ByteArray? = synchronized(keyAccessLock) {
        val material = readMaterial(loadKeyStore()) ?: return@synchronized null
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(material.privateKey)
            update(transcript)
            sign()
        }
    }

    override fun delete(): Unit = synchronized(keyAccessLock) {
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

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER).apply {
        load(null)
    }

    private companion object {
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
