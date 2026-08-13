package org.rotki.mobile.android.pairing.scanner

import androidx.camera.core.ImageProxy
import java.io.Closeable

internal sealed interface PairingFrameDecodeResult {
    class Detected(
        val payload: String,
    ) : PairingFrameDecodeResult {
        override fun toString(): String = "Detected(redacted)"
    }

    data object Empty : PairingFrameDecodeResult

    data object Failed : PairingFrameDecodeResult
}

/** Implementations must invoke [complete] exactly once, including for asynchronous failures. */
internal interface PairingFrameDecoder : Closeable {
    fun decode(
        frame: ImageProxy,
        complete: (PairingFrameDecodeResult) -> Unit,
    ): Unit
}
