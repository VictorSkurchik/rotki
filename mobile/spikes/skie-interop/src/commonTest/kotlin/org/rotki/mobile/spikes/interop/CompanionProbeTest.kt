package org.rotki.mobile.spikes.interop

import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionProbeTest {
    @Test
    fun exposesAllRequiredRootStates(): Unit {
        val states = listOf(
            CompanionState.Unpaired,
            CompanionState.DeviceLocked,
            CompanionState.Connecting,
            CompanionState.Online,
            CompanionState.Refreshing,
            CompanionState.Degraded,
            CompanionState.Unreachable,
            CompanionState.EngineLocked,
            CompanionState.ProfileMismatch,
            CompanionState.Incompatible,
            CompanionState.Revoked,
        )

        assertEquals(11, states.size)
        assertEquals(CompanionState.Online, CompanionProbe().classify(ConnectionQuality.COMPLETE))
        assertEquals(
            CompanionState.Degraded,
            CompanionProbe().classify(ConnectionQuality.DEGRADED),
        )
    }
}
