package org.rotki.mobile.android.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.PairingFlow
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.core.ports.Clock

internal class PairingViewModel(
    facade: CompanionFacade,
    clock: Clock,
) : ViewModel() {
    private val flow: PairingFlow = facade.pairingFlow(clock)

    val presentation: StateFlow<PairingPresentation> = flow.presentation

    fun startScanning(): Unit = flow.startScanning()

    fun cameraPermissionDenied(): Unit = flow.cameraPermissionDenied()

    fun scannerUnavailable(): Unit = flow.scannerUnavailable()

    fun submitQr(rawPayload: String): Unit = flow.submitQr(rawPayload)

    fun retryScanning(): Unit = flow.retryScanning()

    fun reset(): Unit = flow.reset()

    internal class Factory(
        private val facade: CompanionFacade,
        private val clock: Clock,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java))
            return PairingViewModel(facade, clock) as T
        }
    }
}

internal object AndroidEpochClock : Clock {
    override fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000L
}
