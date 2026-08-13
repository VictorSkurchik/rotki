package org.rotki.mobile.android.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import android.security.keystore.KeyInfo
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

internal const val ANDROID_SNAPSHOT_KEY_ALIAS: String =
    "rotki_companion_snapshot_encryption_v1"

internal sealed interface SnapshotCipherPreparationOutcome {
    class Prepared(val cipher: Cipher) : SnapshotCipherPreparationOutcome {
        override fun toString(): String = "Prepared(redacted)"
    }

    data object PairingRequired : SnapshotCipherPreparationOutcome

    data object AuthenticationUnavailable : SnapshotCipherPreparationOutcome

    data object PermanentlyInvalidated : SnapshotCipherPreparationOutcome

    data object Unavailable : SnapshotCipherPreparationOutcome
}

internal enum class SnapshotKeyPresence {
    Missing,
    Present,
    PermanentlyInvalidated,
    Unavailable,
}

internal data class SnapshotKeyPolicy(
    val opaque: Boolean,
    val keySize: Int,
    val origin: Int,
    val purposes: Int,
    val blockModes: Set<String>,
    val encryptionPaddings: Set<String>,
    val userAuthenticationRequired: Boolean,
    val authenticationValiditySeconds: Int,
    val invalidatedByBiometricEnrollment: Boolean,
    val authenticationType: Int?,
)

internal interface SnapshotKeyStore {
    fun presence(): SnapshotKeyPresence

    fun prepareEncryptCipher(): SnapshotCipherPreparationOutcome

    fun prepareDecryptCipher(initializationVector: ByteArray): SnapshotCipherPreparationOutcome

    fun delete(): Boolean
}

