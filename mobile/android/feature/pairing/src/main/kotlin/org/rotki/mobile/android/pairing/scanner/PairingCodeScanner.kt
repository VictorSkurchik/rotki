package org.rotki.mobile.android.pairing.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectResult
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
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Controls explicit result-delivery cycles for a remembered Pairing scanner. */
@Stable
public sealed interface PairingCodeScannerController {
    /** Rearms result delivery and retries acquisition when the scanner is already active. */
    public fun restart(): Unit
}

private class DefaultPairingCodeScannerController : PairingCodeScannerController {
    var restartToken: Long by mutableLongStateOf(0L)
        private set

    override fun restart() {
        restartToken += 1L
    }
}

/** Remembers the controller for one composition-scoped Pairing scanner. */
@Composable
public fun rememberPairingCodeScannerController(): PairingCodeScannerController =
    remember { DefaultPairingCodeScannerController() }

/**
 * Native camera preview that emits one opaque QR payload per explicit controller cycle.
 *
 * [onPairingCode] receives one-shot Pairing authority. The caller MUST submit it immediately and
 * MUST NOT log, persist, or retain it beyond that submission attempt.
 *
 * Permission requests and any rendering of scan results belong to the host screen.
 */
@Composable
public fun PairingCodeScanner(
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
        val observer = PairingCodeScannerLifecycleObserver(source)
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            source.start()
        }
        PairingCodeScannerLifecycleEffectResult(lifecycleOwner, observer, source)
    }

    val restartToken =
        when (controller) {
            is DefaultPairingCodeScannerController -> controller.restartToken
        }
    DisposableEffect(source, restartToken) {
        if (restartToken > 0L) {
            source.restart()
        }
        NoOpPairingCodeScannerEffectResult
    }
}

private class PairingCodeScannerLifecycleObserver(
    private val source: PairingCodeSource,
) : DefaultLifecycleObserver {
    override fun onResume(owner: LifecycleOwner) {
        source.start()
    }

    override fun onPause(owner: LifecycleOwner) {
        source.stop()
    }
}

private class PairingCodeScannerLifecycleEffectResult(
    private val lifecycleOwner: LifecycleOwner,
    private val observer: PairingCodeScannerLifecycleObserver,
    private val source: PairingCodeSource,
) : DisposableEffectResult {
    override fun dispose() {
        lifecycleOwner.lifecycle.removeObserver(observer)
        source.close()
    }
}

private data object NoOpPairingCodeScannerEffectResult : DisposableEffectResult {
    override fun dispose(): Unit = Unit
}

private interface PairingCodeSource : Closeable {
    fun start(): Unit

    fun stop(): Unit

    fun restart(): Unit
}

private class CameraXPairingCodeSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onPairingCode: (String) -> Unit,
    private val onFailure: (PairingCodeScannerFailure) -> Unit,
) : PairingCodeSource {
    private val mainExecutor: Executor = context.mainExecutor
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
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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

private class MlKitQrFrameDecoder(
    private val completionExecutor: Executor,
    private val scanner: BarcodeScanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions
                .Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        ),
) : PairingFrameDecoder {
    @ExperimentalGetImage
    override fun decode(
        frame: ImageProxy,
        complete: (PairingFrameDecodeResult) -> Unit,
    ) {
        val mediaImage = frame.image
        if (mediaImage == null) {
            complete(EmptyPairingFrameDecodeResult)
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
        scanner.process(input).addOnCompleteListener(completionExecutor) { task ->
            val outcome =
                if (!task.isSuccessful) {
                    FailedPairingFrameDecodeResult
                } else {
                    val payload =
                        task.result
                            ?.asSequence()
                            ?.mapNotNull { barcode -> barcode.rawValue }
                            ?.firstOrNull { candidate -> candidate.isNotBlank() }
                    if (payload == null) {
                        EmptyPairingFrameDecodeResult
                    } else {
                        DetectedPairingFrameDecodeResult(payload)
                    }
                }
            complete(outcome)
        }
    }

    override fun close() {
        scanner.close()
    }
}

private class PairingCodeAnalyzer(
    private val decoder: PairingFrameDecoder,
    private val deliveryGate: PairingCodeDeliveryGate,
    private val onPairingCode: (String) -> Unit,
    private val onFailure: (PairingCodeScannerFailure) -> Unit,
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        val lease = CloseOnce(image::close)
        val session = deliveryGate.currentSession()
        if (session == null) {
            lease.close()
            return
        }
        val completed = AtomicBoolean(false)
        val complete: (PairingFrameDecodeResult) -> Unit = { result ->
            if (completed.compareAndSet(false, true)) {
                try {
                    when (result) {
                        is DetectedPairingFrameDecodeResult -> {
                            deliveryGate.offer(session, result.payload, onPairingCode)
                        }

                        EmptyPairingFrameDecodeResult -> {
                            // Keep scanning until this delivery cycle observes a payload or failure.
                        }

                        FailedPairingFrameDecodeResult -> {
                            deliveryGate.fail(session) {
                                onFailure(PairingCodeScannerFailure.DECODER_FAILED)
                            }
                        }
                    }
                } finally {
                    lease.close()
                }
            }
        }

        try {
            decoder.decode(image, complete)
        } catch (_: RuntimeException) {
            complete(FailedPairingFrameDecodeResult)
        }
    }
}

private class PairingCodeDeliveryGate {
    private val lock = Any()
    private var generation: Long = 0L
    private var active: Boolean = false
    private var delivered: Boolean = false

    fun activate(): Unit =
        synchronized(lock) {
            generation += 1L
            active = true
        }

    fun deactivate(): Unit =
        synchronized(lock) {
            generation += 1L
            active = false
        }

    fun currentSession(): Long? =
        synchronized(lock) {
            generation.takeIf { active }
        }

    fun offer(
        session: Long,
        payload: String?,
        deliver: (String) -> Unit,
    ) {
        if (payload.isNullOrBlank()) return
        if (claim(session)) {
            deliver(payload)
        }
    }

    fun fail(
        session: Long,
        deliver: () -> Unit,
    ) {
        if (claim(session)) deliver()
    }

    fun restart(): Unit =
        synchronized(lock) {
            generation += 1L
            delivered = false
        }

    private fun claim(session: Long): Boolean =
        synchronized(lock) {
            if (!active || session != generation || delivered) {
                false
            } else {
                delivered = true
                true
            }
        }
}

private class CloseOnce(
    private val closeAction: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) {
            closeAction()
        }
    }
}

private sealed interface PairingFrameDecodeResult

private class DetectedPairingFrameDecodeResult(
    val payload: String,
) : PairingFrameDecodeResult {
    override fun toString(): String = "Detected(redacted)"
}

private data object EmptyPairingFrameDecodeResult : PairingFrameDecodeResult

private data object FailedPairingFrameDecodeResult : PairingFrameDecodeResult

private interface PairingFrameDecoder : Closeable {
    fun decode(
        frame: ImageProxy,
        complete: (PairingFrameDecodeResult) -> Unit,
    ): Unit
}
