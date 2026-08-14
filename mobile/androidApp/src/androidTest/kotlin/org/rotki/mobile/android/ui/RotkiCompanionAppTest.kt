package org.rotki.mobile.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.rotki.mobile.android.pairing.PairingConnectionUiState
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.SnapshotCoverage

class RotkiCompanionAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unpairedLandingStartsScanning() {
        var scanRequested = false
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Unpaired),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                onStartScanning = { scanRequested = true },
            )
        }

        composeRule.onNodeWithTag(UiTags.PAIRING_INTRO).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.PAIRING_SCAN_BUTTON).performClick()
        composeRule.runOnIdle { assertTrue(scanRequested) }
    }

    @Test
    fun scanningAndPairingErrorsHaveDeliberateScreens() {
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Unpaired),
                pairing = PairingPresentationForTest(PairingUiState.INVALID_QR),
            )
        }

        composeRule.onNodeWithTag(UiTags.PAIRING_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("That is not a Rotki pairing code").assertIsDisplayed()
    }

    @Test
    fun unsupportedPairingFormatRecommendsAnUpdateInsteadOfRescanning() {
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Unpaired),
                pairing = UnsupportedPresentationForTest(),
            )
        }

        composeRule.onNodeWithText("Update Rotki Companion").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Scan again").assertIsNotDisplayed()
    }

    @Test
    fun snapshotStatesExposeAllFourDestinations() {
        composeRule.setContent {
            TestApp(
                status =
                    CompanionStatus(
                        rootState = CompanionRootState.Online,
                        snapshotCoverage = SnapshotCoverage.Complete,
                    ),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
            )
        }

        composeRule.onNodeWithTag(UiTags.HOME_SHELL).assertIsDisplayed()
        listOf("Overview", "Portfolio", "History", "Sources").forEach { destination ->
            composeRule.onNodeWithText(destination).assertIsDisplayed()
        }
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("Recent activity").assertIsDisplayed()
    }

    @Test
    fun privacyCoverObscuresEveryRootScreen() {
        composeRule.setContent {
            TestApp(
                status =
                    CompanionStatus(
                        rootState = CompanionRootState.Online,
                        snapshotCoverage = SnapshotCoverage.Complete,
                    ),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                privacyCovered = true,
            )
        }

        composeRule.onNodeWithTag(UiTags.PRIVACY_COVER).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Portfolio hidden").assertIsDisplayed()
        composeRule.onNodeWithText("Portfolio overview").assertIsNotDisplayed()
        composeRule.onNodeWithText("Sources").assertIsNotDisplayed()
    }

    @Test
    fun pairingConnectionShowsProgressWithoutClaimingSuccess() {
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Connecting),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                pairingConnectionState = PairingConnectionUiState.CONNECTING,
            )
        }

        composeRule.onNodeWithTag(UiTags.PAIRING_CONNECTING).assertIsDisplayed()
        composeRule.onNodeWithText("Registering this device").assertIsDisplayed()
        composeRule.onNodeWithText("Pairing code accepted").assertIsNotDisplayed()
    }

    @Test
    fun registeredDeviceKeepsProofAndSyncAsTheNextStep() {
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Connecting),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                pairingConnectionState = PairingConnectionUiState.REGISTERED,
            )
        }

        composeRule.onNodeWithTag(UiTags.PAIRING_REGISTERED).assertIsDisplayed()
        composeRule.onNodeWithText("Device registered").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "The device key and Engine registration are saved. Device proof and portfolio sync are the next development step.",
            ).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.HOME_SHELL).assertIsNotDisplayed()
    }

    @Test
    fun networkFailureOffersARescanWithoutEngineMessage() {
        var scanRequested = false
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Unpaired),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                pairingConnectionState = PairingConnectionUiState.NETWORK_UNAVAILABLE,
                onStartScanning = { scanRequested = true },
            )
        }

        composeRule.onNodeWithText("Rotki Engine is out of reach").assertIsDisplayed()
        composeRule.onNodeWithText("Scan again").performClick()
        composeRule.runOnIdle { assertTrue(scanRequested) }
    }

    @Test
    fun rateLimitIsPresentedAsBusyAndRequiresAFreshCode() {
        var scanRequested = false
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Unpaired),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                pairingConnectionState = PairingConnectionUiState.RATE_LIMITED,
                onStartScanning = { scanRequested = true },
            )
        }

        composeRule.onNodeWithText("Rotki Engine is busy").assertIsDisplayed()
        composeRule.onNodeWithText("Rotki Engine is out of reach").assertIsNotDisplayed()
        composeRule.onNodeWithText("Scan a new code").performClick()
        composeRule.runOnIdle { assertTrue(scanRequested) }
    }

    @Test
    fun incompleteLocalCleanupOverridesDeviceLockedAndOffersOnlyCleanupRetry() {
        var cleanupRequested = false
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.DeviceLocked),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                pairingConnectionState = PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE,
                onRetryCleanup = { cleanupRequested = true },
            )
        }

        composeRule.onNodeWithText("Local cleanup could not be verified").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Rotki Companion remains locked because removal of the local device key " +
                    "and registration record could not be confirmed. Do not pair again yet.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Scan again").assertIsNotDisplayed()
        composeRule.onNodeWithText("Scan a new code").assertIsNotDisplayed()
        composeRule.onNodeWithText("Back").assertIsNotDisplayed()
        composeRule.onNodeWithText("Portfolio locked").assertIsNotDisplayed()
        composeRule.onNodeWithText("Retry cleanup").performClick()
        composeRule.runOnIdle { assertTrue(cleanupRequested) }
    }

    @Test
    fun localNetworkPermissionExplainsThatTheCodeWasNotSaved() {
        var permissionRequested = false
        composeRule.setContent {
            TestApp(
                status = status(CompanionRootState.Unpaired),
                pairing = PairingPresentationForTest(PairingUiState.INTRO),
                pairingConnectionState =
                    PairingConnectionUiState.LOCAL_NETWORK_PERMISSION_REQUIRED,
                onRequestLocalNetworkPermission = { permissionRequested = true },
            )
        }

        composeRule.onNodeWithText("Local network access needed").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Allow nearby device access, then scan the pairing code again. The code was not saved.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").performClick()
        composeRule.runOnIdle { assertTrue(permissionRequested) }
    }
}

