package org.rotki.mobile.android.pairing.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor

internal class MlKitQrFrameDecoder(
    private val completionExecutor: Executor,
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    ),
) : PairingFrameDecoder {
    @ExperimentalGetImage
    override fun decode(
        frame: ImageProxy,
        complete: (PairingFrameDecodeResult) -> Unit,
    ): Unit {
        val mediaImage = frame.image
        if (mediaImage == null) {
            complete(PairingFrameDecodeResult.Empty)
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees)
        scanner.process(input).addOnCompleteListener(completionExecutor) { task ->
            val outcome = if (!task.isSuccessful) {
                PairingFrameDecodeResult.Failed
            } else {
                val payload = task.result
                    ?.asSequence()
                    ?.mapNotNull { barcode -> barcode.rawValue }
                    ?.firstOrNull { candidate -> candidate.isNotBlank() }
                if (payload == null) {
                    PairingFrameDecodeResult.Empty
                } else {
                    PairingFrameDecodeResult.Detected(payload)
                }
            }
            complete(outcome)
        }
    }

    override fun close(): Unit {
        scanner.close()
    }
}
