package org.rotki.mobile.android.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.SecureSnapshotDeleteOutcome
import org.rotki.mobile.core.ports.SecureSnapshotReadOutcome
import org.rotki.mobile.core.ports.SecureSnapshotStore
import org.rotki.mobile.core.ports.SecureSnapshotWriteOutcome
import java.security.InvalidKeyException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.AEADBadTagException

internal class AndroidSecureSnapshotStore(
    context: Context,
    private val keyStore: SnapshotKeyStore,
    private val biometricBroker: BiometricCryptoBroker,
    private val materialCleaner: LocalMaterialCleaner,
    private val file: AtomicSnapshotFile = AtomicSnapshotFile(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecureSnapshotStore {
    private val operationMutex: Mutex = Mutex()
    private val plaintextLock: Any = Any()
    private val readPlaintextBarrierLock: Any = Any()
    private val commitLock: Any = Any()
    private val activePlaintext: MutableSet<SecureSnapshotReadOutcome.Unlocked> = mutableSetOf()
    private val activeWritePlaintext: MutableSet<ByteArray> = mutableSetOf()
    private val lifecycleEpoch: AtomicLong = AtomicLong(0)

    override suspend fun readAfterDeviceAuthentication(): SecureSnapshotReadOutcome =
        operationMutex.withLock {
            readLocked()
        }

    // Android crypto providers expose permanent invalidation through several exception wrappers.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readLocked(): SecureSnapshotReadOutcome {
        val operationEpoch = currentLifecycleEpoch()
        val envelope =
            when (val stored = withContext(dispatcher) { file.read() }) {
                AtomicSnapshotReadOutcome.Missing -> {
                    return when (
                        withContext(dispatcher) { keyStore.presence() }
                    ) {
                        SnapshotKeyPresence.Missing,
                        SnapshotKeyPresence.Present,
                        -> SecureSnapshotReadOutcome.Missing

                        SnapshotKeyPresence.PermanentlyInvalidated -> pairingRequiredReadAfterCleanup()

                        SnapshotKeyPresence.Unavailable -> SecureSnapshotReadOutcome.Unavailable
                    }
                }

                AtomicSnapshotReadOutcome.Corrupt -> {
                    return SecureSnapshotReadOutcome.Corrupt
                }

                AtomicSnapshotReadOutcome.Unavailable -> {
                    return SecureSnapshotReadOutcome.Unavailable
                }

                is AtomicSnapshotReadOutcome.Present -> {
                    try {
                        SnapshotEnvelopeCodec.decode(stored.bytesCopy())
                    } catch (_: IllegalArgumentException) {
                        return SecureSnapshotReadOutcome.Corrupt
                    }
                }
            }
        val prepared =
            withContext(dispatcher) {
                keyStore.prepareDecryptCipher(envelope.initializationVectorCopy())
            }
        if (operationEpoch != currentLifecycleEpoch()) {
            return SecureSnapshotReadOutcome.DeviceAuthenticationCancelled
        }
        val cipher =
            when (prepared) {
                SnapshotCipherPreparationOutcome.PairingRequired -> {
                    return pairingRequiredReadAfterCleanup()
                }

                SnapshotCipherPreparationOutcome.AuthenticationUnavailable -> {
                    return SecureSnapshotReadOutcome.DeviceAuthenticationUnavailable
                }

                SnapshotCipherPreparationOutcome.PermanentlyInvalidated -> {
                    return pairingRequiredReadAfterCleanup()
                }

                SnapshotCipherPreparationOutcome.Unavailable -> {
                    return SecureSnapshotReadOutcome.Unavailable
                }

                is SnapshotCipherPreparationOutcome.Prepared -> {
                    prepared.cipher
                }
            }
        val authorized =
            when (val outcome = biometricBroker.authorize(cipher)) {
                AndroidBiometricCryptoOutcome.Cancelled -> {
                    return SecureSnapshotReadOutcome.DeviceAuthenticationCancelled
                }

                AndroidBiometricCryptoOutcome.PermanentlyInvalidated -> {
                    return pairingRequiredReadAfterCleanup()
                }

                AndroidBiometricCryptoOutcome.Unavailable -> {
                    return SecureSnapshotReadOutcome.DeviceAuthenticationUnavailable
                }

                is AndroidBiometricCryptoOutcome.Authorized -> {
                    outcome.cipher
                }
            }

        val ciphertext = envelope.ciphertextAndTagCopy()
        return try {
            withContext(dispatcher) {
                synchronized(readPlaintextBarrierLock) {
                    if (operationEpoch != currentLifecycleEpoch()) {
                        return@synchronized SecureSnapshotReadOutcome
                            .DeviceAuthenticationCancelled
                    }
                    val plaintext =
                        authorized.run {
                            updateAAD(SnapshotEnvelopeCodec.authenticatedDomainCopy())
                            doFinal(ciphertext)
                        }
                    try {
                        if (operationEpoch != currentLifecycleEpoch()) {
                            SecureSnapshotReadOutcome.DeviceAuthenticationCancelled
                        } else {
                            SecureSnapshotReadOutcome.Unlocked(plaintext).also { unlocked ->
                                synchronized(plaintextLock) {
                                    activePlaintext += unlocked
                                }
                            }
                        }
                    } finally {
                        plaintext.fill(0)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: AEADBadTagException) {
            return SecureSnapshotReadOutcome.Corrupt
        } catch (error: Exception) {
            if (error.containsPermanentInvalidation()) {
                return pairingRequiredReadAfterCleanup()
            }
            return SecureSnapshotReadOutcome.Unavailable
        }
    }

    override suspend fun replace(document: ByteArray): SecureSnapshotWriteOutcome =
        operationMutex.withLock {
            replaceLocked(document)
        }

    // Android crypto providers expose permanent invalidation through several exception wrappers.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun replaceLocked(document: ByteArray): SecureSnapshotWriteOutcome {
        if (!SnapshotEnvelopeCodec.isWithinDefensivePlaintextBound(document.size)) {
            return SecureSnapshotWriteOutcome.Unavailable
        }
        val operationEpoch = currentLifecycleEpoch()
        val plaintext = document.copyOf()
        val writeRegistered =
            synchronized(plaintextLock) {
                if (operationEpoch == currentLifecycleEpoch()) {
                    activeWritePlaintext += plaintext
                    true
                } else {
                    false
                }
            }
        if (!writeRegistered) {
            plaintext.fill(0)
            return SecureSnapshotWriteOutcome.DeviceAuthenticationRequired
        }
        try {
            val prepared = withContext(dispatcher) { keyStore.prepareEncryptCipher() }
            if (operationEpoch != currentLifecycleEpoch()) {
                return SecureSnapshotWriteOutcome.DeviceAuthenticationRequired
            }
            val cipher =
                when (prepared) {
                    SnapshotCipherPreparationOutcome.PairingRequired -> {
                        return pairingRequiredWriteAfterCleanup()
                    }

                    SnapshotCipherPreparationOutcome.AuthenticationUnavailable -> {
                        return SecureSnapshotWriteOutcome.DeviceAuthenticationRequired
                    }

                    SnapshotCipherPreparationOutcome.PermanentlyInvalidated -> {
                        return pairingRequiredWriteAfterCleanup()
                    }

                    SnapshotCipherPreparationOutcome.Unavailable -> {
                        return SecureSnapshotWriteOutcome.Unavailable
                    }

                    is SnapshotCipherPreparationOutcome.Prepared -> {
                        prepared.cipher
                    }
                }
            val authorized =
                when (val outcome = biometricBroker.authorize(cipher)) {
                    AndroidBiometricCryptoOutcome.Cancelled,
                    AndroidBiometricCryptoOutcome.Unavailable,
                    -> {
                        return SecureSnapshotWriteOutcome.DeviceAuthenticationRequired
                    }

                    AndroidBiometricCryptoOutcome.PermanentlyInvalidated -> {
                        return pairingRequiredWriteAfterCleanup()
                    }

                    is AndroidBiometricCryptoOutcome.Authorized -> {
                        outcome.cipher
                    }
                }
            val envelope =
                try {
                    withContext(dispatcher) {
                        authorized.updateAAD(SnapshotEnvelopeCodec.authenticatedDomainCopy())
                        val ciphertext = authorized.doFinal(plaintext)
                        check(authorized.iv.size == SnapshotEnvelopeCodec.GCM_IV_BYTES) {
                            "Android provider returned an invalid AES-GCM IV"
                        }
                        SnapshotEnvelope(authorized.iv, ciphertext)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error.containsPermanentInvalidation()) {
                        return pairingRequiredWriteAfterCleanup()
                    }
                    return SecureSnapshotWriteOutcome.Unavailable
                }
            val encoded = SnapshotEnvelopeCodec.encode(envelope)
            val stored =
                withContext(dispatcher) {
                    replaceIfCurrent(encoded, operationEpoch)
                }
            return if (stored) {
                SecureSnapshotWriteOutcome.Stored
            } else {
                if (operationEpoch != currentLifecycleEpoch()) {
                    SecureSnapshotWriteOutcome.DeviceAuthenticationRequired
                } else {
                    SecureSnapshotWriteOutcome.Unavailable
                }
            }
        } finally {
            synchronized(plaintextLock) {
                activeWritePlaintext -= plaintext
            }
            plaintext.fill(0)
        }
    }

    override suspend fun delete(): SecureSnapshotDeleteOutcome =
        operationMutex.withLock {
            discardPlaintext()
            if (materialCleaner.destroyAll()) {
                SecureSnapshotDeleteOutcome.Deleted
            } else {
                SecureSnapshotDeleteOutcome.Unavailable
            }
        }

    override fun discardPlaintext() {
        lifecycleEpoch.incrementAndGet()
        biometricBroker.cancelPending()
        synchronized(readPlaintextBarrierLock) {
            // No authorized read can create plaintext after this barrier returns.
        }
        val (handles, writeBuffers) =
            synchronized(plaintextLock) {
                val unlocked = activePlaintext.toList()
                val writes = activeWritePlaintext.toList()
                activePlaintext.clear()
                activeWritePlaintext.clear()
                unlocked to writes
            }
        handles.forEach(SecureSnapshotReadOutcome.Unlocked::discard)
        writeBuffers.forEach { plaintext -> plaintext.fill(0) }
        synchronized(commitLock) {
            // Cancellation barrier: an in-flight encrypted commit has observed the new epoch.
        }
    }

    private suspend fun destroyInaccessibleMaterial(): Boolean {
        discardPlaintext()
        return materialCleaner.destroyAll()
    }

    private suspend fun pairingRequiredReadAfterCleanup(): SecureSnapshotReadOutcome =
        if (destroyInaccessibleMaterial()) {
            SecureSnapshotReadOutcome.PairingRequired
        } else {
            SecureSnapshotReadOutcome.Unavailable
        }

    private suspend fun pairingRequiredWriteAfterCleanup(): SecureSnapshotWriteOutcome =
        if (destroyInaccessibleMaterial()) {
            SecureSnapshotWriteOutcome.PairingRequired
        } else {
            SecureSnapshotWriteOutcome.Unavailable
        }

    private fun currentLifecycleEpoch(): Long = lifecycleEpoch.get()

    private fun replaceIfCurrent(
        encoded: ByteArray,
        operationEpoch: Long,
    ): Boolean =
        synchronized(commitLock) {
            if (operationEpoch != currentLifecycleEpoch()) return@synchronized false
            val previous = file.read()
            if (!file.replace(encoded)) return@synchronized false
            if (operationEpoch == currentLifecycleEpoch()) return@synchronized true

            when (previous) {
                AtomicSnapshotReadOutcome.Missing -> file.delete()

                is AtomicSnapshotReadOutcome.Present -> file.replace(previous.bytesCopy())

                AtomicSnapshotReadOutcome.Corrupt,
                AtomicSnapshotReadOutcome.Unavailable,
                -> file.delete()
            }
            false
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
}
