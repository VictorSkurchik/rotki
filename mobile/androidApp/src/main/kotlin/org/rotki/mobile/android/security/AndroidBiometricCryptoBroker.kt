package org.rotki.mobile.android.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

internal sealed interface AndroidBiometricCryptoOutcome {
    class Authorized(val cipher: Cipher) : AndroidBiometricCryptoOutcome {
        override fun toString(): String = "Authorized(redacted)"
    }

    data object Cancelled : AndroidBiometricCryptoOutcome

    data object PermanentlyInvalidated : AndroidBiometricCryptoOutcome

    data object Unavailable : AndroidBiometricCryptoOutcome
}

internal class AndroidBiometricPromptCopy(
    val title: String,
    val subtitle: String,
    val cancel: String,
) {
    override fun toString(): String = "AndroidBiometricPromptCopy(redacted)"
}

internal interface BiometricCryptoBroker {
    suspend fun authorize(cipher: Cipher): AndroidBiometricCryptoOutcome

    fun cancelPending()
}

/**
 * Authenticates exactly one cipher operation with BIOMETRIC_STRONG and no credential fallback.
 * Epoch checks make cancellation and late framework callbacks unable to authorize later work.
 */
internal class AndroidBiometricCryptoBroker(
    private val activity: FragmentActivity,
    promptCopy: AndroidBiometricPromptCopy,
) : BiometricCryptoBroker {
    private val epoch: AtomicLong = AtomicLong(0)
    private val stateLock: Any = Any()
    private var pendingOperation: PendingOperation? = null
    private val promptInfo: BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(promptCopy.title)
        .setSubtitle(promptCopy.subtitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(promptCopy.cancel)
        .setConfirmationRequired(false)
        .build()

    override suspend fun authorize(cipher: Cipher): AndroidBiometricCryptoOutcome =
        suspendCancellableCoroutine { continuation ->
            val operationEpoch = epoch.incrementAndGet()
            val prior = synchronized(stateLock) {
                pendingOperation.also {
                    pendingOperation = PendingOperation(operationEpoch, continuation)
                }
            }
            prior?.cancelAndResume()
            continuation.invokeOnCancellation {
                cancelEpoch(operationEpoch)
            }
            activity.runOnUiThread {
                try {
                    if (activity.isFinishing || activity.isDestroyed) {
                        takeIfCurrent(operationEpoch)?.continuation?.resumeIfActive(
                            AndroidBiometricCryptoOutcome.Unavailable,
                        )
                        return@runOnUiThread
                    }
                    val prompt = createPrompt(operationEpoch, cipher)
                    val shouldAuthenticate = synchronized(stateLock) {
                        val pending = pendingOperation
                        if (pending?.epoch == operationEpoch && continuation.isActive) {
                            pending.prompt = prompt
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldAuthenticate) {
                        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
                    } else {
                        prompt.cancelAuthentication()
                    }
                } catch (_: RuntimeException) {
                    takeIfCurrent(operationEpoch)?.continuation?.resumeIfActive(
                        AndroidBiometricCryptoOutcome.Unavailable,
                    )
                }
            }
        }

    override fun cancelPending(): Unit {
        epoch.incrementAndGet()
        val pending = synchronized(stateLock) {
            pendingOperation.also { pendingOperation = null }
        }
        pending?.cancelAndResume()
    }

    private fun cancelEpoch(operationEpoch: Long): Unit {
        val pending = synchronized(stateLock) {
            if (pendingOperation?.epoch == operationEpoch) {
                epoch.incrementAndGet()
                pendingOperation.also { pendingOperation = null }
            } else {
                null
            }
        }
        pending?.prompt?.cancelOnMainThread()
    }

    private fun takeIfCurrent(operationEpoch: Long): PendingOperation? = synchronized(stateLock) {
        val pending = pendingOperation
        if (pending?.epoch != operationEpoch) return@synchronized null
        epoch.incrementAndGet()
        pendingOperation = null
        pending
    }

    private fun createPrompt(operationEpoch: Long, cipher: Cipher): BiometricPrompt =
        BiometricPrompt(
            activity,
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ): Unit {
                    val pending = takeIfCurrent(operationEpoch) ?: return
                    val authorizedCipher = result.cryptoObject?.cipher
                    val outcome = if (
                        result.authenticationType ==
                        BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL ||
                        authorizedCipher == null || authorizedCipher !== cipher
                    ) {
                        AndroidBiometricCryptoOutcome.Unavailable
                    } else {
                        AndroidBiometricCryptoOutcome.Authorized(authorizedCipher)
                    }
                    pending.continuation.resumeIfActive(outcome)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ): Unit {
                    val pending = takeIfCurrent(operationEpoch) ?: return
                    val outcome = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> AndroidBiometricCryptoOutcome.Cancelled
                        BiometricPrompt.ERROR_NO_BIOMETRICS ->
                            AndroidBiometricCryptoOutcome.PermanentlyInvalidated
                        else -> AndroidBiometricCryptoOutcome.Unavailable
                    }
                    pending.continuation.resumeIfActive(outcome)
                }
            },
        )

    private fun CancellableContinuation<AndroidBiometricCryptoOutcome>.resumeIfActive(
        outcome: AndroidBiometricCryptoOutcome,
    ): Unit {
        if (!isActive) return
        try {
            resume(outcome)
        } catch (_: IllegalStateException) {
            // Structured cancellation or another terminal callback won the race.
        }
    }

    private fun PendingOperation.cancelAndResume(): Unit {
        prompt?.cancelOnMainThread()
        continuation.resumeIfActive(AndroidBiometricCryptoOutcome.Cancelled)
    }

    private fun BiometricPrompt.cancelOnMainThread(): Unit {
        activity.runOnUiThread {
            runCatching { cancelAuthentication() }
        }
    }

    private class PendingOperation(
        val epoch: Long,
        val continuation: CancellableContinuation<AndroidBiometricCryptoOutcome>,
    ) {
        var prompt: BiometricPrompt? = null

        override fun toString(): String = "PendingOperation(redacted)"
    }
}
