package org.rotki.mobile.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionRootState

class PairingFlowTest {
    @Test
    fun `QR outcomes map to the complete public presentation vocabulary`(): Unit {
        assertEquals(
            setOf(
                PairingUiState.INTRO,
                PairingUiState.SCANNING,
                PairingUiState.CAMERA_DENIED,
                PairingUiState.SCANNER_UNAVAILABLE,
                PairingUiState.INVALID_QR,
                PairingUiState.EXPIRED_QR,
                PairingUiState.CONNECTING,
            ),
            PairingUiState.entries.toSet(),
        )
        assertEquals(
            setOf(
                PairingRejectionCategory.MALFORMED,
                PairingRejectionCategory.UNSUPPORTED,
                PairingRejectionCategory.EXPIRED,
            ),
            PairingRejectionCategory.entries.toSet(),
        )

        listOf(
            QrCase(
                name = "valid",
                wire = validQr(expiresAt = NOW + 1),
                expectedState = PairingUiState.CONNECTING,
                expectedRejection = null,
            ),
            QrCase(
                name = "expired",
                wire = validQr(expiresAt = NOW),
                expectedState = PairingUiState.EXPIRED_QR,
                expectedRejection = PairingRejectionCategory.EXPIRED,
            ),
            QrCase(
                name = "malformed",
                wire = "{not-json}",
                expectedState = PairingUiState.INVALID_QR,
                expectedRejection = PairingRejectionCategory.MALFORMED,
            ),
            QrCase(
                name = "unsupported",
                wire = validQr(expiresAt = NOW + 1).replace(
                    "\"format_version\":1",
                    "\"format_version\":2",
                ),
                expectedState = PairingUiState.INVALID_QR,
                expectedRejection = PairingRejectionCategory.UNSUPPORTED,
            ),
        ).forEach { case ->
            val facade = CompanionFacade()
            val flow = facade.pairingFlow(Clock { NOW })
            flow.startScanning()

            flow.submitQr(case.wire)

            assertEquals(case.expectedState, flow.presentation.value.state, case.name)
            assertEquals(
                case.expectedRejection,
                flow.presentation.value.rejectionCategory,
                case.name,
            )
            assertEquals(
                if (case.expectedState == PairingUiState.CONNECTING) {
                    CompanionRootState.Connecting
                } else {
                    CompanionRootState.Unpaired
                },
                facade.status.value.rootState,
                case.name,
            )
        }
    }

    @Test
    fun `permission reset and retry are deterministic`(): Unit {
        val flow = CompanionFacade().pairingFlow(Clock { NOW })

        assertEquals(PairingUiState.INTRO, flow.presentation.value.state)
        flow.cameraPermissionDenied()
        assertEquals(PairingUiState.INTRO, flow.presentation.value.state)

        flow.startScanning()
        flow.cameraPermissionDenied()
        assertEquals(PairingUiState.CAMERA_DENIED, flow.presentation.value.state)
        assertNull(flow.presentation.value.rejectionCategory)

        flow.retryScanning()
        assertEquals(PairingUiState.SCANNING, flow.presentation.value.state)
        flow.scannerUnavailable()
        assertEquals(PairingUiState.SCANNER_UNAVAILABLE, flow.presentation.value.state)
        flow.retryScanning()
        assertEquals(PairingUiState.SCANNING, flow.presentation.value.state)
        flow.submitQr("malformed")
        assertEquals(PairingUiState.INVALID_QR, flow.presentation.value.state)

        flow.reset()
        assertEquals(PairingUiState.INTRO, flow.presentation.value.state)
        assertNull(flow.presentation.value.rejectionCategory)

        // A scanner callback arriving after reset cannot silently begin pairing.
        flow.submitQr(validQr(expiresAt = NOW + 1))
        assertEquals(PairingUiState.INTRO, flow.presentation.value.state)
    }

