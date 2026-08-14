package org.rotki.mobile.android.lifecycle

import org.rotki.mobile.core.ports.ApplicationVisibilityController

/** Android lifecycle boundary used by the process composition root. */
public interface AndroidCompanionLifecycle {
    public fun onResume()

    public fun onPause()

    public fun onBackgroundOrSystemLock()
}

/**
 * Creates one lifecycle coordinator for the Android application process.
 *
 * The callbacks must be process-safe and must not directly retain an Activity; Activity-bound work
 * must remain behind an identity-checked attach/detach proxy. Background handling is deliberately
 * ordered: close visibility, lock authority, cancel authentication, then discard all plaintext.
 */
public fun createAndroidCompanionLifecycle(
    visibility: ApplicationVisibilityController,
    lockCompanion: () -> Unit,
    cancelPendingAuthentication: () -> Unit,
    discardSnapshotPlaintext: () -> Unit,
    discardAdditionalPlaintext: () -> Unit,
): AndroidCompanionLifecycle =
    DefaultAndroidCompanionLifecycle(
        visibility = visibility,
        lockCompanion = lockCompanion,
        cancelPendingAuthentication = cancelPendingAuthentication,
        discardSnapshotPlaintext = discardSnapshotPlaintext,
        discardAdditionalPlaintext = discardAdditionalPlaintext,
    )

private class DefaultAndroidCompanionLifecycle(
    private val visibility: ApplicationVisibilityController,
    private val lockCompanion: () -> Unit,
    private val cancelPendingAuthentication: () -> Unit,
    private val discardSnapshotPlaintext: () -> Unit,
    private val discardAdditionalPlaintext: () -> Unit,
) : AndroidCompanionLifecycle {
    override fun onResume() {
        visibility.onActiveForeground()
    }

    override fun onPause() {
        visibility.onInactive()
    }

    override fun onBackgroundOrSystemLock() {
        visibility.onBackgroundOrLocked()
        lockCompanion()
        cancelPendingAuthentication()
        discardSnapshotPlaintext()
        discardAdditionalPlaintext()
    }

    override fun toString(): String = "AndroidCompanionLifecycle(redacted)"
}
