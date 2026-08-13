package org.rotki.mobile.android.pairing

import android.os.Build
import org.rotki.mobile.auth.protocol.DeviceLabelValidator

/** Supplies a bounded, non-identifying label for Device Session registration. */
internal class AndroidDeviceLabelProvider(
    private val modelSource: () -> String? = { Build.MODEL },
) {
    fun label(): String {
        val candidate = modelSource()?.trim().orEmpty()
        return if (DeviceLabelValidator.isValid(candidate)) candidate else FALLBACK_LABEL
    }

    private companion object {
        const val FALLBACK_LABEL: String = "Android device"
    }
}
