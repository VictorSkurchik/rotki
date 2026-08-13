package org.rotki.mobile.spikes.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStateMachineTest {
    @Test
    fun `missing hardware or enrollment blocks Pairing`() {
        val machine = SecurityStateMachine { error("must not destroy absent material") }

        machine.refresh(BiometricAvailability.NO_HARDWARE, hasLocalMaterial = false)
        assertEquals(SecurityState.PAIRING_BLOCKED_NO_HARDWARE, machine.state)
        assertFalse(machine.canCreatePairingMaterial())

        machine.refresh(BiometricAvailability.NONE_ENROLLED, hasLocalMaterial = false)
        assertEquals(SecurityState.PAIRING_BLOCKED_NO_ENROLLMENT, machine.state)
        assertFalse(machine.canCreatePairingMaterial())
    }

    @Test
    fun `temporary and permanent lockout retain material and remain locked`() {
        var destructions = 0
        val machine = SecurityStateMachine { destructions++ }
        machine.refresh(BiometricAvailability.AVAILABLE, hasLocalMaterial = true)

        assertTrue(machine.beginAuthentication())
        machine.onAuthenticationError(BiometricTerminalError.TEMPORARY_LOCKOUT)
        assertEquals(SecurityState.LOCKED, machine.state)

        assertTrue(machine.beginAuthentication())
        machine.onAuthenticationError(BiometricTerminalError.PERMANENT_LOCKOUT)
        assertEquals(SecurityState.LOCKED, machine.state)
        assertEquals(0, destructions)
    }

    @Test
    fun `enrollment change destroys local material once and requires repair`() {
        var destructions = 0
        val machine = SecurityStateMachine { destructions++ }
        machine.refresh(BiometricAvailability.AVAILABLE, hasLocalMaterial = true)

        machine.onKeyPermanentlyInvalidated()
        assertEquals(SecurityState.REPAIR_REQUIRED, machine.state)
        assertEquals(1, destructions)

        machine.onKeyPermanentlyInvalidated()
        assertEquals(SecurityState.REPAIR_REQUIRED, machine.state)
        assertEquals(1, destructions)
    }

    @Test
    fun `loss of all enrollment destroys existing local material`() {
        var destructions = 0
        val machine = SecurityStateMachine { destructions++ }
        machine.refresh(BiometricAvailability.AVAILABLE, hasLocalMaterial = true)

        machine.refresh(BiometricAvailability.NONE_ENROLLED, hasLocalMaterial = true)

        assertEquals(SecurityState.PAIRING_BLOCKED_NO_ENROLLMENT, machine.state)
        assertEquals(1, destructions)
    }

    @Test
    fun `only available biometrics permit new Pairing material`() {
        val machine = SecurityStateMachine { }
        machine.refresh(BiometricAvailability.AVAILABLE, hasLocalMaterial = false)
        assertTrue(machine.canCreatePairingMaterial())

        machine.onPairingMaterialCreated()
        assertEquals(SecurityState.LOCKED, machine.state)
        assertFalse(machine.canCreatePairingMaterial())
        assertTrue(machine.beginAuthentication())
        machine.onAuthenticationSucceeded()
        assertEquals(SecurityState.LOCKED, machine.state)
    }
}
