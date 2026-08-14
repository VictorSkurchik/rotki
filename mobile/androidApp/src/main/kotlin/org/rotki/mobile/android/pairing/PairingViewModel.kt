package org.rotki.mobile.android.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.PairingConnection
import org.rotki.mobile.auth.PairingConnectionOutcome
import org.rotki.mobile.auth.PairingFlow
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionRootState

internal enum class PairingConnectionUiState {
    IDLE,
    CONNECTING,
    CLEANING_UP,
    REGISTERED,
    LOCAL_NETWORK_PERMISSION_REQUIRED,
    PAIRING_EXPIRED,
    PAIRING_UNAVAILABLE,
    INCOMPATIBLE,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    LOCAL_SECURITY_UNAVAILABLE,
    LOCAL_STORAGE_UNAVAILABLE,
    LOCAL_CLEANUP_INCOMPLETE,
    UNEXPECTED,
}

internal fun interface PendingPairingConnector {
    suspend fun connect(): PairingConnectionOutcome
}

internal fun interface PendingPairingCleanupConnector {
    suspend fun retry(): PairingConnectionOutcome
}

internal class PairingViewModel(
    private val facade: CompanionFacade,
    clock: Clock,
    private val connector: PendingPairingConnector,
    private val cleanupConnector: PendingPairingCleanupConnector,
    initialConnectionState: PairingConnectionUiState = PairingConnectionUiState.IDLE,
) : ViewModel() {
    private val flow: PairingFlow = facade.pairingFlow(clock)
    private val mutableConnectionState: MutableStateFlow<PairingConnectionUiState> =
        MutableStateFlow(initialConnectionState)
    private val mutableScannerRestartGeneration: MutableStateFlow<Long> = MutableStateFlow(0)
    private var connectionJob: Job? = null
    private var connectionGeneration: Long = 0

    val presentation: StateFlow<PairingPresentation> = flow.presentation
    val connectionState: StateFlow<PairingConnectionUiState> =
        mutableConnectionState.asStateFlow()
    val scannerRestartGeneration: StateFlow<Long> =
        mutableScannerRestartGeneration.asStateFlow()

    fun startScanning() {
        if (connectionJob?.isActive == true) return
        mutableConnectionState.value = PairingConnectionUiState.IDLE
        if (facade.status.value.rootState == CompanionRootState.Unpaired) {
            flow.reset()
        }
        flow.startScanning()
    }

    fun cameraPermissionDenied(): Unit = flow.cameraPermissionDenied()

    fun scannerUnavailable(): Unit = flow.scannerUnavailable()

    fun localNetworkPermissionRequired() {
        if (connectionJob?.isActive == true) return
        mutableConnectionState.value = PairingConnectionUiState.LOCAL_NETWORK_PERMISSION_REQUIRED
    }

    fun localNetworkPermissionGranted() {
        retryScanning()
        if (mutableConnectionState.value == PairingConnectionUiState.IDLE &&
            flow.presentation.value.state == PairingUiState.SCANNING
        ) {
            mutableScannerRestartGeneration.value += 1
        }
    }

    fun submitQr(rawPayload: String) {
        if (connectionJob?.isActive == true) return
        val previousState = flow.presentation.value.state
        flow.submitQr(rawPayload)
        if (previousState == PairingUiState.SCANNING &&
            flow.presentation.value.state == PairingUiState.CONNECTING
        ) {
            launchPendingConnection()
        }
    }

    fun retryScanning() {
        if (connectionJob?.isActive == true) return
        mutableConnectionState.value = PairingConnectionUiState.IDLE
        if (facade.status.value.rootState == CompanionRootState.Unpaired) {
            flow.reset()
            flow.startScanning()
        } else {
            flow.retryScanning()
        }
    }

    fun reset() {
        if (connectionJob?.isActive == true) return
        cancelConnection()
        mutableConnectionState.value = PairingConnectionUiState.IDLE
        flow.reset()
    }

    fun onForeground() {
        if (connectionJob?.isActive == true) return
        if (mutableConnectionState.value == PairingConnectionUiState.REGISTERED ||
            mutableConnectionState.value == PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE ||
            mutableConnectionState.value ==
            PairingConnectionUiState.LOCAL_NETWORK_PERMISSION_REQUIRED
        ) {
            return
        }
        // Transient permission dialogs also produce INACTIVE -> ACTIVE. Preserve their explicit
        // presentation; full background handling performs the destructive reset when required.
    }

    // Keep the shared connection coroutine alive; it suspends network work at visibility loss.
    fun onInactive(): Unit = Unit

    fun onBackground() {
        // Visibility and facade lock are applied first. Keep an active shared job alive so it can
        // finish rollback and distinguish verified cleanup from a fail-closed cleanup outcome.
        if (connectionJob?.isActive == true) return
        cancelConnection()
        if (mutableConnectionState.value != PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE) {
            mutableConnectionState.value = PairingConnectionUiState.IDLE
        }
        flow.reset()
    }

    fun retryConnection() {
        when (facade.status.value.rootState) {
            CompanionRootState.Unreachable -> facade.transportRestored()

            CompanionRootState.EngineLocked,
            CompanionRootState.Incompatible,
            CompanionRootState.ProfileMismatch,
            -> facade.retryResolvedEngineState()

            else -> Unit
        }
    }

    fun retryIncompleteCleanup() {
        if (connectionJob?.isActive == true ||
            mutableConnectionState.value != PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
        ) {
            return
        }
        val generation = ++connectionGeneration
        mutableConnectionState.value = PairingConnectionUiState.CLEANING_UP
        connectionJob =
            viewModelScope.launch {
                val outcome =
                    try {
                        cleanupConnector.retry()
                    } catch (cancellation: CancellationException) {
                        if (connectionGeneration == generation) {
                            mutableConnectionState.value =
                                PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
                        }
                        throw cancellation
                    } catch (_: Exception) {
                        PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                    }
                if (connectionGeneration != generation) return@launch
                if (outcome == PairingConnectionOutcome.NO_PENDING_PAIRING) {
                    mutableConnectionState.value = PairingConnectionUiState.IDLE
                    flow.reset()
                } else {
                    mutableConnectionState.value = PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
                }
            }
    }

    private fun launchPendingConnection() {
        if (connectionJob?.isActive == true) return
        val generation = ++connectionGeneration
        mutableConnectionState.value = PairingConnectionUiState.CONNECTING
        connectionJob =
            viewModelScope.launch {
                try {
                    val outcome = connector.connect()
                    if (connectionGeneration != generation) return@launch
                    mutableConnectionState.value = outcome.toUiState()
                    if (outcome != PairingConnectionOutcome.REGISTERED) {
                        flow.reset()
                    }
                } catch (cancellation: CancellationException) {
                    if (connectionGeneration == generation &&
                        facade.status.value.rootState == CompanionRootState.Unpaired
                    ) {
                        mutableConnectionState.value = PairingConnectionUiState.IDLE
                        flow.reset()
                    }
                    throw cancellation
                }
            }
    }

    private fun cancelConnection() {
        connectionGeneration += 1
        connectionJob?.cancel()
        connectionJob = null
    }

    internal class Factory(
        private val facade: CompanionFacade,
        private val clock: Clock,
        private val pairingConnection: PairingConnection,
        private val cleanupConnector: PendingPairingCleanupConnector,
        private val initialConnectionState: PairingConnectionUiState,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java))
            return PairingViewModel(
                facade = facade,
                clock = clock,
                connector = PendingPairingConnector(pairingConnection::connectPendingPairing),
                cleanupConnector = cleanupConnector,
                initialConnectionState = initialConnectionState,
            ) as T
        }
    }
}

