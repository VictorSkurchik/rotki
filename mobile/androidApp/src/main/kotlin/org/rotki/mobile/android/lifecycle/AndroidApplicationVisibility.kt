package org.rotki.mobile.android.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState

internal class AndroidApplicationVisibility : ApplicationVisibility {
    private val mutableState = MutableStateFlow(
        ApplicationVisibilityState.BACKGROUND_OR_LOCKED,
    )

    override val state: StateFlow<ApplicationVisibilityState> = mutableState.asStateFlow()

    fun onActiveForeground(): Unit {
        mutableState.value = ApplicationVisibilityState.ACTIVE_FOREGROUND
    }

    fun onInactive(): Unit {
        if (mutableState.value == ApplicationVisibilityState.ACTIVE_FOREGROUND) {
            mutableState.value = ApplicationVisibilityState.INACTIVE
        }
    }

    fun onBackgroundOrLocked(): Unit {
        mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
    }
}
