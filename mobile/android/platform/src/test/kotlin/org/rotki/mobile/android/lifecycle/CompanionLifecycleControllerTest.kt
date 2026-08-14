package org.rotki.mobile.android.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rotki.mobile.core.ports.ApplicationVisibilityController
import org.rotki.mobile.core.ports.ApplicationVisibilityState

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
    fun `background locks before every ordered plaintext cleanup`() {
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

    @Test
    fun `repeated background signals reapply complete cleanup and stay redacted`() {
        val fixture = Fixture()

        fixture.controller.onBackgroundOrSystemLock()
        fixture.controller.onBackgroundOrSystemLock()

        val oneCleanup =
            listOf("lock", "cancel_authentication", "discard_snapshot", "discard_ui")
        assertEquals(oneCleanup + oneCleanup, fixture.effects)
        assertEquals("AndroidCompanionLifecycle(redacted)", fixture.controller.toString())
    }

    private class Fixture {
        val effects = mutableListOf<String>()
        val visibility = ApplicationVisibilityController()
        val controller: AndroidCompanionLifecycle =
            createAndroidCompanionLifecycle(
                visibility = visibility,
                lockCompanion = { recordLockedEffect("lock") },
                cancelPendingAuthentication = {
                    recordLockedEffect("cancel_authentication")
                },
                discardSnapshotPlaintext = { recordLockedEffect("discard_snapshot") },
                discardAdditionalPlaintext = { recordLockedEffect("discard_ui") },
            )

        private fun recordLockedEffect(effect: String) {
            assertEquals(
                ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
                visibility.state.value,
            )
            effects += effect
        }
    }
}
