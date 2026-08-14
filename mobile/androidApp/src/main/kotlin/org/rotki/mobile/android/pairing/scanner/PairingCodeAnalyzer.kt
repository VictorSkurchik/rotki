package org.rotki.mobile.android.pairing.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

internal class PairingCodeAnalyzer(
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
                        is PairingFrameDecodeResult.Detected -> {
                            deliveryGate.offer(session, result.payload, onPairingCode)
                        }

                        PairingFrameDecodeResult.Empty -> {
                            Unit
                        }

                        PairingFrameDecodeResult.Failed -> {
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
            complete(PairingFrameDecodeResult.Failed)
        }
    }
}
