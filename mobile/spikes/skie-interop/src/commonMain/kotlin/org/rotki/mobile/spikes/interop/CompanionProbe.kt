package org.rotki.mobile.spikes.interop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

public enum class ConnectionQuality {
    COMPLETE,
    DEGRADED,
}

public sealed interface CompanionState {
    public data object Unpaired : CompanionState
    public data object DeviceLocked : CompanionState
    public data object Connecting : CompanionState
    public data object Online : CompanionState
    public data object Refreshing : CompanionState
    public data object Degraded : CompanionState
    public data object Unreachable : CompanionState
    public data object EngineLocked : CompanionState
    public data object ProfileMismatch : CompanionState
    public data object Incompatible : CompanionState
    public data object Revoked : CompanionState
}

public class CompanionProbe {
    private val mutableState: MutableStateFlow<CompanionState> =
        MutableStateFlow(CompanionState.Unpaired)
    private val mutableCancellationStarted: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val mutableCancellationObserved: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val mutableKotlinCancellationStarted: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    private val mutableKotlinCancellationObserved: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    private val kotlinCancellationJob: MutableStateFlow<Job?> = MutableStateFlow(null)

    public val state: StateFlow<CompanionState> = mutableState.asStateFlow()
    public val cancellationStarted: StateFlow<Boolean> = mutableCancellationStarted.asStateFlow()
    public val cancellationObserved: StateFlow<Boolean> =
        mutableCancellationObserved.asStateFlow()
    public val kotlinCancellationStarted: StateFlow<Boolean> =
        mutableKotlinCancellationStarted.asStateFlow()
    public val kotlinCancellationObserved: StateFlow<Boolean> =
        mutableKotlinCancellationObserved.asStateFlow()

    public fun emit(state: CompanionState): Unit {
        mutableState.value = state
    }

    public fun classify(quality: ConnectionQuality): CompanionState = when (quality) {
        ConnectionQuality.COMPLETE -> CompanionState.Online
        ConnectionQuality.DEGRADED -> CompanionState.Degraded
    }

    public suspend fun awaitSwiftCancellation(): Unit {
        mutableCancellationStarted.value = true
        try {
            awaitCancellation()
        } catch (cancellation: CancellationException) {
            mutableCancellationObserved.value = true
            throw cancellation
        }
    }

    public suspend fun awaitKotlinCancellation(): Unit {
        val currentJob = currentCoroutineContext().job
        kotlinCancellationJob.value = currentJob
        mutableKotlinCancellationStarted.value = true
        try {
            awaitCancellation()
        } finally {
            mutableKotlinCancellationObserved.value = true
            kotlinCancellationJob.compareAndSet(currentJob, null)
        }
    }

    public fun cancelFromKotlin(): Unit {
        kotlinCancellationJob.value?.cancel()
    }
}
