package org.rotki.mobile.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionRootState

class PairingViewModelTest {
    @Test
    fun `view model delegates a valid QR without retaining it`(): Unit {
        val facade = CompanionFacade()
        val viewModel = PairingViewModel(facade, Clock { NOW })

        viewModel.startScanning()
        viewModel.submitQr(validQr())

        assertEquals(PairingUiState.CONNECTING, viewModel.presentation.value.state)
        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
    }

    @Test
    fun `permission denial and retry remain explicit presentation states`(): Unit {
        val viewModel = PairingViewModel(CompanionFacade(), Clock { NOW })

        viewModel.startScanning()
        viewModel.cameraPermissionDenied()
        assertEquals(PairingUiState.CAMERA_DENIED, viewModel.presentation.value.state)

        viewModel.retryScanning()
        assertEquals(PairingUiState.SCANNING, viewModel.presentation.value.state)

        viewModel.scannerUnavailable()
        assertEquals(PairingUiState.SCANNER_UNAVAILABLE, viewModel.presentation.value.state)

        viewModel.reset()
        assertEquals(PairingUiState.INTRO, viewModel.presentation.value.state)
    }
}

private const val NOW: Long = 1_786_550_300L

private fun validQr(): String =
    """{"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"https://rotki.example","pairing_id":"AAECAwQFBgcICQoLDA0ODw","pairing_credential":"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8","expires_at":${NOW + 60}}"""
