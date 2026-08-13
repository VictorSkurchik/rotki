package org.rotki.mobile.spikes.security

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Cipher

class SecuritySpikeActivity : FragmentActivity() {
    private val securityExecutor = Executors.newSingleThreadExecutor()
    private lateinit var keyStoreProbe: AndroidKeyStoreProbe
    private lateinit var blobStore: EncryptedBlobStore
    private lateinit var stateMachine: SecurityStateMachine
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private lateinit var statusText: TextView
    private lateinit var createMaterialButton: Button
    private lateinit var signButton: Button
    private lateinit var encryptButton: Button
    private lateinit var decryptButton: Button
    private var pendingOperation: PendingOperation? = null
    @Volatile
    private var operationEpoch: Long = 0
    private var lastDetail = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyStoreProbe = AndroidKeyStoreProbe(applicationContext)
        blobStore = EncryptedBlobStore(applicationContext)
        stateMachine = SecurityStateMachine(::destroyLocalMaterial)
        biometricPrompt = BiometricPrompt(this, mainExecutor, biometricCallback)
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.cancel))
            .setConfirmationRequired(false)
            .build()
        setContentView(buildContentView())
        refreshEligibility("Initial check")
    }

    override fun onStop() {
        super.onStop()
        if (stateMachine.state == SecurityState.AUTHENTICATING) {
            invalidatePendingOperation()
            biometricPrompt.cancelAuthentication()
            stateMachine.onAuthenticationError(BiometricTerminalError.CANCELED)
            renderState("App left the foreground; the pending biometric operation was canceled")
        }
    }

    override fun onDestroy() {
        securityExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val spacing = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }
        statusText = TextView(this).apply {
            id = R.id.security_status
            text = getString(R.string.status_initial)
            setTextIsSelectable(true)
        }
        content.addView(statusText)
        content.addView(actionButton(R.id.refresh_eligibility, R.string.refresh) {
            refreshEligibility("Eligibility refreshed")
        })
        content.addView(actionButton(R.id.open_enrollment, R.string.open_enrollment) {
            openEnrollmentSettings()
        })
        createMaterialButton = actionButton(R.id.create_material, R.string.create_material) {
            createPairingMaterial()
        }.also(content::addView)
        signButton = actionButton(R.id.sign_transcript, R.string.sign_transcript) {
            signProtocolTranscript()
        }.also(content::addView)
        encryptButton = actionButton(R.id.encrypt_sample, R.string.encrypt_sample) {
            beginEncrypt()
        }.also(content::addView)
        decryptButton = actionButton(R.id.decrypt_sample, R.string.decrypt_sample) {
            beginDecrypt()
        }.also(content::addView)
        content.addView(actionButton(R.id.destroy_material, R.string.destroy_material) {
            invalidatePendingOperation()
            biometricPrompt.cancelAuthentication()
            stateMachine.destroyByUser()
            renderState("Signing key, AES key, and encrypted envelope deleted")
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun actionButton(idValue: Int, textValue: Int, action: () -> Unit) = Button(this).apply {
        id = idValue
        setText(textValue)
        setOnClickListener { action() }
    }

    private fun refreshEligibility(detail: String) {
        val availability = keyStoreProbe.biometricAvailability
        val anyMaterial = keyStoreProbe.hasSigningKey || keyStoreProbe.hasAesKey || blobStore.exists
        val completeKeys = keyStoreProbe.hasCompleteKeyMaterial
        if (availability == BiometricAvailability.AVAILABLE && anyMaterial && !completeKeys) {
            stateMachine.refresh(availability, hasLocalMaterial = true)
            stateMachine.onKeyPermanentlyInvalidated()
            renderState("Incomplete local material was destroyed; a new Pairing is required")
            return
        }
        stateMachine.refresh(availability, anyMaterial)
        renderState(detail)
    }

    private fun createPairingMaterial() {
        if (!stateMachine.canCreatePairingMaterial()) {
            renderState("Pairing remains blocked until a strong biometric is enrolled and available")
            return
        }
        renderState("Creating non-exportable signing and biometric AES keys…")
        securityExecutor.execute {
            try {
                destroyLocalMaterial()
                keyStoreProbe.ensureSigningKey()
                keyStoreProbe.ensureAesKey()
                runOnUiThread {
                    stateMachine.onPairingMaterialCreated()
                    val signing = keyStoreProbe.signingKeyDiagnostics()
                    val aes = keyStoreProbe.aesKeyDiagnostics()
                    renderState(
                        "Created keys: signingOpaque=${signing.opaquePrivateKey}, " +
                            "publicPoint=${keyStoreProbe.signingPublicKeySec1().size} bytes, " +
                            "aesOpaque=${aes.opaqueSecretKey}, authEveryUse=" +
                            "${aes.authenticationValiditySeconds <= 0}",
                    )
                }
            } catch (error: Throwable) {
                // Never leave a half-created key pair behind if the second generation step fails.
                destroyLocalMaterial()
                runOnUiThread { handleCryptoError("Key creation failed", error) }
            }
        }
    }

    private fun signProtocolTranscript() {
        if (stateMachine.state != SecurityState.LOCKED || !keyStoreProbe.hasSigningKey) {
            renderState("Create Pairing material before signing")
            return
        }
        securityExecutor.execute {
            try {
                val transcript = ProofVector.transcript()
                val signature = keyStoreProbe.signDeviceProof(transcript)
                val verified = keyStoreProbe.verifyDeviceProof(transcript, signature)
                val altered = transcript.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
                val alteredRejected = !keyStoreProbe.verifyDeviceProof(altered, signature)
                runOnUiThread {
                    renderState(
                        "Signed exact checked transcript: public=65 bytes, signature=${signature.size} " +
                            "P1363 bytes, verified=$verified, alteredRejected=$alteredRejected",
                    )
                }
            } catch (error: Throwable) {
                runOnUiThread { handleCryptoError("Signing failed", error) }
            }
        }
    }

    private fun beginEncrypt() {
        beginBiometricOperation("Preparing a fresh authenticated encryption") { epoch ->
            PendingOperation.Encrypt(keyStoreProbe.newEncryptCipher(), epoch)
        }
    }

    private fun beginDecrypt() {
        beginBiometricOperation("Preparing a fresh authenticated decryption") { epoch ->
            val blob = checkNotNull(blobStore.read()) { "No encrypted envelope exists" }
            PendingOperation.Decrypt(keyStoreProbe.newDecryptCipher(blob), blob, epoch)
        }
    }

    private fun beginBiometricOperation(
        detail: String,
        prepare: (Long) -> PendingOperation,
    ) {
        if (!stateMachine.beginAuthentication()) {
            renderState("A complete Pairing is required and no other operation may be active")
            return
        }
        val epoch = ++operationEpoch
        renderState(detail)
        securityExecutor.execute {
            try {
                val operation = prepare(epoch)
                runOnUiThread {
                    if (
                        operation.epoch != operationEpoch ||
                        stateMachine.state != SecurityState.AUTHENTICATING ||
                        isFinishing ||
                        isDestroyed
                    ) {
                        return@runOnUiThread
                    }
                    pendingOperation = operation
                    biometricPrompt.authenticate(
                        promptInfo,
                        BiometricPrompt.CryptoObject(operation.cipher),
                    )
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (epoch == operationEpoch) {
                        handleCryptoError("Could not initialize AES-GCM", error)
                    }
                }
            }
        }
    }

    private val biometricCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (result.authenticationType ==
                BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
            ) {
                invalidatePendingOperation()
                stateMachine.onAuthenticationError(BiometricTerminalError.OTHER)
                renderState("Rejected an unexpected device-credential result")
                return
            }
            val operation = pendingOperation
            val cipher = result.cryptoObject?.cipher
            pendingOperation = null
            if (
                operation == null ||
                operation.epoch != operationEpoch ||
                stateMachine.state != SecurityState.AUTHENTICATING ||
                cipher == null ||
                cipher !== operation.cipher
            ) {
                stateMachine.onAuthenticationError(BiometricTerminalError.OTHER)
                renderState("Biometric result did not authorize the pending Cipher")
                return
            }
            securityExecutor.execute { completeAuthenticatedOperation(operation, cipher) }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            invalidatePendingOperation()
            if (stateMachine.state != SecurityState.AUTHENTICATING) {
                return
            }
            stateMachine.onAuthenticationError(errorCode.toTerminalError())
            renderState("Biometric error $errorCode: $errString; no credential fallback was offered")
        }

        override fun onAuthenticationFailed() {
            renderState("Biometric sample was not recognized; operation remains locked")
        }
    }

    private fun completeAuthenticatedOperation(operation: PendingOperation, cipher: Cipher) {
        if (operation.epoch != operationEpoch) {
            return
        }
        try {
            val detail = when (operation) {
                is PendingOperation.Encrypt -> {
                    val plaintext = SAMPLE_PLAINTEXT.copyOf()
                    try {
                        val first = keyStoreProbe.encryptWithAuthenticatedCipher(cipher, plaintext)
                        blobStore.write(first)
                        check(!EncryptedBlobCodec.encode(first).containsSubsequence(plaintext)) {
                            "Encrypted envelope contains the sample plaintext"
                        }
                        "Encrypted sample with a generated ${first.iv.size}-byte IV and persisted only ciphertext"
                    } finally {
                        plaintext.fill(0)
                    }
                }
                is PendingOperation.Decrypt -> {
                    val plaintext = keyStoreProbe.decryptWithAuthenticatedCipher(
                        cipher,
                        operation.blob.ciphertext,
                    )
                    try {
                        check(MessageDigest.isEqual(plaintext, SAMPLE_PLAINTEXT)) {
                            "Decrypted bytes differ from the sample"
                        }
                        "Decrypted and authenticated the persisted envelope; plaintext was wiped from memory"
                    } finally {
                        plaintext.fill(0)
                    }
                }
            }
            runOnUiThread {
                if (operation.epoch != operationEpoch) {
                    return@runOnUiThread
                }
                stateMachine.onAuthenticationSucceeded()
                renderState(detail)
            }
        } catch (error: Throwable) {
            runOnUiThread {
                if (operation.epoch == operationEpoch) {
                    handleCryptoError("Authenticated AES-GCM operation failed", error)
                }
            }
        }
    }

    private fun invalidatePendingOperation() {
        operationEpoch += 1
        pendingOperation = null
    }

    private fun handleCryptoError(prefix: String, error: Throwable) {
        if (with(AndroidKeyStoreProbe) { error.containsKeyPermanentlyInvalidated() }) {
            stateMachine.onKeyPermanentlyInvalidated()
            renderState("$prefix: biometric enrollment invalidated the key; local material was destroyed")
        } else {
            if (stateMachine.state == SecurityState.AUTHENTICATING) {
                stateMachine.onAuthenticationError(BiometricTerminalError.OTHER)
            }
            renderState("$prefix: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private fun destroyLocalMaterial() {
        blobStore.delete()
        keyStoreProbe.deleteAllKeys()
    }

    private fun renderState(detail: String) {
        lastDetail = detail
        val explanation = when (stateMachine.state) {
            SecurityState.PAIRING_REQUIRED -> "No local Pairing material exists."
            SecurityState.PAIRING_BLOCKED_NO_HARDWARE ->
                "Pairing is blocked because strong biometric hardware is absent or unsupported."
            SecurityState.PAIRING_BLOCKED_NO_ENROLLMENT ->
                "Pairing is blocked until a strong biometric is enrolled."
            SecurityState.PAIRING_BLOCKED_HARDWARE_UNAVAILABLE ->
                "Biometric hardware is temporarily unavailable; local material is retained."
            SecurityState.PAIRING_BLOCKED_SECURITY_UPDATE ->
                "Pairing is blocked until the biometric security update is installed."
            SecurityState.LOCKED -> "Local material exists; every AES operation requires a fresh biometric."
            SecurityState.AUTHENTICATING -> "Waiting for a strong biometric; PIN is not accepted."
            SecurityState.REPAIR_REQUIRED ->
                "Enrollment invalidated inaccessible material. A new Pairing is required."
        }
        statusText.text = buildString {
            append("State: ").append(stateMachine.state).append('\n')
            append(explanation).append('\n')
            append("Detail: ").append(lastDetail)
        }
        createMaterialButton.isEnabled = stateMachine.canCreatePairingMaterial()
        val locked = stateMachine.state == SecurityState.LOCKED
        signButton.isEnabled = locked && keyStoreProbe.hasSigningKey
        encryptButton.isEnabled = locked && keyStoreProbe.hasAesKey
        decryptButton.isEnabled = locked && keyStoreProbe.hasAesKey && blobStore.exists
    }

    private fun openEnrollmentSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG,
                )
            }
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_FINGERPRINT_ENROLL)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            renderState("This device does not expose biometric enrollment settings")
        }
    }

    private fun Int.toTerminalError(): BiometricTerminalError = when (this) {
        BiometricPrompt.ERROR_USER_CANCELED -> BiometricTerminalError.USER_CANCELED
        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricTerminalError.NEGATIVE_BUTTON
        BiometricPrompt.ERROR_CANCELED -> BiometricTerminalError.CANCELED
        BiometricPrompt.ERROR_LOCKOUT -> BiometricTerminalError.TEMPORARY_LOCKOUT
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricTerminalError.PERMANENT_LOCKOUT
        BiometricPrompt.ERROR_NO_BIOMETRICS -> BiometricTerminalError.NO_BIOMETRICS
        BiometricPrompt.ERROR_HW_NOT_PRESENT -> BiometricTerminalError.NO_HARDWARE
        BiometricPrompt.ERROR_HW_UNAVAILABLE -> BiometricTerminalError.HARDWARE_UNAVAILABLE
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
            BiometricTerminalError.SECURITY_UPDATE_REQUIRED
        else -> BiometricTerminalError.OTHER
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > size) {
            return false
        }
        return (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }

    private sealed class PendingOperation(open val cipher: Cipher, open val epoch: Long) {
        data class Encrypt(
            override val cipher: Cipher,
            override val epoch: Long,
        ) : PendingOperation(cipher, epoch)

        data class Decrypt(
            override val cipher: Cipher,
            val blob: EncryptedBlob,
            override val epoch: Long,
        ) : PendingOperation(cipher, epoch)
    }

    private companion object {
        val SAMPLE_PLAINTEXT = "P0.3 plaintext must never reach disk".toByteArray(Charsets.UTF_8)
    }
}
