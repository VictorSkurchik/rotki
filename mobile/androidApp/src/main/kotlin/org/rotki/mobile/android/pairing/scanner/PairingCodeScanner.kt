package org.rotki.mobile.android.pairing.scanner

import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

@Stable
class PairingCodeScannerController internal constructor() {
    internal var restartToken: Long by mutableLongStateOf(0L)
        private set

    fun restart() {
        restartToken += 1L
    }
}

@Composable
fun rememberPairingCodeScannerController(): PairingCodeScannerController = remember { PairingCodeScannerController() }

/**
 * Native camera preview that emits one opaque QR payload per explicit controller cycle.
 *
 * Permission requests and any rendering of scan results belong to the host screen.
 */
@Composable
fun PairingCodeScanner(
    onPairingCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: PairingCodeScannerController = rememberPairingCodeScannerController(),
    onFailure: (PairingCodeScannerFailure) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPairingCode = rememberUpdatedState(onPairingCode)
    val currentOnFailure = rememberUpdatedState(onFailure)
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val source =
        remember(context, lifecycleOwner, previewView) {
            CameraXPairingCodeSource(
                context = context.applicationContext,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                onPairingCode = { payload -> currentOnPairingCode.value(payload) },
                onFailure = { failure -> currentOnFailure.value(failure) },
            )
        }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner, source) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    source.start()
                }

                override fun onPause(owner: LifecycleOwner) {
                    source.stop()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            source.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            source.close()
        }
    }

    val restartToken = controller.restartToken
    DisposableEffect(source, restartToken) {
        if (restartToken > 0L) {
            source.restart()
        }
        onDispose {}
    }
}
