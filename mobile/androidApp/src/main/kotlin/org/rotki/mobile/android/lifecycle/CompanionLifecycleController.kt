package org.rotki.mobile.android.lifecycle

import org.rotki.mobile.core.ports.SecureSnapshotStore

internal class CompanionLifecycleController(
    private val visibility: AndroidApplicationVisibility,
    private val snapshotStore: SecureSnapshotStore,
    private val lockCompanion: () -> Unit,
    private val cancelPendingAuthentication: () -> Unit,
    private val discardAdditionalPlaintext: () -> Unit,
) {
    fun onResume() {
        visibility.onActiveForeground()
    }

    fun onPause() {
        visibility.onInactive()
    }

    fun onBackgroundOrSystemLock() {
        visibility.onBackgroundOrLocked()
        lockCompanion()
        cancelPendingAuthentication()
        snapshotStore.discardPlaintext()
        discardAdditionalPlaintext()
    }
}