/** Per-operation biometric-only AES-256-GCM key in AndroidKeyStore. */
internal class AndroidSnapshotKeyStore(
    context: Context,
    private val biometricAvailability: () -> Int = {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        )
    },
) : SnapshotKeyStore {
    private val expectationFile: AtomicFile = AtomicFile(
        File(context.noBackupFilesDir, EXPECTATION_FILE_NAME),
    )

    override fun presence(): SnapshotKeyPresence = synchronized(keyAccessLock) {
        val expectation = readExpectation()
        if (expectation == KeyExpectation.Unavailable) {
            return@synchronized SnapshotKeyPresence.Unavailable
        }
        if (expectation == KeyExpectation.Corrupt) {
            return@synchronized SnapshotKeyPresence.PermanentlyInvalidated
        }
        val keyPresent = try {
            currentKeyOrNull() != null
        } catch (_: Exception) {
            return@synchronized SnapshotKeyPresence.Unavailable
        }
        when {
            keyPresent != (expectation == KeyExpectation.Present) ->
                SnapshotKeyPresence.PermanentlyInvalidated
            !keyPresent -> SnapshotKeyPresence.Missing
            biometricAvailability() == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                SnapshotKeyPresence.PermanentlyInvalidated
            else -> SnapshotKeyPresence.Present
        }
    }

    override fun prepareEncryptCipher(): SnapshotCipherPreparationOutcome = prepareCipher { key ->
        Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    override fun prepareDecryptCipher(
        initializationVector: ByteArray,
    ): SnapshotCipherPreparationOutcome {
        if (initializationVector.size != SnapshotEnvelopeCodec.GCM_IV_BYTES) {
            return SnapshotCipherPreparationOutcome.Unavailable
        }
        val frozenInitializationVector = initializationVector.copyOf()
        return prepareCipher { key ->
            Cipher.getInstance(AES_TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, frozenInitializationVector),
                )
            }
        }
    }

    override fun delete(): Boolean = synchronized(keyAccessLock) {
        try {
            val store = loadKeyStore()
            if (store.containsAlias(ANDROID_SNAPSHOT_KEY_ALIAS)) {
                store.deleteEntry(ANDROID_SNAPSHOT_KEY_ALIAS)
            }
            expectationFile.delete()
            !store.containsAlias(ANDROID_SNAPSHOT_KEY_ALIAS) &&
                readExpectation() == KeyExpectation.Missing
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareCipher(
        initialize: (SecretKey) -> Cipher,
    ): SnapshotCipherPreparationOutcome = synchronized(keyAccessLock) {
        val expectation = readExpectation()
        if (expectation == KeyExpectation.Unavailable) {
            return@synchronized SnapshotCipherPreparationOutcome.Unavailable
        }
        if (expectation == KeyExpectation.Corrupt) {
            return@synchronized SnapshotCipherPreparationOutcome.PermanentlyInvalidated
        }
        val key = try {
            currentKeyOrNull()
        } catch (error: Exception) {
            return@synchronized error.toPreparationOutcome()
        } ?: return@synchronized if (expectation == KeyExpectation.Present) {
            SnapshotCipherPreparationOutcome.PermanentlyInvalidated
        } else {
            SnapshotCipherPreparationOutcome.PairingRequired
        }

        if (expectation == KeyExpectation.Missing) {
            return@synchronized SnapshotCipherPreparationOutcome.PermanentlyInvalidated
        }

        val availability = biometricAvailability()
        if (availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            return@synchronized SnapshotCipherPreparationOutcome.PermanentlyInvalidated
        }
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            return@synchronized SnapshotCipherPreparationOutcome.AuthenticationUnavailable
        }

        try {
            SnapshotCipherPreparationOutcome.Prepared(initialize(key))
        } catch (error: Exception) {
            error.toPreparationOutcome()
        }
    }

    private fun currentKeyOrNull(): SecretKey? {
        val store = loadKeyStore()
        if (!store.containsAlias(ANDROID_SNAPSHOT_KEY_ALIAS)) return null
        val key = store.getKey(ANDROID_SNAPSHOT_KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Snapshot key has an unexpected type")
        check(key.format == null && key.encoded == null) {
            "AndroidKeyStore snapshot key is unexpectedly exportable"
        }
        return key
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE_PROVIDER).apply {
        load(null)
    }

    internal fun createForPairing(): Boolean = synchronized(keyAccessLock) {
        if (biometricAvailability() != BiometricManager.BIOMETRIC_SUCCESS) return@synchronized false
        try {
            if (currentKeyOrNull() != null && readExpectation() == KeyExpectation.Present) {
                return@synchronized true
            }
            deleteKeyAndExpectation()
            val builder = KeyGenParameterSpec.Builder(
                ANDROID_SNAPSHOT_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(AES_KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE_PROVIDER)
                .run {
                    init(builder.build())
                    generateKey()
                }
            if (!writeExpectation()) {
                deleteKeyAndExpectation()
                return@synchronized false
            }
            currentKeyOrNull() != null && readExpectation() == KeyExpectation.Present
        } catch (_: Exception) {
            runCatching { deleteKeyAndExpectation() }
            false
        }
    }

    internal fun policyForTest(): SnapshotKeyPolicy = synchronized(keyAccessLock) {
        val key = checkNotNull(currentKeyOrNull())
        // Java's SecretKeyFactory boundary erases the concrete KeySpec return type.
        val info = checkNotNull(
            SecretKeyFactory.getInstance(
                key.algorithm,
                ANDROID_KEY_STORE_PROVIDER,
            ).getKeySpec(key, KeyInfo::class.java) as? KeyInfo,
        )
        SnapshotKeyPolicy(
            opaque = key.format == null && key.encoded == null,
            keySize = info.keySize,
            origin = info.origin,
            purposes = info.purposes,
            blockModes = info.blockModes.toSet(),
            encryptionPaddings = info.encryptionPaddings.toSet(),
            userAuthenticationRequired = info.isUserAuthenticationRequired,
            authenticationValiditySeconds = info.userAuthenticationValidityDurationSeconds,
            invalidatedByBiometricEnrollment = info.isInvalidatedByBiometricEnrollment,
            authenticationType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.userAuthenticationType
            } else {
                null
            },
        )
    }

    private fun writeExpectation(): Boolean {
        val output = try {
            expectationFile.startWrite()
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        return try {
            output.write(EXPECTATION_BYTES)
            expectationFile.finishWrite(output)
            true
        } catch (_: IOException) {
            expectationFile.failWrite(output)
            false
        } catch (_: SecurityException) {
            expectationFile.failWrite(output)
            false
        }
    }

    private fun readExpectation(): KeyExpectation = try {
        val bytes = expectationFile.openRead().use { input ->
            val encoded = ByteArray(EXPECTATION_BYTES.size + 1)
            var count = 0
            while (count < encoded.size) {
                val read = input.read(encoded, count, encoded.size - count)
                if (read == -1) break
                if (read == 0) continue
                count += read
            }
            if (count != EXPECTATION_BYTES.size) {
                return KeyExpectation.Corrupt
            }
            encoded
        }
        if (bytes.copyOf(EXPECTATION_BYTES.size).contentEquals(EXPECTATION_BYTES)) {
            KeyExpectation.Present
        } else {
            KeyExpectation.Corrupt
        }
    } catch (_: FileNotFoundException) {
        KeyExpectation.Missing
    } catch (_: IOException) {
        KeyExpectation.Unavailable
    } catch (_: SecurityException) {
        KeyExpectation.Unavailable
    }

    private fun deleteKeyAndExpectation(): Unit {
        val store = loadKeyStore()
        if (store.containsAlias(ANDROID_SNAPSHOT_KEY_ALIAS)) {
            store.deleteEntry(ANDROID_SNAPSHOT_KEY_ALIAS)
        }
        expectationFile.delete()
    }

    private fun Throwable.toPreparationOutcome(): SnapshotCipherPreparationOutcome =
        if (containsPermanentInvalidation()) {
            SnapshotCipherPreparationOutcome.PermanentlyInvalidated
        } else {
            SnapshotCipherPreparationOutcome.Unavailable
        }

    private fun Throwable.containsPermanentInvalidation(): Boolean {
        var candidate: Throwable? = this
        while (candidate != null) {
            if (candidate is KeyPermanentlyInvalidatedException) return true
            if (candidate is InvalidKeyException &&
                candidate.cause is KeyPermanentlyInvalidatedException
            ) {
                return true
            }
            candidate = candidate.cause
        }
        return false
    }

    private companion object {
        const val ANDROID_KEY_STORE_PROVIDER: String = "AndroidKeyStore"
        const val AES_TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val AES_KEY_BITS: Int = 256
        const val GCM_TAG_BITS: Int = 128
        const val EXPECTATION_FILE_NAME: String = "snapshot-key.expected"
        val EXPECTATION_BYTES: ByteArray =
            "rotki-snapshot-key/v1".toByteArray(Charsets.US_ASCII)
        val keyAccessLock: Any = Any()
    }

    private enum class KeyExpectation {
        Missing,
        Present,
        Corrupt,
        Unavailable,
    }
}
