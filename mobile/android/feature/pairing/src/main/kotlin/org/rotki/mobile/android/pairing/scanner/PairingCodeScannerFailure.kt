package org.rotki.mobile.android.pairing.scanner

/** Coarse, payload-free failures that the scanner host can recover from. */
public enum class PairingCodeScannerFailure {
    CAMERA_PERMISSION_REQUIRED,
    CAMERA_UNAVAILABLE,
    DECODER_FAILED,
}
