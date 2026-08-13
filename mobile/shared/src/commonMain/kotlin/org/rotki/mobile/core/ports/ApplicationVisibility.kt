package org.rotki.mobile.core.ports

import kotlinx.coroutines.flow.StateFlow

public enum class ApplicationVisibilityState {
    ACTIVE_FOREGROUND,
    INACTIVE,
    BACKGROUND_OR_LOCKED,
}

public interface ApplicationVisibility {
    public val state: StateFlow<ApplicationVisibilityState>
}
