package org.rotki.mobile.spikes.security

enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    NONE_ENROLLED,
    HARDWARE_UNAVAILABLE,
    SECURITY_UPDATE_REQUIRED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class BiometricTerminalError {
    USER_CANCELED,
    NEGATIVE_BUTTON,
    CANCELED,
    TEMPORARY_LOCKOUT,
    PERMANENT_LOCKOUT,
    NO_BIOMETRICS,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    SECURITY_UPDATE_REQUIRED,
    OTHER,
}

enum class SecurityState {
    PAIRING_REQUIRED,
    PAIRING_BLOCKED_NO_HARDWARE,
    PAIRING_BLOCKED_NO_ENROLLMENT,
    PAIRING_BLOCKED_HARDWARE_UNAVAILABLE,
    PAIRING_BLOCKED_SECURITY_UPDATE,
    LOCKED,
    AUTHENTICATING,
    REPAIR_REQUIRED,
}

/**
 * Models only the P0.3 decisions: Pairing eligibility, per-operation authentication,
 * lockout retention, and destructive recovery after biometric-set invalidation.
 */
class SecurityStateMachine(private val destroyLocalMaterial: () -> Unit) {
    private var availability = BiometricAvailability.UNKNOWN
    private var materialPresent = false
    private var repairRequired = false

    var state: SecurityState = SecurityState.PAIRING_REQUIRED
        private set

    fun refresh(newAvailability: BiometricAvailability, hasLocalMaterial: Boolean) {
        availability = newAvailability
        materialPresent = hasLocalMaterial
        if (newAvailability == BiometricAvailability.NONE_ENROLLED && materialPresent) {
            destroyForRepair()
        }
        state = stateForAvailability()
    }

    fun canCreatePairingMaterial(): Boolean =
        availability == BiometricAvailability.AVAILABLE &&
            (state == SecurityState.PAIRING_REQUIRED || state == SecurityState.REPAIR_REQUIRED)

    fun onPairingMaterialCreated() {
        check(availability == BiometricAvailability.AVAILABLE) {
            "Biometric hardware and enrollment are required before Pairing"
        }
        materialPresent = true
        repairRequired = false
        state = SecurityState.LOCKED
    }

    fun beginAuthentication(): Boolean {
        if (state != SecurityState.LOCKED || !materialPresent) {
            return false
        }
        state = SecurityState.AUTHENTICATING
        return true
    }

    fun onAuthenticationSucceeded() {
        check(state == SecurityState.AUTHENTICATING) { "No biometric operation is pending" }
        state = SecurityState.LOCKED
    }

    fun onAuthenticationError(error: BiometricTerminalError) {
        when (error) {
            BiometricTerminalError.NO_BIOMETRICS -> {
                availability = BiometricAvailability.NONE_ENROLLED
                if (materialPresent) {
                    destroyForRepair()
                }
                state = SecurityState.PAIRING_BLOCKED_NO_ENROLLMENT
            }
            BiometricTerminalError.NO_HARDWARE -> {
                availability = BiometricAvailability.NO_HARDWARE
                state = SecurityState.PAIRING_BLOCKED_NO_HARDWARE
            }
            BiometricTerminalError.HARDWARE_UNAVAILABLE -> {
                availability = BiometricAvailability.HARDWARE_UNAVAILABLE
                state = SecurityState.PAIRING_BLOCKED_HARDWARE_UNAVAILABLE
            }
            BiometricTerminalError.SECURITY_UPDATE_REQUIRED -> {
                availability = BiometricAvailability.SECURITY_UPDATE_REQUIRED
                state = SecurityState.PAIRING_BLOCKED_SECURITY_UPDATE
            }
            BiometricTerminalError.USER_CANCELED,
            BiometricTerminalError.NEGATIVE_BUTTON,
            BiometricTerminalError.CANCELED,
            BiometricTerminalError.TEMPORARY_LOCKOUT,
            BiometricTerminalError.PERMANENT_LOCKOUT,
            BiometricTerminalError.OTHER,
            -> state = if (materialPresent) SecurityState.LOCKED else stateForAvailability()
        }
    }

    fun onKeyPermanentlyInvalidated() {
        if (materialPresent) {
            destroyForRepair()
        } else {
            repairRequired = true
        }
        state = if (availability == BiometricAvailability.AVAILABLE) {
            SecurityState.REPAIR_REQUIRED
        } else {
            stateForAvailability()
        }
    }

    fun destroyByUser() {
        if (materialPresent) {
            destroyLocalMaterial()
        }
        materialPresent = false
        repairRequired = false
        state = stateForAvailability()
    }

    private fun destroyForRepair() {
        destroyLocalMaterial()
        materialPresent = false
        repairRequired = true
    }

    private fun stateForAvailability(): SecurityState = when (availability) {
        BiometricAvailability.AVAILABLE -> when {
            repairRequired -> SecurityState.REPAIR_REQUIRED
            materialPresent -> SecurityState.LOCKED
            else -> SecurityState.PAIRING_REQUIRED
        }
        BiometricAvailability.NO_HARDWARE,
        BiometricAvailability.UNSUPPORTED,
        -> SecurityState.PAIRING_BLOCKED_NO_HARDWARE
        BiometricAvailability.NONE_ENROLLED -> SecurityState.PAIRING_BLOCKED_NO_ENROLLMENT
        BiometricAvailability.HARDWARE_UNAVAILABLE,
        BiometricAvailability.UNKNOWN,
        -> SecurityState.PAIRING_BLOCKED_HARDWARE_UNAVAILABLE
        BiometricAvailability.SECURITY_UPDATE_REQUIRED -> SecurityState.PAIRING_BLOCKED_SECURITY_UPDATE
    }
}
