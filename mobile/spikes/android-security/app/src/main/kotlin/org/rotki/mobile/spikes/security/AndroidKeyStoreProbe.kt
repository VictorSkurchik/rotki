package org.rotki.mobile.spikes.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

data class SigningKeyDiagnostics(
    val opaquePrivateKey: Boolean,
    val keySize: Int,
    val origin: Int,
    val purposes: Int,
    val digests: Set<String>,
    val insideSecureHardware: Boolean,
    val securityLevel: Int?,
)

data class AesKeyDiagnostics(
    val opaqueSecretKey: Boolean,
    val keySize: Int,
    val origin: Int,
    val purposes: Int,
    val blockModes: Set<String>,
    val encryptionPaddings: Set<String>,
    val userAuthenticationRequired: Boolean,
    val authenticationValiditySeconds: Int,
    val authenticationType: Int?,
    val invalidatedByBiometricEnrollment: Boolean,
    val authenticationEnforcedBySecureHardware: Boolean,
    val insideSecureHardware: Boolean,
    val securityLevel: Int?,
)

class BiometricUnavailableException(val availability: BiometricAvailability) :
    IllegalStateException("Strong biometric is unavailable: $availability")

class AndroidKeyStoreProbe(private val context: Context) {
    val biometricAvailability: BiometricAvailability
        get() = when (
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            )
        ) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                BiometricAvailability.SECURITY_UPDATE_REQUIRED
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.UNSUPPORTED
            else -> BiometricAvailability.UNKNOWN
        }

    val hasSigningKey: Boolean
        get() = keyStore().containsAlias(SIGNING_ALIAS)

    val hasAesKey: Boolean
        get() = keyStore().containsAlias(AES_ALIAS)

    val hasCompleteKeyMaterial: Boolean
        get() = hasSigningKey && hasAesKey

    fun ensureSigningKey(): KeyPair {
        val store = keyStore()
        if (store.containsAlias(SIGNING_ALIAS)) {
            val privateKey = store.getKey(SIGNING_ALIAS, null) as? PrivateKey
            val publicKey = store.getCertificate(SIGNING_ALIAS)?.publicKey
            if (privateKey != null && publicKey != null) {
                return KeyPair(publicKey, privateKey)
            }
            store.deleteEntry(SIGNING_ALIAS)
        }

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(SIGNING_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=rotki P0.3 Device Key"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(0))
                .setCertificateNotAfter(Date(CERTIFICATE_NOT_AFTER_MILLIS))
                .build(),
        )
        return generator.generateKeyPair()
    }

    fun ensureAesKey(): SecretKey {
        val availability = biometricAvailability
        if (availability != BiometricAvailability.AVAILABLE) {
            throw BiometricUnavailableException(availability)
        }
        secretKeyOrNull()?.let { return it }

        val builder = KeyGenParameterSpec.Builder(
            AES_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(builder.build())
            generateKey()
        }
    }

    fun signingPublicKeySec1(): ByteArray =
        ProtocolEncoding.p256PublicKeyToSec1(requireSigningKeyPair().public)

    /** Signs the full message exactly once; Android returns DER, so the boundary returns P1363. */
    fun signDeviceProof(message: ByteArray): ByteArray = Signature.getInstance(SIGNATURE_ALGORITHM).run {
        initSign(requireSigningKeyPair().private)
        update(message)
        ProtocolEncoding.derEcdsaToP1363(sign())
    }

    fun verifyDeviceProof(message: ByteArray, p1363Signature: ByteArray): Boolean =
        verifyDeviceProof(requireSigningKeyPair().public, message, p1363Signature)

    fun signingKeyDiagnostics(): SigningKeyDiagnostics {
        val privateKey = requireSigningKeyPair().private
        val info = KeyFactory.getInstance(privateKey.algorithm, PROVIDER)
            .getKeySpec(privateKey, KeyInfo::class.java)
        return SigningKeyDiagnostics(
            opaquePrivateKey = privateKey.format == null && privateKey.encoded == null,
            keySize = info.keySize,
            origin = info.origin,
            purposes = info.purposes,
            digests = info.digests.toSet(),
            insideSecureHardware = insideSecureHardware(info),
            securityLevel = securityLevel(info),
        )
    }

    fun aesKeyDiagnostics(): AesKeyDiagnostics {
        val key = requireAesKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return AesKeyDiagnostics(
            opaqueSecretKey = key.format == null && key.encoded == null,
            keySize = info.keySize,
            origin = info.origin,
            purposes = info.purposes,
            blockModes = info.blockModes.toSet(),
            encryptionPaddings = info.encryptionPaddings.toSet(),
            userAuthenticationRequired = info.isUserAuthenticationRequired,
            authenticationValiditySeconds = info.userAuthenticationValidityDurationSeconds,
            authenticationType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.userAuthenticationType
            } else {
                null
            },
            invalidatedByBiometricEnrollment = info.isInvalidatedByBiometricEnrollment,
            authenticationEnforcedBySecureHardware =
                info.isUserAuthenticationRequirementEnforcedBySecureHardware,
            insideSecureHardware = insideSecureHardware(info),
            securityLevel = securityLevel(info),
        )
    }

    fun newEncryptCipher(): Cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, requireAesKey())
    }

    fun newDecryptCipher(blob: EncryptedBlob): Cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, requireAesKey(), GCMParameterSpec(GCM_TAG_BITS, blob.iv))
    }

    fun encryptWithAuthenticatedCipher(cipher: Cipher, plaintext: ByteArray): EncryptedBlob {
        cipher.updateAAD(SNAPSHOT_AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(cipher.iv, ciphertext)
    }

    fun decryptWithAuthenticatedCipher(cipher: Cipher, ciphertext: ByteArray): ByteArray {
        cipher.updateAAD(SNAPSHOT_AAD)
        return cipher.doFinal(ciphertext)
    }

    fun deleteAllKeys() {
        keyStore().run {
            if (containsAlias(AES_ALIAS)) {
                deleteEntry(AES_ALIAS)
            }
            if (containsAlias(SIGNING_ALIAS)) {
                deleteEntry(SIGNING_ALIAS)
            }
        }
    }

    private fun requireSigningKeyPair(): KeyPair {
        val store = keyStore()
        val privateKey = store.getKey(SIGNING_ALIAS, null) as? PrivateKey
        val publicKey = store.getCertificate(SIGNING_ALIAS)?.publicKey
        check(privateKey != null && publicKey != null) { "Signing key has not been created" }
        return KeyPair(publicKey, privateKey)
    }

    private fun requireAesKey(): SecretKey =
        checkNotNull(secretKeyOrNull()) { "Biometric AES key has not been created" }

    private fun secretKeyOrNull(): SecretKey? = keyStore().getKey(AES_ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun insideSecureHardware(info: KeyInfo): Boolean {
        @Suppress("DEPRECATION")
        return info.isInsideSecureHardware
    }

    private fun securityLevel(info: KeyInfo): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        info.securityLevel
    } else {
        null
    }

    companion object {
        const val SIGNING_ALIAS = "rotki_p03_device_signing"
        const val AES_ALIAS = "rotki_p03_snapshot"
        const val PROVIDER = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        private const val CERTIFICATE_NOT_AFTER_MILLIS = 4_102_444_800_000L
        private val SNAPSHOT_AAD = "rotki-companion-snapshot-envelope/v1"
            .toByteArray(Charsets.US_ASCII)

        fun verifyDeviceProof(
            publicKey: PublicKey,
            message: ByteArray,
            p1363Signature: ByteArray,
        ): Boolean = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(message)
            verify(ProtocolEncoding.p1363ToDerEcdsa(p1363Signature))
        }

        fun Throwable.containsKeyPermanentlyInvalidated(): Boolean {
            var current: Throwable? = this
            while (current != null) {
                if (current is KeyPermanentlyInvalidatedException) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
