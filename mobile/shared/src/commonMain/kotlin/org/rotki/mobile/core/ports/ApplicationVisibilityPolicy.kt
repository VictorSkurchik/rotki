package org.rotki.mobile.core.ports

public data class ApplicationVisibilityDecision(
    public val networkAllowed: Boolean,
    public val destroyBearer: Boolean,
    public val discardPlaintext: Boolean,
    public val requiresDeviceAuthenticationOnReturn: Boolean,
)

public object ApplicationVisibilityPolicy {
    public fun decide(state: ApplicationVisibilityState): ApplicationVisibilityDecision =
        when (state) {
            ApplicationVisibilityState.ACTIVE_FOREGROUND -> ApplicationVisibilityDecision(
                networkAllowed = true,
                destroyBearer = false,
                discardPlaintext = false,
                requiresDeviceAuthenticationOnReturn = false,
            )
            ApplicationVisibilityState.INACTIVE -> ApplicationVisibilityDecision(
                networkAllowed = false,
                destroyBearer = false,
                discardPlaintext = false,
                requiresDeviceAuthenticationOnReturn = false,
            )
            ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> ApplicationVisibilityDecision(
                networkAllowed = false,
                destroyBearer = true,
                discardPlaintext = true,
                requiresDeviceAuthenticationOnReturn = true,
            )
        }
}
