package org.rotki.mobile.core.ports

import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationVisibilityContractsTest {
    @Test
    fun `clock remains a substitutable injected boundary`() {
        val clock = Clock { 1_786_550_300L }

        assertEquals(1_786_550_300L, clock.nowEpochSeconds())
    }

    @Test
    fun `controller starts fail closed and accepts only valid lifecycle progression`() {
        val controller = ApplicationVisibilityController()

        assertEquals(ApplicationVisibilityState.BACKGROUND_OR_LOCKED, controller.state.value)
        controller.onInactive()
        assertEquals(ApplicationVisibilityState.BACKGROUND_OR_LOCKED, controller.state.value)
        controller.onActiveForeground()
        assertEquals(ApplicationVisibilityState.ACTIVE_FOREGROUND, controller.state.value)
        controller.onInactive()
        assertEquals(ApplicationVisibilityState.INACTIVE, controller.state.value)
        controller.onBackgroundOrLocked()
        assertEquals(ApplicationVisibilityState.BACKGROUND_OR_LOCKED, controller.state.value)
        assertEquals("ApplicationVisibilityController(redacted)", controller.toString())
    }

    @Test
    fun `policy decisions cover every visibility state`() {
        assertEquals(
            listOf(
                ApplicationVisibilityState.ACTIVE_FOREGROUND,
                ApplicationVisibilityState.INACTIVE,
                ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
            ),
            ApplicationVisibilityState.entries,
        )
        val expected =
            mapOf(
                ApplicationVisibilityState.ACTIVE_FOREGROUND to
                    ApplicationVisibilityDecision(
                        networkAllowed = true,
                        destroyBearer = false,
                        discardPlaintext = false,
                        requiresDeviceAuthenticationOnReturn = false,
                    ),
                ApplicationVisibilityState.INACTIVE to
                    ApplicationVisibilityDecision(
                        networkAllowed = false,
                        destroyBearer = false,
                        discardPlaintext = false,
                        requiresDeviceAuthenticationOnReturn = false,
                    ),
                ApplicationVisibilityState.BACKGROUND_OR_LOCKED to
                    ApplicationVisibilityDecision(
                        networkAllowed = false,
                        destroyBearer = true,
                        discardPlaintext = true,
                        requiresDeviceAuthenticationOnReturn = true,
                    ),
            )

        assertEquals(ApplicationVisibilityState.entries.toSet(), expected.keys)
        expected.forEach { (state, decision) ->
            assertEquals(decision, ApplicationVisibilityPolicy.decide(state), state.name)
        }
    }
}
