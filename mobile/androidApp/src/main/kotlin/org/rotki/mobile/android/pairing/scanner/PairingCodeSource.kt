package org.rotki.mobile.android.pairing.scanner

import java.io.Closeable

/** Lifecycle-neutral scanner boundary for a native UI host. */
interface PairingCodeSource : Closeable {
    /** Starts camera acquisition without resetting an already delivered result. */
    fun start(): Unit

    /** Stops camera acquisition while retaining the current delivery latch. */
    fun stop(): Unit

    /** Explicitly rearms result delivery and retries acquisition when already started. */
    fun restart(): Unit
}

enum class PairingCodeScannerFailure {
    CAMERA_PERMISSION_REQUIRED,
    CAMERA_UNAVAILABLE,
    DECODER_FAILED,
}
