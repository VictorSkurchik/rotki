package org.rotki.mobile.android.pairing.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CameraXPairingCodeSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onPairingCode: (String) -> Unit,
    private val onFailure: (PairingCodeScannerFailure) -> Unit,
) : PairingCodeSource {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val deliveryGate = PairingCodeDeliveryGate()
    private val decoder = MlKitQrFrameDecoder(mainExecutor)
    private val preview =
        Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
    private val analysis =
        ImageAnalysis
            .Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(
                    analysisExecutor,
                    PairingCodeAnalyzer(
                        decoder = decoder,
                        deliveryGate = deliveryGate,
                        onPairingCode = onPairingCode,
                        onFailure = onFailure,
                    ),
                )
            }
    private val providerFuture = ProcessCameraProvider.getInstance(context)

    private var provider: ProcessCameraProvider? = null
    private var startRequested: Boolean = false
    private var bound: Boolean = false
    private var closed: Boolean = false

    init {
        providerFuture.addListener(
            {
                if (closed) return@addListener
                provider =
                    try {
                        providerFuture.get()
                    } catch (_: Exception) {
                        onFailure(PairingCodeScannerFailure.CAMERA_UNAVAILABLE)
                        return@addListener
                    }
                if (startRequested) {
                    bindIfPossible()
                }
            },
            mainExecutor,
        )
    }

    override fun start() {
        if (closed || startRequested) return
        startRequested = true
        bindIfPossible()
    }

    override fun stop() {
        if (!startRequested) return
        startRequested = false
        deliveryGate.deactivate()
        unbind()
    }

    override fun restart() {
        if (closed) return
        deliveryGate.restart()
        if (startRequested && !bound) {
            bindIfPossible()
        }
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        analysis.clearAnalyzer()
        decoder.close()
        analysisExecutor.shutdown()
    }

    private fun bindIfPossible() {
        if (closed || !startRequested || bound) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onFailure(PairingCodeScannerFailure.CAMERA_PERMISSION_REQUIRED)
            return
        }
        val availableProvider = provider ?: return
        try {
            availableProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            bound = true
            deliveryGate.activate()
        } catch (_: RuntimeException) {
            bound = false
            onFailure(PairingCodeScannerFailure.CAMERA_UNAVAILABLE)
        }
    }

    private fun unbind() {
        if (!bound) return
        provider?.unbind(preview, analysis)
        bound = false
    }
}