private fun PairingConnectionOutcome.toUiState(): PairingConnectionUiState =
    when (this) {
        PairingConnectionOutcome.REGISTERED -> {
            PairingConnectionUiState.REGISTERED
        }

        PairingConnectionOutcome.NO_PENDING_PAIRING,
        PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND,
        -> {
            PairingConnectionUiState.IDLE
        }

        PairingConnectionOutcome.PAIRING_EXPIRED -> {
            PairingConnectionUiState.PAIRING_EXPIRED
        }

        PairingConnectionOutcome.PAIRING_UNAVAILABLE -> {
            PairingConnectionUiState.PAIRING_UNAVAILABLE
        }

        PairingConnectionOutcome.INCOMPATIBLE -> {
            PairingConnectionUiState.INCOMPATIBLE
        }

        PairingConnectionOutcome.RATE_LIMITED -> {
            PairingConnectionUiState.RATE_LIMITED
        }

        PairingConnectionOutcome.NETWORK_UNAVAILABLE -> {
            PairingConnectionUiState.NETWORK_UNAVAILABLE
        }

        PairingConnectionOutcome.LOCAL_SECURITY_UNAVAILABLE -> {
            PairingConnectionUiState.LOCAL_SECURITY_UNAVAILABLE
        }

        PairingConnectionOutcome.LOCAL_STORAGE_UNAVAILABLE -> {
            PairingConnectionUiState.LOCAL_STORAGE_UNAVAILABLE
        }

        PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE -> {
            PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE
        }

        PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE,
        PairingConnectionOutcome.UNEXPECTED_FAILURE,
        -> {
            PairingConnectionUiState.UNEXPECTED
        }
    }

internal object AndroidEpochClock : Clock {
    override fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000L
}
