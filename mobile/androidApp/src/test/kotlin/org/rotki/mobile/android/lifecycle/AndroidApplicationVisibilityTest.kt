package org.rotki.mobile.android.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rotki.mobile.core.ports.ApplicationVisibilityState

class AndroidApplicationVisibilityTest {
    @Test
    fun `starts fail closed and follows Android lifecycle signals`() {
        val visibility = AndroidApplicationVisibility()

        assertEquals(
            ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
            visibility.state.value,
        )

        visibility.onActiveForeground()
        assertEquals(ApplicationVisibilityState.ACTIVE_FOREGROUND, visibility.state.value)

        visibility.onInactive()
        assertEquals(ApplicationVisibilityState.INACTIVE, visibility.state.value)

        visibility.onBackgroundOrLocked()
        assertEquals(ApplicationVisibilityState.BACKGROUND_OR_LOCKED, visibility.state.value)
    }

    @Test
    fun `inactive cannot reopen a locked application`() {
        val visibility = AndroidApplicationVisibility()

        visibility.onInactive()

        assertEquals(
            ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
            visibility.state.value,
        )
    }
}
