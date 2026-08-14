package org.rotki.mobile.android.ui

import androidx.compose.runtime.Composable
import org.rotki.mobile.android.pairing.PairingConnectionUiState
import org.rotki.mobile.android.ui.pairing.PairingScreen
import org.rotki.mobile.android.ui.privacy.PrivacyCover
import org.rotki.mobile.android.ui.shell.CompanionHome
import org.rotki.mobile.android.ui.shell.HomeConnectionBannerState
import org.rotki.mobile.android.ui.state.RecoveryScreen
import org.rotki.mobile.android.ui.theme.RotkiTheme
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.auth.PairingUiState
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.SnapshotCoverage

@Composable
internal fun RotkiCompanionApp(
    status: CompanionStatus,
    pairing: PairingPresentation,
    pairingConnectionState: PairingConnectionUiState,
    privacyCovered: Boolean,
    onStartScanning: () -> Unit,
    onRetryScanning: () -> Unit,
    onCancelScanning: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onRequestLocalNetworkPermission: () -> Unit,
    onRetryCleanup: () -> Unit,
    onRetryConnection: () -> Unit,
    scanner: @Composable () -> Unit,
) {
    RotkiTheme {
        if (privacyCovered) {
            PrivacyCover()
        } else {
            CompanionContent(
                status = status,
                pairing = pairing,
                pairingConnectionState = pairingConnectionState,
                onStartScanning = onStartScanning,
                onRetryScanning = onRetryScanning,
                onCancelScanning = onCancelScanning,
                onOpenCameraSettings = onOpenCameraSettings,
                onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                onRetryCleanup = onRetryCleanup,
                onRetryConnection = onRetryConnection,
                scanner = scanner,
            )
        }
    }
}

@Composable
private fun CompanionContent(
    status: CompanionStatus,
    pairing: PairingPresentation,
    pairingConnectionState: PairingConnectionUiState,
    onStartScanning: () -> Unit,
    onRetryScanning: () -> Unit,
    onCancelScanning: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onRequestLocalNetworkPermission: () -> Unit,
    onRetryCleanup: () -> Unit,
    onRetryConnection: () -> Unit,
    scanner: @Composable () -> Unit,
) {
    if (pairingConnectionState == PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE ||
        pairingConnectionState == PairingConnectionUiState.CLEANING_UP
    ) {
        PairingScreen(
            presentation = pairing,
            connectionState = pairingConnectionState,
            onStartScanning = onStartScanning,
            onRetryScanning = onRetryScanning,
            onCancelScanning = onCancelScanning,
            onOpenSettings = onOpenCameraSettings,
            onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
            onRetryCleanup = onRetryCleanup,
            scanner = scanner,
        )
        return
    }
    when (status.rootState) {
        CompanionRootState.Unpaired -> {
            PairingScreen(
                presentation = pairing,
                connectionState = pairingConnectionState,
                onStartScanning = onStartScanning,
                onRetryScanning = onRetryScanning,
                onCancelScanning = onCancelScanning,
                onOpenSettings = onOpenCameraSettings,
                onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                onRetryCleanup = onRetryCleanup,
                scanner = scanner,
            )
        }

        CompanionRootState.Connecting -> {
            if (pairing.state == PairingUiState.CONNECTING ||
                pairingConnectionState != PairingConnectionUiState.IDLE
            ) {
                PairingScreen(
                    presentation = pairing,
                    connectionState = pairingConnectionState,
                    onStartScanning = onStartScanning,
                    onRetryScanning = onRetryScanning,
                    onCancelScanning = onCancelScanning,
                    onOpenSettings = onOpenCameraSettings,
                    onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                    onRetryCleanup = onRetryCleanup,
                    scanner = scanner,
                )
            } else {
                RecoveryScreen(
                    symbol = "↗",
                    title = "Connecting securely",
                    message = "Proving this device to your Rotki Engine.",
                )
            }
        }

        CompanionRootState.DeviceLocked -> {
            RecoveryScreen(
                symbol = "•",
                title = "Portfolio locked",
                message = "Rotki will request device authentication before restoring your secure portfolio.",
            )
        }

        CompanionRootState.EngineLocked -> {
            RecoveryScreen(
                symbol = "R",
                title = "Open your Profile in Rotki",
                message = "The Engine is reachable, but your paired Profile is locked.",
                actionLabel = "Try again",
                onAction = onRetryConnection,
            )
        }

        CompanionRootState.ProfileMismatch -> {
            RecoveryScreen(
                symbol = "≠",
                title = "A different Profile is open",
                message = "Open the Profile paired with this device, then try again.",
                actionLabel = "Check again",
                onAction = onRetryConnection,
            )
        }

        CompanionRootState.Incompatible -> {
            RecoveryScreen(
                symbol = "↑",
                title = "Rotki needs an update",
                message = "This Engine does not yet support the Companion contract required by this app.",
                actionLabel = "Check again",
                onAction = onRetryConnection,
            )
        }

        CompanionRootState.Revoked -> {
            RecoveryScreen(
                symbol = "×",
                title = "Device access was revoked",
                message = "Pair this installation again from the Rotki Profile that should authorize it.",
            )
        }

        CompanionRootState.Online,
        CompanionRootState.Refreshing,
        CompanionRootState.Degraded,
        CompanionRootState.Unreachable,
        -> {
            val bannerState = status.toHomeConnectionBannerState()
            if (bannerState != null) {
                CompanionHome(bannerState = bannerState)
            } else {
                RecoveryScreen(
                    symbol = if (status.rootState == CompanionRootState.Unreachable) "…" else "↻",
                    title =
                        if (status.rootState == CompanionRootState.Unreachable) {
                            "Rotki is out of reach"
                        } else {
                            "Preparing your portfolio"
                        },
                    message =
                        if (status.rootState == CompanionRootState.Unreachable) {
                            "No offline snapshot is available yet. Reconnect to your Engine to continue."
                        } else {
                            "Keep the app open while Rotki prepares the first secure snapshot."
                        },
                    actionLabel =
                        if (status.rootState == CompanionRootState.Unreachable) {
                            "Try again"
                        } else {
                            null
                        },
                    onAction =
                        if (status.rootState == CompanionRootState.Unreachable) {
                            onRetryConnection
                        } else {
                            null
                        },
                )
            }
        }
    }
}

internal fun CompanionStatus.toHomeConnectionBannerState(): HomeConnectionBannerState? {
    if (snapshotCoverage == SnapshotCoverage.Absent) {
        return null
    }
    return when (rootState) {
        CompanionRootState.Online -> {
            HomeConnectionBannerState.CONNECTED
        }

        CompanionRootState.Refreshing -> {
            when (snapshotCoverage) {
                SnapshotCoverage.Complete -> HomeConnectionBannerState.REFRESHING_COMPLETE
                SnapshotCoverage.Degraded -> HomeConnectionBannerState.REFRESHING_PARTIAL
                SnapshotCoverage.Absent -> null
            }
        }

        CompanionRootState.Degraded -> {
            HomeConnectionBannerState.DEGRADED
        }

        CompanionRootState.Unreachable -> {
            HomeConnectionBannerState.UNREACHABLE
        }

        CompanionRootState.Connecting,
        CompanionRootState.DeviceLocked,
        CompanionRootState.EngineLocked,
        CompanionRootState.Incompatible,
        CompanionRootState.ProfileMismatch,
        CompanionRootState.Revoked,
        CompanionRootState.Unpaired,
        -> {
            null
        }
    }
}
