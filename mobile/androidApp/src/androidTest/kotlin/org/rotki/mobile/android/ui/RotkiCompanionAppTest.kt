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
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.SnapshotCoverage

class RotkiCompanionAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unpairedLandingStartsScanning(): Unit {
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
    fun scanningAndPairingErrorsHaveDeliberateScreens(): Unit {
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
    fun unsupportedPairingFormatRecommendsAnUpdateInsteadOfRescanning(): Unit {
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
    fun snapshotStatesExposeAllFourDestinations(): Unit {
        composeRule.setContent {
            TestApp(
                status = CompanionStatus(
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
    fun privacyCoverObscuresEveryRootScreen(): Unit {
        composeRule.setContent {
            TestApp(
                status = CompanionStatus(
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
}

@Suppress("LongParameterList")
@androidx.compose.runtime.Composable
private fun TestApp(
    status: CompanionStatus,
    pairing: PairingPresentation,
    privacyCovered: Boolean = false,
    onStartScanning: () -> Unit = {},
): Unit = RotkiCompanionApp(
    status = status,
    pairing = pairing,
    privacyCovered = privacyCovered,
    onStartScanning = onStartScanning,
    onRetryScanning = {},
    onCancelScanning = {},
    onOpenCameraSettings = {},
    onRetryConnection = {},
    scanner = { Box(modifier = Modifier) },
)

private fun status(rootState: CompanionRootState): CompanionStatus = CompanionStatus(
    rootState = rootState,
    snapshotCoverage = SnapshotCoverage.Absent,
)

private fun PairingPresentationForTest(state: PairingUiState): PairingPresentation =
    when (state) {
        PairingUiState.INTRO -> org.rotki.mobile.CompanionFacade()
            .pairingFlow { 0L }
            .presentation.value
        PairingUiState.INVALID_QR -> org.rotki.mobile.CompanionFacade()
            .pairingFlow { 0L }
            .also { flow ->
                flow.startScanning()
                flow.submitQr("invalid")
            }
            .presentation.value
        else -> error("Test helper has no fixture for $state")
    }

private fun UnsupportedPresentationForTest(): PairingPresentation =
    org.rotki.mobile.CompanionFacade()
        .pairingFlow { 0L }
        .also { flow ->
            flow.startScanning()
            flow.submitQr(
                """{"kind":"rotki_companion_pairing","format_version":2,"engine_origin":"https://rotki.example","pairing_id":"AAECAwQFBgcICQoLDA0ODw","pairing_credential":"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8","expires_at":1}""",
            )
        }
        .presentation.value
