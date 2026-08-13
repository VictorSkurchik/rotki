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
import org.rotki.mobile.android.pairing.AndroidDeviceLabelProvider
import org.rotki.mobile.android.pairing.AndroidEpochClock
import org.rotki.mobile.android.pairing.AndroidPairingStartupReconciler
import org.rotki.mobile.android.pairing.AndroidPairingStartupState
import org.rotki.mobile.android.pairing.applyRetryTo
import org.rotki.mobile.android.pairing.createStartupFacade
import org.rotki.mobile.android.pairing.PairingConnectionUiState
import org.rotki.mobile.android.security.AndroidBiometricCryptoBroker
import org.rotki.mobile.android.security.AndroidBiometricCryptoOutcome
import org.rotki.mobile.android.security.AndroidBiometricPromptCopy
import org.rotki.mobile.android.security.AndroidDeviceProofSigner
import org.rotki.mobile.android.security.AndroidIdempotencyKeyGenerator
import org.rotki.mobile.android.security.AndroidLocalMaterialCleaner
import org.rotki.mobile.android.security.AndroidSecureSnapshotStore
import org.rotki.mobile.android.security.AndroidSnapshotKeyStore
import org.rotki.mobile.android.security.AtomicSnapshotFile
import org.rotki.mobile.android.security.BiometricCryptoBroker
import org.rotki.mobile.android.storage.AndroidPairingRecordStore
import org.rotki.mobile.android.storage.AndroidPairingCleanupJournal
import org.rotki.mobile.auth.PairingConnection
import org.rotki.mobile.auth.PairingConnectionConfiguration
import org.rotki.mobile.auth.PairingConnectionOutcome
import org.rotki.mobile.auth.PairingDevicePlatform

/** One retained security graph for the lifetime of the Android application process. */
internal class AndroidSecurityComposition private constructor(
    private val applicationContext: Context,
) {
    val visibility: AndroidApplicationVisibility = AndroidApplicationVisibility()
    val pairingRecordStore: AndroidPairingRecordStore =
        AndroidPairingRecordStore(applicationContext)
    val deviceProofSigner: AndroidDeviceProofSigner = AndroidDeviceProofSigner()
    val pairingCleanupJournal: AndroidPairingCleanupJournal =
        AndroidPairingCleanupJournal(applicationContext)
    private val startupReconciler: AndroidPairingStartupReconciler =
        AndroidPairingStartupReconciler(
            readJournal = pairingCleanupJournal::read,
            readRecord = pairingRecordStore::read,
            readCurrentKey = deviceProofSigner::currentPublicKeyX963,
            markCleanupRequired = pairingCleanupJournal::markCleanupRequired,
        )
    private val startupState: AndroidPairingStartupState = runBlocking(Dispatchers.IO) {
        startupReconciler.reconcile()
    }
    val facade: CompanionFacade = startupState.createStartupFacade()
    val pairingConnection: PairingConnection = facade.pairingConnection(
        PairingConnectionConfiguration(
            deviceLabel = AndroidDeviceLabelProvider().label(),
            platform = PairingDevicePlatform.ANDROID,
            deviceProofSigner = deviceProofSigner,
            pairingRecordStore = pairingRecordStore,
            pairingCleanupJournal = pairingCleanupJournal,
            idempotencyKeyGenerator = AndroidIdempotencyKeyGenerator(),
            applicationVisibility = visibility,
            clock = AndroidEpochClock,
        ),
    )
    val initialPairingConnectionState: PairingConnectionUiState =
        when (startupState) {
            AndroidPairingStartupState.PAIRED,
            AndroidPairingStartupState.UNPAIRED,
            -> PairingConnectionUiState.IDLE
            AndroidPairingStartupState.CLEANUP_REQUIRED -> runBlocking(Dispatchers.IO) {
                pairingConnection.retryIncompleteCleanup().toStartupUiState()
            }
            AndroidPairingStartupState.FAIL_CLOSED ->
                PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
        }

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
        pairingCleanupJournal = pairingCleanupJournal,
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

    suspend fun retryIncompletePairingCleanup(): PairingConnectionOutcome =
        startupReconciler.reconcile().applyRetryTo(
            facade = facade,
            retryCleanup = pairingConnection::retryIncompleteCleanup,
        )

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

private fun PairingConnectionOutcome.toStartupUiState(): PairingConnectionUiState = when (this) {
    PairingConnectionOutcome.NO_PENDING_PAIRING -> PairingConnectionUiState.IDLE
    else -> PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
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
