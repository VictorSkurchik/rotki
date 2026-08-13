package org.rotki.mobile.android.pairing

internal object AndroidLocalNetworkPermissionPolicy {
    const val ENFORCED_API_LEVEL: Int = 37

    fun requiresRuntimePermission(
        sdkInt: Int,
        permissionGranted: Boolean,
    ): Boolean = sdkInt >= ENFORCED_API_LEVEL && !permissionGranted
}
