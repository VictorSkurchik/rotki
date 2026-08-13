package org.rotki.mobile.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.rotki.mobile.android.pairing.AndroidEpochClock
import org.rotki.mobile.android.pairing.AndroidLocalNetworkPermissionPolicy
import org.rotki.mobile.android.pairing.PairingViewModel
import org.rotki.mobile.android.pairing.scanner.PairingCodeScanner
import org.rotki.mobile.android.pairing.scanner.PairingCodeScannerFailure
import org.rotki.mobile.android.pairing.scanner.rememberPairingCodeScannerController
import org.rotki.mobile.android.security.AndroidBiometricPromptCopy
import org.rotki.mobile.android.ui.RotkiCompanionApp
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.ports.ApplicationVisibilityState

class MainActivity : FragmentActivity() {
    private lateinit var securityComposition: AndroidSecurityComposition
    private lateinit var pairingViewModel: PairingViewModel

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pairingViewModel.startScanning()
        } else {
            pairingViewModel.cameraPermissionDenied()
        }
    }

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pairingViewModel.localNetworkPermissionGranted()
        } else {
            pairingViewModel.localNetworkPermissionRequired()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        securityComposition = AndroidSecurityComposition.get(applicationContext)
        pairingViewModel = ViewModelProvider(
            this,
            PairingViewModel.Factory(
                facade = securityComposition.facade,
                clock = AndroidEpochClock,
                pairingConnection = securityComposition.pairingConnection,
                cleanupConnector = securityComposition::retryIncompletePairingCleanup,
                initialConnectionState =
                    securityComposition.initialPairingConnectionState,
            ),
        )[PairingViewModel::class.java]
        securityComposition.attachActivity(
            activity = this,
            promptCopy = AndroidBiometricPromptCopy(
                title = getString(R.string.biometric_snapshot_title),
                subtitle = getString(R.string.biometric_snapshot_subtitle),
                cancel = getString(R.string.biometric_cancel),
            ),
        )
        setContent {
            val status by securityComposition.facade.status.collectAsStateWithLifecycle()
            val visibility by securityComposition.visibility.state.collectAsStateWithLifecycle()
            val pairing by pairingViewModel.presentation.collectAsStateWithLifecycle()
            val pairingConnectionState by
                pairingViewModel.connectionState.collectAsStateWithLifecycle()
            val scannerRestartGeneration by
                pairingViewModel.scannerRestartGeneration.collectAsStateWithLifecycle()
            val scannerController = rememberPairingCodeScannerController()

            LaunchedEffect(pairing.state) {
                if (pairing.state == PairingUiState.SCANNING) {
                    scannerController.restart()
                }
            }
            LaunchedEffect(scannerRestartGeneration) {
                if (scannerRestartGeneration > 0L) {
                    scannerController.restart()
                }
            }

            RotkiCompanionApp(
                status = status,
                pairing = pairing,
                pairingConnectionState = pairingConnectionState,
                privacyCovered =
                    visibility != ApplicationVisibilityState.ACTIVE_FOREGROUND,
                onStartScanning = ::requestCameraAndScan,
                onRetryScanning = ::requestCameraAndScan,
                onCancelScanning = pairingViewModel::reset,
                onOpenCameraSettings = ::openApplicationSettings,
                onRequestLocalNetworkPermission = ::requestLocalNetworkPermission,
                onRetryCleanup = pairingViewModel::retryIncompleteCleanup,
                onRetryConnection = pairingViewModel::retryConnection,
                scanner = {
                    PairingCodeScanner(
                        onPairingCode = ::submitQrWithLocalNetworkPermission,
                        modifier = Modifier.fillMaxSize(),
                        controller = scannerController,
                        onFailure = { failure ->
                            if (failure == PairingCodeScannerFailure.CAMERA_PERMISSION_REQUIRED) {
                                pairingViewModel.cameraPermissionDenied()
                            } else {
                                pairingViewModel.scannerUnavailable()
                            }
                        },
                    )
                },
            )
        }
    }

    override fun onResume(): Unit {
        super.onResume()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        securityComposition.lifecycleController.onResume()
        pairingViewModel.onForeground()
    }

    override fun onPause(): Unit {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        securityComposition.lifecycleController.onPause()
        pairingViewModel.onInactive()
        super.onPause()
    }

    override fun onStop(): Unit {
        if (!isChangingConfigurations) {
            securityComposition.lifecycleController.onBackgroundOrSystemLock()
            pairingViewModel.onBackground()
        }
        super.onStop()
    }

    override fun onDestroy(): Unit {
        if (::securityComposition.isInitialized) {
            securityComposition.detachActivity(this)
        }
        super.onDestroy()
    }

    private fun requestCameraAndScan(): Unit {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pairingViewModel.startScanning()
        } else {
            pairingViewModel.startScanning()
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun submitQrWithLocalNetworkPermission(rawPayload: String): Unit {
        if (requiresLocalNetworkPermission()) {
            pairingViewModel.localNetworkPermissionRequired()
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            pairingViewModel.submitQr(rawPayload)
        }
    }

    private fun requestLocalNetworkPermission(): Unit {
        if (requiresLocalNetworkPermission()) {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            pairingViewModel.localNetworkPermissionGranted()
        }
    }

    private fun requiresLocalNetworkPermission(): Boolean =
        AndroidLocalNetworkPermissionPolicy.requiresRuntimePermission(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED,
        )

    private fun openApplicationSettings(): Unit {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
