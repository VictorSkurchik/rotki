package org.rotki.mobile.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.android.lifecycle.AndroidApplicationVisibility
import org.rotki.mobile.android.lifecycle.CompanionLifecycleController
import org.rotki.mobile.android.security.AndroidBiometricCryptoBroker
import org.rotki.mobile.android.security.AndroidBiometricCryptoOutcome
import org.rotki.mobile.android.security.AndroidBiometricPromptCopy
import org.rotki.mobile.android.security.AndroidDeviceProofSigner
import org.rotki.mobile.android.security.AndroidLocalMaterialCleaner
import org.rotki.mobile.android.security.AndroidSecureSnapshotStore
import org.rotki.mobile.android.security.AndroidSnapshotKeyStore
import org.rotki.mobile.android.security.AtomicSnapshotFile
import org.rotki.mobile.android.security.BiometricCryptoBroker
import org.rotki.mobile.android.storage.AndroidPairingRecordStore
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.state.SnapshotCoverage

/** One retained security graph for the lifetime of the Android application process. */
internal class AndroidSecurityComposition private constructor(
    private val applicationContext: Context,
) {
    val visibility: AndroidApplicationVisibility = AndroidApplicationVisibility()
    val pairingRecordStore: AndroidPairingRecordStore =
        AndroidPairingRecordStore(applicationContext)
    val deviceProofSigner: AndroidDeviceProofSigner = AndroidDeviceProofSigner()
    val facade: CompanionFacade = restoreCompanionFacade(pairingRecordStore)

    private val biometricBroker: ActivityBoundBiometricCryptoBroker =
        ActivityBoundBiometricCryptoBroker()
    private val snapshotFile: AtomicSnapshotFile = AtomicSnapshotFile(applicationContext)
    private val snapshotKeyStore: AndroidSnapshotKeyStore =
        AndroidSnapshotKeyStore(applicationContext)
    private val materialCleaner: AndroidLocalMaterialCleaner = AndroidLocalMaterialCleaner(
        snapshotFile = snapshotFile,
        snapshotKeyStore = snapshotKeyStore,
        pairingRecordStore = pairingRecordStore,
        deviceProofSigner = deviceProofSigner,
    )
    val snapshotStore: AndroidSecureSnapshotStore = AndroidSecureSnapshotStore(
        context = applicationContext,
        keyStore = snapshotKeyStore,
        biometricBroker = biometricBroker,
        materialCleaner = materialCleaner,
        file = snapshotFile,
    )
    val lifecycleController: CompanionLifecycleController = CompanionLifecycleController(
        visibility = visibility,
        snapshotStore = snapshotStore,
        lockCompanion = { facade.lock() },
        cancelPendingAuthentication = biometricBroker::cancelPending,
        discardAdditionalPlaintext = { },
    )

    private val screenOffReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?): Unit {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                lifecycleController.onBackgroundOrSystemLock()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            applicationContext,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun attachActivity(
        activity: FragmentActivity,
        promptCopy: AndroidBiometricPromptCopy,
    ): Unit {
        biometricBroker.attach(activity, promptCopy)
    }

    fun detachActivity(activity: FragmentActivity): Unit {
        biometricBroker.detach(activity)
    }

    companion object {
        @Volatile
        private var retained: AndroidSecurityComposition? = null

        fun get(context: Context): AndroidSecurityComposition =
            retained ?: synchronized(this) {
                retained ?: AndroidSecurityComposition(context.applicationContext).also {
                    retained = it
                }
            }
    }
}

private fun restoreCompanionFacade(
    pairingRecordStore: AndroidPairingRecordStore,
): CompanionFacade = runBlocking(Dispatchers.IO) {
    when (pairingRecordStore.read()) {
        is PairingRecordReadOutcome.Present ->
            CompanionFacade.restorePaired(SnapshotCoverage.Absent)
        PairingRecordReadOutcome.Missing -> CompanionFacade()
        // The current shared state model has no local-storage-error state; fail closed.
        PairingRecordReadOutcome.Corrupt,
        PairingRecordReadOutcome.Unavailable,
        -> CompanionFacade.restorePaired(SnapshotCoverage.Absent)
    }
}

/** Keeps process-scoped crypto state while holding only the currently attached Activity. */
private class ActivityBoundBiometricCryptoBroker : BiometricCryptoBroker {
    private val bindingLock: Any = Any()
    private var binding: ActivityBinding? = null

    fun attach(
        activity: FragmentActivity,
        promptCopy: AndroidBiometricPromptCopy,
    ): Unit {
        val next = ActivityBinding(
            activity = activity,
            broker = AndroidBiometricCryptoBroker(activity, promptCopy),
        )
        val previous = synchronized(bindingLock) {
            binding.also { binding = next }
        }
        previous?.broker?.cancelPending()
    }

    fun detach(activity: FragmentActivity): Unit {
        val detached = synchronized(bindingLock) {
            val current = binding
            if (current?.activity === activity) {
                binding = null
                current
            } else {
                null
            }
        }
        detached?.broker?.cancelPending()
    }

    override suspend fun authorize(cipher: Cipher): AndroidBiometricCryptoOutcome {
        val broker = synchronized(bindingLock) { binding?.broker }
            ?: return AndroidBiometricCryptoOutcome.Unavailable
        return broker.authorize(cipher)
    }

    override fun cancelPending(): Unit {
        synchronized(bindingLock) { binding?.broker }?.cancelPending()
    }

    private class ActivityBinding(
        val activity: FragmentActivity,
        val broker: AndroidBiometricCryptoBroker,
    )
}
