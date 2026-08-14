package org.rotki.mobile.core.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public enum class ApplicationVisibilityState {
    ACTIVE_FOREGROUND,
    INACTIVE,
    BACKGROUND_OR_LOCKED,
}

public interface ApplicationVisibility {
    public val state: StateFlow<ApplicationVisibilityState>
}

/** Swift-safe lifecycle input whose initial state is deliberately fail-closed. */
public class ApplicationVisibilityController : ApplicationVisibility {
    private val mutableState: MutableStateFlow<ApplicationVisibilityState> =
        MutableStateFlow(
            ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
        )

    override val state: StateFlow<ApplicationVisibilityState> = mutableState.asStateFlow()

    public fun onActiveForeground() {
        mutableState.value = ApplicationVisibilityState.ACTIVE_FOREGROUND
    }

    public fun onInactive() {
        if (mutableState.value == ApplicationVisibilityState.ACTIVE_FOREGROUND) {
            mutableState.value = ApplicationVisibilityState.INACTIVE
        }
    }

    public fun onBackgroundOrLocked() {
        mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
    }

    override fun toString(): String = "ApplicationVisibilityController(redacted)"
}
