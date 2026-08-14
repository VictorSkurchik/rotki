package org.rotki.mobile.android.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.ports.SecureSnapshotDeleteOutcome
import org.rotki.mobile.core.ports.SecureSnapshotReadOutcome
import org.rotki.mobile.core.ports.SecureSnapshotStore
import org.rotki.mobile.core.ports.SecureSnapshotWriteOutcome

class CompanionLifecycleControllerTest {
    @Test
    fun `inactive suspends without discarding local data`() {
        val fixture = Fixture()

        fixture.controller.onResume()
        fixture.controller.onPause()

        assertEquals(ApplicationVisibilityState.INACTIVE, fixture.visibility.state.value)
        assertEquals(emptyList<String>(), fixture.effects)
    }

    @Test
    fun `background locks and discards before returning`() {
        val fixture = Fixture()
        fixture.controller.onResume()

        fixture.controller.onBackgroundOrSystemLock()

        assertEquals(
            ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
            fixture.visibility.state.value,
        )
        assertEquals(
            listOf("lock", "cancel_authentication", "discard_snapshot", "discard_ui"),
            fixture.effects,
        )
    }

    private class Fixture {
        val effects = mutableListOf<String>()
        val visibility = AndroidApplicationVisibility()
        val controller =
            CompanionLifecycleController(
                visibility = visibility,
                snapshotStore = RecordingSnapshotStore(effects),
                lockCompanion = { effects += "lock" },
                cancelPendingAuthentication = { effects += "cancel_authentication" },
                discardAdditionalPlaintext = { effects += "discard_ui" },
            )
    }

    private class RecordingSnapshotStore(
        private val effects: MutableList<String>,
    ) : SecureSnapshotStore {
        override suspend fun readAfterDeviceAuthentication(): SecureSnapshotReadOutcome =
            SecureSnapshotReadOutcome.Missing

        override suspend fun replace(document: ByteArray): SecureSnapshotWriteOutcome =
            SecureSnapshotWriteOutcome.Unavailable

        override suspend fun delete(): SecureSnapshotDeleteOutcome = SecureSnapshotDeleteOutcome.Deleted

        override fun discardPlaintext() {
            effects += "discard_snapshot"
        }
    }
}
