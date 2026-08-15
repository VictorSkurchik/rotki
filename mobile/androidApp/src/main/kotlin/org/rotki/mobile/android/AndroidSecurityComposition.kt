package org.rotki.mobile.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.android.lifecycle.AndroidCompanionLifecycle
import org.rotki.mobile.android.lifecycle.createAndroidCompanionLifecycle
import org.rotki.mobile.android.pairing.AndroidDeviceLabelProvider
import org.rotki.mobile.android.pairing.AndroidEpochClock
import org.rotki.mobile.android.pairing.AndroidPairingStartupReconciler
import org.rotki.mobile.android.pairing.AndroidPairingStartupState
import org.rotki.mobile.android.pairing.PairingConnectionUiState
import org.rotki.mobile.android.pairing.applyRetryTo
import org.rotki.mobile.android.pairing.createStartupFacade
import org.rotki.mobile.android.security.AndroidBiometricCryptoBroker
import org.rotki.mobile.android.security.AndroidBiometricCryptoOutcome
import org.rotki.mobile.android.security.AndroidBiometricPromptCopy
import org.rotki.mobile.android.security.AndroidLocalMaterialCleaner
import org.rotki.mobile.android.security.AndroidSecureSnapshotStore
import org.rotki.mobile.android.security.AndroidSnapshotKeyStore
import org.rotki.mobile.android.security.AtomicSnapshotFile
import org.rotki.mobile.android.security.BiometricCryptoBroker
import org.rotki.mobile.android.security.createAndroidDeviceProofSigner
import org.rotki.mobile.android.security.createAndroidIdempotencyKeyGenerator
import org.rotki.mobile.android.storage.createAndroidPairingCleanupJournal
import org.rotki.mobile.android.storage.createAndroidPairingRecordStore
import org.rotki.mobile.auth.CompanionAuthorizationController
import org.rotki.mobile.auth.CompanionAuthorizationLocalAuthorityDestroyer
import org.rotki.mobile.auth.PairingConnection
import org.rotki.mobile.auth.PairingConnectionConfiguration
import org.rotki.mobile.auth.PairingConnectionOutcome
import org.rotki.mobile.auth.PairingDevicePlatform
import org.rotki.mobile.auth.createCompanionAuthorizationController
import org.rotki.mobile.auth.createPlatformPairingRegistrationRemoteGateway
import org.rotki.mobile.core.ports.ApplicationVisibilityController
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.IdempotencyKeyGenerator
import org.rotki.mobile.core.ports.PairingCleanupJournal
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.ports.SecureSnapshotReadOutcome
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.feature.authorization.data.createPlatformAuthorizationRemoteGateway
import javax.crypto.Cipher