    @Test
    fun `accepted scan enters facade connecting once and ignores later scans and controls`(): Unit {
        val facade = CompanionFacade()
        val flow = facade.pairingFlow(Clock { NOW })
        flow.startScanning()

        flow.submitQr(validQr(expiresAt = NOW + 1))
        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
        assertEquals(PairingUiState.CONNECTING, flow.presentation.value.state)

        flow.submitQr("malformed")
        flow.cameraPermissionDenied()
        flow.retryScanning()
        flow.reset()

        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
        assertEquals(PairingUiState.CONNECTING, flow.presentation.value.state)
        assertNull(flow.presentation.value.rejectionCategory)
        assertEquals(
            "PairingQr(redacted)",
            assertNotNull(facade.takePendingPairingForConnection()).toString(),
        )
        assertNull(facade.takePendingPairingForConnection())
    }

    @Test
    fun `background before registration cancels the ephemeral pairing attempt`(): Unit {
        val facade = CompanionFacade()
        val flow = facade.pairingFlow(Clock { NOW })
        flow.startScanning()
        flow.submitQr(validQr(expiresAt = NOW + 1))

        facade.lock()
        flow.reset()

        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        assertEquals(PairingUiState.INTRO, flow.presentation.value.state)
        assertNull(facade.takePendingPairingForConnection())
    }

    @Test
    fun `terminal connection outcome clears an unconsumed QR credential`(): Unit {
        val facade = CompanionFacade()
        val flow = facade.pairingFlow(Clock { NOW })
        flow.startScanning()
        flow.submitQr(validQr(expiresAt = NOW + 1))

        facade.completeConnectionWithCompleteSnapshot()

        assertEquals(CompanionRootState.Online, facade.status.value.rootState)
        assertNull(facade.takePendingPairingForConnection())
    }

    @Test
    fun `background cancels registration even after its one-shot QR handoff`(): Unit {
        val facade = CompanionFacade()
        val flow = facade.pairingFlow(Clock { NOW })
        flow.startScanning()
        flow.submitQr(validQr(expiresAt = NOW + 1))
        assertNotNull(facade.takePendingPairingForConnection())

        facade.lock()
        flow.reset()

        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        assertEquals(PairingUiState.INTRO, flow.presentation.value.state)
        assertNull(facade.takePendingPairingForConnection())
    }

    @Test
    fun `stale flow cannot replace or cancel the active registration attempt`(): Unit {
        val facade = CompanionFacade()
        val activeFlow = facade.pairingFlow(Clock { NOW })
        val staleFlow = facade.pairingFlow(Clock { NOW })
        activeFlow.startScanning()
        activeFlow.submitQr(validQr(expiresAt = NOW + 1))
        assertNotNull(facade.takePendingPairingForConnection())

        staleFlow.startScanning()
        staleFlow.submitQr(validQr(expiresAt = NOW + 2))

        assertEquals(PairingUiState.SCANNING, staleFlow.presentation.value.state)
        facade.lock()
        activeFlow.reset()
        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
    }

    @Test
    fun `public representations never contain QR authority or credentials`(): Unit {
        val facade = CompanionFacade()
        val flow = facade.pairingFlow(Clock { NOW })
        flow.startScanning()
        flow.submitQr(validQr(expiresAt = NOW + 1))

        val representations = listOf(
            flow.toString(),
            flow.presentation.value.toString(),
            facade.status.value.toString(),
        ).joinToString()
        listOf(ORIGIN, PAIRING_ID, CREDENTIAL, "rotki_companion_pairing").forEach { secret ->
            assertFalse(secret in representations, secret)
        }
        assertEquals("PairingFlow(redacted)", flow.toString())
    }

    private data class QrCase(
        val name: String,
        val wire: String,
        val expectedState: PairingUiState,
        val expectedRejection: PairingRejectionCategory?,
    )
}

private const val NOW: Long = 1_786_550_300L
private const val ORIGIN: String = "https://rotki.example"
private const val PAIRING_ID: String = "AAECAwQFBgcICQoLDA0ODw"
private const val CREDENTIAL: String = "EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8"

private fun validQr(expiresAt: Long): String =
    """{"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"$ORIGIN","pairing_id":"$PAIRING_ID","pairing_credential":"$CREDENTIAL","expires_at":$expiresAt}"""