@Suppress("LongParameterList")
@androidx.compose.runtime.Composable
private fun TestApp(
    status: CompanionStatus,
    pairing: PairingPresentation,
    pairingConnectionState: PairingConnectionUiState = PairingConnectionUiState.IDLE,
    privacyCovered: Boolean = false,
    onStartScanning: () -> Unit = {},
    onRequestLocalNetworkPermission: () -> Unit = {},
    onRetryCleanup: () -> Unit = {},
): Unit =
    RotkiCompanionApp(
        status = status,
        pairing = pairing,
        pairingConnectionState = pairingConnectionState,
        privacyCovered = privacyCovered,
        onStartScanning = onStartScanning,
        onRetryScanning = onStartScanning,
        onCancelScanning = {},
        onOpenCameraSettings = {},
        onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
        onRetryCleanup = onRetryCleanup,
        onRetryConnection = {},
        scanner = { Box(modifier = Modifier) },
    )

private fun status(rootState: CompanionRootState): CompanionStatus =
    CompanionStatus(
        rootState = rootState,
        snapshotCoverage = SnapshotCoverage.Absent,
    )

private fun PairingPresentationForTest(state: PairingUiState): PairingPresentation =
    when (state) {
        PairingUiState.INTRO -> {
            org.rotki.mobile
                .CompanionFacade()
                .pairingFlow { 0L }
                .presentation.value
        }

        PairingUiState.INVALID_QR -> {
            org.rotki.mobile
                .CompanionFacade()
                .pairingFlow { 0L }
                .also { flow ->
                    flow.startScanning()
                    flow.submitQr("invalid")
                }.presentation.value
        }

        else -> {
            error("Test helper has no fixture for $state")
        }
    }

private fun UnsupportedPresentationForTest(): PairingPresentation =
    org.rotki.mobile
        .CompanionFacade()
        .pairingFlow { 0L }
        .also { flow ->
            flow.startScanning()
            flow.submitQr(
                """{"kind":"rotki_companion_pairing","format_version":2,"engine_origin":"https://rotki.example","pairing_id":"AAECAwQFBgcICQoLDA0ODw","pairing_credential":"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8","expires_at":1}""",
            )
        }.presentation.value
