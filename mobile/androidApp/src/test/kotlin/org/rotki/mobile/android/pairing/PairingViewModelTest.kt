package org.rotki.mobile.android.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.PairingConnectionOutcome
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionRootState

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp(): Unit {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown(): Unit {
        Dispatchers.resetMain()
    }

    @Test
    fun `valid QR starts one connection and reports durable registration only`(): Unit {
        val facade = CompanionFacade()
        var connectionCalls = 0
        val viewModel = viewModel(
            facade = facade,
            connector = PendingPairingConnector {
                connectionCalls += 1
                PairingConnectionOutcome.REGISTERED
            },
        )

        viewModel.startScanning()
        viewModel.submitQr(validQr())
        viewModel.submitQr(validQr())

        assertEquals(PairingUiState.CONNECTING, viewModel.presentation.value.state)
        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
        assertEquals(PairingConnectionUiState.REGISTERED, viewModel.connectionState.value)
        assertEquals(1, connectionCalls)
    }

    @Test
    fun `permission denial and retry remain explicit presentation states`(): Unit {
        val viewModel = viewModel()

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

    @Test
    fun `local network permission gate retains no QR and starts a fresh scan`(): Unit {
        var connectionCalls = 0
        val viewModel = viewModel(
            connector = PendingPairingConnector {
                connectionCalls += 1
                PairingConnectionOutcome.REGISTERED
            },
        )
        viewModel.startScanning()

        viewModel.localNetworkPermissionRequired()

        assertEquals(
            PairingConnectionUiState.LOCAL_NETWORK_PERMISSION_REQUIRED,
            viewModel.connectionState.value,
        )
        assertEquals(PairingUiState.SCANNING, viewModel.presentation.value.state)
        assertEquals(0, connectionCalls)

        viewModel.localNetworkPermissionGranted()

        assertEquals(PairingConnectionUiState.IDLE, viewModel.connectionState.value)
        assertEquals(PairingUiState.SCANNING, viewModel.presentation.value.state)
        assertEquals(0, connectionCalls)
        assertEquals(1L, viewModel.scannerRestartGeneration.value)
    }

    @Test
    fun `foreground after camera permission dialog preserves denial UI`(): Unit {
        val viewModel = viewModel()
        viewModel.startScanning()
        viewModel.cameraPermissionDenied()

        viewModel.onInactive()
        viewModel.onForeground()

        assertEquals(PairingUiState.CAMERA_DENIED, viewModel.presentation.value.state)
        assertEquals(PairingConnectionUiState.IDLE, viewModel.connectionState.value)
    }

    @Test
    fun `foreground after local network permission dialog preserves rescan UI`(): Unit {
        val viewModel = viewModel()
        viewModel.startScanning()
        viewModel.localNetworkPermissionRequired()

        viewModel.onInactive()
        viewModel.onForeground()

        assertEquals(PairingUiState.SCANNING, viewModel.presentation.value.state)
        assertEquals(
            PairingConnectionUiState.LOCAL_NETWORK_PERMISSION_REQUIRED,
            viewModel.connectionState.value,
        )
    }

    @Test
    fun `inactive keeps one connection attempt and foreground resumes without a fresh QR`() =
        runTest {
            val facade = CompanionFacade()
            val started = CompletableDeferred<Unit>()
            val resumedOutcome = CompletableDeferred<PairingConnectionOutcome>()
            var connectionCalls = 0
            val viewModel = viewModel(
                facade = facade,
                connector = PendingPairingConnector {
                    connectionCalls += 1
                    started.complete(Unit)
                    resumedOutcome.await()
                },
            )
            viewModel.startScanning()
            viewModel.submitQr(validQr())
            started.await()

            viewModel.onInactive()

            assertEquals(1, connectionCalls)
            assertEquals(PairingConnectionUiState.CONNECTING, viewModel.connectionState.value)
            assertEquals(PairingUiState.CONNECTING, viewModel.presentation.value.state)

            viewModel.reset()
            viewModel.onForeground()
            assertEquals(1, connectionCalls)
            assertEquals(PairingConnectionUiState.CONNECTING, viewModel.connectionState.value)

            resumedOutcome.complete(PairingConnectionOutcome.REGISTERED)

            assertEquals(1, connectionCalls)
            assertEquals(PairingConnectionUiState.REGISTERED, viewModel.connectionState.value)
        }

    @Test
    fun `background after lifecycle lock returns an unfinished attempt to intro`() = runTest {
        val facade = CompanionFacade()
        val started = CompletableDeferred<Unit>()
        val backgroundOutcome = CompletableDeferred<PairingConnectionOutcome>()
        var connectionCalls = 0
        val viewModel = viewModel(
            facade = facade,
            connector = PendingPairingConnector {
                connectionCalls += 1
                started.complete(Unit)
                backgroundOutcome.await()
            },
        )
        viewModel.startScanning()
        viewModel.submitQr(validQr())
        started.await()

        facade.lock()
        viewModel.onBackground()
        assertEquals(PairingConnectionUiState.CONNECTING, viewModel.connectionState.value)

        backgroundOutcome.complete(PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND)
        viewModel.onForeground()

        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        assertEquals(PairingUiState.INTRO, viewModel.presentation.value.state)
        assertEquals(PairingConnectionUiState.IDLE, viewModel.connectionState.value)
        assertEquals(1, connectionCalls)
    }

    @Test
    fun `registered result survives inactive cover but is cleared after background lock`(): Unit {
        val facade = CompanionFacade()
        val viewModel = viewModel(
            facade = facade,
            connector = PendingPairingConnector { PairingConnectionOutcome.REGISTERED },
        )
        viewModel.startScanning()
        viewModel.submitQr(validQr())

        viewModel.onInactive()

        assertEquals(PairingConnectionUiState.REGISTERED, viewModel.connectionState.value)

        facade.lock()
        viewModel.onBackground()

        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        assertEquals(PairingConnectionUiState.IDLE, viewModel.connectionState.value)
        assertEquals(PairingUiState.INTRO, viewModel.presentation.value.state)
    }

    @Test
    fun `incomplete cleanup stays fail closed through background and foreground`() = runTest {
        val facade = CompanionFacade()
        val started = CompletableDeferred<Unit>()
        val backgroundOutcome = CompletableDeferred<PairingConnectionOutcome>()
        val viewModel = viewModel(
            facade = facade,
            connector = PendingPairingConnector {
                started.complete(Unit)
                backgroundOutcome.await()
            },
        )
        viewModel.startScanning()
        viewModel.submitQr(validQr())
        started.await()

        facade.lock()
        viewModel.onBackground()
        assertEquals(PairingConnectionUiState.CONNECTING, viewModel.connectionState.value)

        backgroundOutcome.complete(PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE)
        viewModel.onForeground()

        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        assertEquals(
            PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE,
            viewModel.connectionState.value,
        )
        assertEquals(PairingUiState.INTRO, viewModel.presentation.value.state)
    }

    @Test
    fun `cleanup retry unlocks only after shared proves local material absent`(): Unit {
        val viewModel = viewModel(
            cleanupConnector = PendingPairingCleanupConnector {
                PairingConnectionOutcome.NO_PENDING_PAIRING
            },
            initialConnectionState = PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE,
        )

        viewModel.retryIncompleteCleanup()

        assertEquals(PairingConnectionUiState.IDLE, viewModel.connectionState.value)
        assertEquals(PairingUiState.INTRO, viewModel.presentation.value.state)
    }

    @Test
    fun `failed cleanup retry remains fail closed`(): Unit {
        val viewModel = viewModel(
            cleanupConnector = PendingPairingCleanupConnector {
                PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
            },
            initialConnectionState = PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE,
        )

        viewModel.retryIncompleteCleanup()

        assertEquals(
            PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE,
            viewModel.connectionState.value,
        )
    }

    @Test
    fun `connection outcomes map to coarse redacted UI states`(): Unit {
        listOf(
            PairingConnectionOutcome.PAIRING_EXPIRED to PairingConnectionUiState.PAIRING_EXPIRED,
            PairingConnectionOutcome.PAIRING_UNAVAILABLE to
                PairingConnectionUiState.PAIRING_UNAVAILABLE,
            PairingConnectionOutcome.INCOMPATIBLE to PairingConnectionUiState.INCOMPATIBLE,
            PairingConnectionOutcome.RATE_LIMITED to PairingConnectionUiState.RATE_LIMITED,
            PairingConnectionOutcome.NETWORK_UNAVAILABLE to
                PairingConnectionUiState.NETWORK_UNAVAILABLE,
            PairingConnectionOutcome.LOCAL_SECURITY_UNAVAILABLE to
                PairingConnectionUiState.LOCAL_SECURITY_UNAVAILABLE,
            PairingConnectionOutcome.LOCAL_STORAGE_UNAVAILABLE to
                PairingConnectionUiState.LOCAL_STORAGE_UNAVAILABLE,
            PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE to
                PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE,
            PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE to
                PairingConnectionUiState.UNEXPECTED,
            PairingConnectionOutcome.UNEXPECTED_FAILURE to PairingConnectionUiState.UNEXPECTED,
        ).forEach { (outcome, expected) ->
            val viewModel = viewModel(connector = PendingPairingConnector { outcome })
            viewModel.startScanning()

            viewModel.submitQr(validQr())

            assertEquals(outcome.code, expected, viewModel.connectionState.value)
        }
    }

    @Test
    fun `retry routes unreachable state through transport restoration`(): Unit {
        val facade = CompanionFacade()
        val pairing = facade.pairingFlow(Clock { NOW })
        pairing.startScanning()
        pairing.submitQr(validQr())
        facade.transportBudgetExhausted()
        val viewModel = viewModel(facade = facade)

        viewModel.retryConnection()

        assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
    }

    @Test
    fun `new scan recovers from a terminal connection failure without a spinner loop`(): Unit {
        val facade = CompanionFacade()
        val viewModel = viewModel(
            facade = facade,
            connector = PendingPairingConnector {
                facade.lock()
                PairingConnectionOutcome.NETWORK_UNAVAILABLE
            },
        )
        viewModel.startScanning()
        viewModel.submitQr(validQr())
        assertEquals(PairingConnectionUiState.NETWORK_UNAVAILABLE, viewModel.connectionState.value)
        assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)

        viewModel.startScanning()

        assertEquals(PairingConnectionUiState.IDLE, viewModel.connectionState.value)
        assertEquals(PairingUiState.SCANNING, viewModel.presentation.value.state)
    }

    private fun viewModel(
        facade: CompanionFacade = CompanionFacade(),
        connector: PendingPairingConnector = PendingPairingConnector {
            PairingConnectionOutcome.NO_PENDING_PAIRING
        },
        cleanupConnector: PendingPairingCleanupConnector = PendingPairingCleanupConnector {
            PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
        },
        initialConnectionState: PairingConnectionUiState = PairingConnectionUiState.IDLE,
    ): PairingViewModel = PairingViewModel(
        facade = facade,
        clock = Clock { NOW },
        connector = connector,
        cleanupConnector = cleanupConnector,
        initialConnectionState = initialConnectionState,
    )
}

private const val NOW: Long = 1_786_550_300L

private fun validQr(): String =
    """{"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"https://rotki.example","pairing_id":"AAECAwQFBgcICQoLDA0ODw","pairing_credential":"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8","expires_at":${NOW + 60}}"""