/** One retained security graph for the lifetime of the Android application process. */
internal class AndroidSecurityComposition(
    private val applicationContext: Context,
) {
    private val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val visibility: ApplicationVisibilityController = ApplicationVisibilityController()
    val pairingRecordStore: PairingRecordStore =
        createAndroidPairingRecordStore(applicationContext)
    val deviceProofSigner: DeviceProofSigner = createAndroidDeviceProofSigner()
    private val idempotencyKeyGenerator: IdempotencyKeyGenerator =
        createAndroidIdempotencyKeyGenerator()
    val pairingCleanupJournal: PairingCleanupJournal =
        createAndroidPairingCleanupJournal(applicationContext)
    private val startupReconciler: AndroidPairingStartupReconciler =
        AndroidPairingStartupReconciler(
            readJournal = pairingCleanupJournal::read,
            readRecord = pairingRecordStore::read,
            readCurrentKey = deviceProofSigner::currentPublicKeyX963,
            markCleanupRequired = pairingCleanupJournal::markCleanupRequired,
        )
    private val startupState: AndroidPairingStartupState =
        runBlocking(Dispatchers.IO) {
            startupReconciler.reconcile()
        }
    val facade: CompanionFacade = startupState.createStartupFacade()
    val pairingConnection: PairingConnection =
        facade.pairingConnection(
            PairingConnectionConfiguration(
                deviceLabel = AndroidDeviceLabelProvider().label(),
                platform = PairingDevicePlatform.ANDROID,
                deviceProofSigner = deviceProofSigner,
                pairingRecordStore = pairingRecordStore,
                pairingCleanupJournal = pairingCleanupJournal,
                idempotencyKeyGenerator = idempotencyKeyGenerator,
                applicationVisibility = visibility,
                clock = AndroidEpochClock,
            ),
        )
    val initialPairingConnectionState: PairingConnectionUiState =
        when (startupState) {
            AndroidPairingStartupState.PAIRED,
            AndroidPairingStartupState.UNPAIRED,
            -> {
                PairingConnectionUiState.IDLE
            }

            AndroidPairingStartupState.CLEANUP_REQUIRED -> {
                runBlocking(Dispatchers.IO) {
                    pairingConnection.retryIncompleteCleanup().toStartupUiState()
                }
            }

            AndroidPairingStartupState.FAIL_CLOSED -> {
                PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
            }
        }

    private val biometricBroker: ActivityBoundBiometricCryptoBroker =
        ActivityBoundBiometricCryptoBroker()
    private val snapshotFile: AtomicSnapshotFile = AtomicSnapshotFile(applicationContext)
    private val snapshotKeyStore: AndroidSnapshotKeyStore =
        AndroidSnapshotKeyStore(applicationContext)
    private val materialCleaner: AndroidLocalMaterialCleaner =
        AndroidLocalMaterialCleaner(
            snapshotFile = snapshotFile,
            snapshotKeyStore = snapshotKeyStore,
            pairingRecordStore = pairingRecordStore,
            deviceProofSigner = deviceProofSigner,
            pairingCleanupJournal = pairingCleanupJournal,
        )
    val snapshotStore: AndroidSecureSnapshotStore =
        AndroidSecureSnapshotStore(
            context = applicationContext,
            keyStore = snapshotKeyStore,
            biometricBroker = biometricBroker,
            materialCleaner = materialCleaner,
            file = snapshotFile,
        )
    val authorizationController: CompanionAuthorizationController =
        createCompanionAuthorizationController(
            facade = facade,
            remoteGateway = createPlatformAuthorizationRemoteGateway(),
            discoveryGateway = createPlatformPairingRegistrationRemoteGateway(),
            pairingRecordStore = pairingRecordStore,
            deviceProofSigner = deviceProofSigner,
            applicationVisibility = visibility,
            clock = AndroidEpochClock,
            processScope = processScope,
            localAuthorityDestroyer =
                CompanionAuthorizationLocalAuthorityDestroyer(materialCleaner::destroyAll),
        )
    private val platformLifecycleController: AndroidCompanionLifecycle =
        createAndroidCompanionLifecycle(
            visibility = visibility,
            lockCompanion = authorizationController::onBackgroundOrSystemLock,
            cancelPendingAuthentication = biometricBroker::cancelPending,
            discardSnapshotPlaintext = snapshotStore::discardPlaintext,
            discardAdditionalPlaintext = { },
        )
    val lifecycleController: AndroidCompanionLifecycle =
        object : AndroidCompanionLifecycle {
            override fun onResume() {
                platformLifecycleController.onResume()
                if (facade.status.value.rootState == CompanionRootState.DeviceLocked) {
                    processScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        readSnapshotAfterDeviceAuthentication()
                    }
                } else {
                    authorizationController.onActiveForeground()
                }
            }

            override fun onPause() {
                platformLifecycleController.onPause()
            }

            override fun onBackgroundOrSystemLock() {
                platformLifecycleController.onBackgroundOrSystemLock()
            }
        }

    private val screenOffReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
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
    ) {
        biometricBroker.attach(activity, promptCopy)
    }

    fun detachActivity(activity: FragmentActivity) {
        biometricBroker.detach(activity)
    }

    suspend fun retryIncompletePairingCleanup(): PairingConnectionOutcome =
        startupReconciler.reconcile().applyRetryTo(
            facade = facade,
            retryCleanup = pairingConnection::retryIncompleteCleanup,
        )

    suspend fun connectPendingPairing(): PairingConnectionOutcome =
        pairingConnection.connectPendingPairing().also { outcome ->
            if (outcome == PairingConnectionOutcome.REGISTERED) {
                authorizationController.onPairingRegistered()
            }
        }

    suspend fun readSnapshotAfterDeviceAuthentication(): SecureSnapshotReadOutcome =
        snapshotStore.readAfterDeviceAuthentication().also { outcome ->
            if (outcome is SecureSnapshotReadOutcome.Unlocked) {
                facade.deviceAuthenticationSucceeded()
                authorizationController.onActiveForeground()
            }
        }
}

private fun PairingConnectionOutcome.toStartupUiState(): PairingConnectionUiState =
    when (this) {
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
    ) {
        val next =
            ActivityBinding(
                activity = activity,
                broker = AndroidBiometricCryptoBroker(activity, promptCopy),
            )
        val previous =
            synchronized(bindingLock) {
                binding.also { binding = next }
            }
        previous?.broker?.cancelPending()
    }

    fun detach(activity: FragmentActivity) {
        val detached =
            synchronized(bindingLock) {
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
        val broker =
            synchronized(bindingLock) { binding?.broker }
                ?: return AndroidBiometricCryptoOutcome.Unavailable
        return broker.authorize(cipher)
    }

    override fun cancelPending() {
        synchronized(bindingLock) { binding?.broker }?.cancelPending()
    }

    private class ActivityBinding(
        val activity: FragmentActivity,
        val broker: AndroidBiometricCryptoBroker,
    )
}
