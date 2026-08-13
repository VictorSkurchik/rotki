package org.rotki.mobile.android.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.rotki.mobile.android.ui.UiTags
import org.rotki.mobile.android.pairing.PairingConnectionUiState
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.auth.PairingRejectionCategory
import org.rotki.mobile.auth.PairingUiState

@Composable
internal fun PairingScreen(
    presentation: PairingPresentation,
    connectionState: PairingConnectionUiState,
    onStartScanning: () -> Unit,
    onRetryScanning: () -> Unit,
    onCancelScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestLocalNetworkPermission: () -> Unit,
    onRetryCleanup: () -> Unit,
    scanner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    Surface(modifier = modifier.fillMaxSize()) {
        if (connectionState != PairingConnectionUiState.IDLE) {
            PairingConnectionStage(
                state = connectionState,
                onScanAgain = onRetryScanning,
                onBack = onCancelScanning,
                onOpenSettings = onOpenSettings,
                onRequestLocalNetworkPermission = onRequestLocalNetworkPermission,
                onRetryCleanup = onRetryCleanup,
            )
        } else when (presentation.state) {
            PairingUiState.INTRO -> PairingIntro(onStartScanning)
            PairingUiState.SCANNING -> ScannerStage(
                onCancel = onCancelScanning,
                scanner = scanner,
            )
            PairingUiState.CAMERA_DENIED -> PairingProblem(
                title = "Camera access is off",
                message = "Allow camera access to scan the one-time QR code shown by Rotki.",
                primaryLabel = "Try again",
                onPrimary = onRetryScanning,
                secondaryLabel = "Open settings",
                onSecondary = onOpenSettings,
            )
            PairingUiState.SCANNER_UNAVAILABLE -> PairingProblem(
                title = "Camera unavailable",
                message = "Rotki could not start the QR scanner. Close other camera apps and try again.",
                primaryLabel = "Try again",
                onPrimary = onRetryScanning,
            )
            PairingUiState.INVALID_QR -> if (
                presentation.rejectionCategory == PairingRejectionCategory.UNSUPPORTED
            ) {
                PairingProblem(
                    title = "Update Rotki Companion",
                    message = "This pairing code uses a newer format. Update the app before trying again.",
                    primaryLabel = "Back",
                    onPrimary = onCancelScanning,
                )
            } else {
                PairingProblem(
                    title = "That is not a Rotki pairing code",
                    message = "Nothing was saved. Scan a fresh code from your Rotki profile.",
                    primaryLabel = "Scan again",
                    onPrimary = onRetryScanning,
                )
            }
            PairingUiState.EXPIRED_QR -> PairingProblem(
                title = "This pairing code expired",
                message = "Create a new code in Rotki, then scan it before its timer ends.",
                primaryLabel = "Scan a new code",
                onPrimary = onRetryScanning,
            )
            PairingUiState.CONNECTING -> ConnectingStage()
        }
    }
}

@Composable
private fun PairingIntro(onStartScanning: () -> Unit): Unit {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag(UiTags.PAIRING_INTRO),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RotkiWordmark()
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PairingIllustration()
            Text(
                text = "Your portfolio, close at hand",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Connect securely to your self-hosted Rotki. Your keys and portfolio stay under your control.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(UiTags.PAIRING_SCAN_BUTTON),
            ) {
                Text("Scan pairing QR")
            }
            Text(
                text = "In Rotki: Settings → Devices → Pair a device",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ScannerStage(
    onCancel: () -> Unit,
    scanner: @Composable () -> Unit,
): Unit {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RotkiWordmark()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Scan pairing QR",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Keep the entire code inside the frame.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black)
                .testTag(UiTags.PAIRING_SCANNER)
                .semantics { contentDescription = "QR scanner" },
            contentAlignment = Alignment.Center,
        ) {
            scanner()
            Box(
                modifier = Modifier
                    .size(248.dp)
                    .border(
                        width = 3.dp,
                        color = Color.White.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(24.dp),
                    ),
            )
        }
        FilledTonalButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun PairingProblem(
    title: String,
    message: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
): Unit {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(UiTags.PAIRING_ERROR),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RotkiWordmark()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "!",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (primaryLabel != null && onPrimary != null) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(primaryLabel)
                }
            }
            if (secondaryLabel != null && onSecondary != null) {
                FilledTonalButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun ConnectingStage(): Unit {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(UiTags.PAIRING_CONNECTING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Registering this device",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Keep Rotki Companion open while it creates a device key and securely registers it with your Engine.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PairingConnectionStage(
    state: PairingConnectionUiState,
    onScanAgain: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestLocalNetworkPermission: () -> Unit,
    onRetryCleanup: () -> Unit,
): Unit {
    when (state) {
        PairingConnectionUiState.IDLE -> Unit
        PairingConnectionUiState.CONNECTING -> ConnectingStage()
        PairingConnectionUiState.CLEANING_UP -> CleanupProgressStage()
        PairingConnectionUiState.REGISTERED -> RegisteredStage()
        PairingConnectionUiState.LOCAL_NETWORK_PERMISSION_REQUIRED -> PairingProblem(
            title = "Local network access needed",
            message = "Allow nearby device access, then scan the pairing code again. The code was not saved.",
            primaryLabel = "Allow access",
            onPrimary = onRequestLocalNetworkPermission,
            secondaryLabel = "Open settings",
            onSecondary = onOpenSettings,
        )
        PairingConnectionUiState.PAIRING_EXPIRED -> PairingProblem(
            title = "This pairing code expired",
            message = "Create a fresh code in Rotki, then scan it before its timer ends.",
            primaryLabel = "Scan a new code",
            onPrimary = onScanAgain,
        )
        PairingConnectionUiState.PAIRING_UNAVAILABLE -> PairingProblem(
            title = "This pairing code is no longer available",
            message = "The code may have expired or already been used. Create a new one in Rotki.",
            primaryLabel = "Scan a new code",
            onPrimary = onScanAgain,
        )
        PairingConnectionUiState.INCOMPATIBLE -> PairingProblem(
            title = "Rotki needs an update",
            message = "This Engine does not support the Companion protocol required by this app.",
            primaryLabel = "Back",
            onPrimary = onBack,
        )
        PairingConnectionUiState.RATE_LIMITED -> PairingProblem(
            title = "Rotki Engine is busy",
            message = "Wait a moment, create a fresh pairing code in Rotki, then scan it again.",
            primaryLabel = "Scan a new code",
            onPrimary = onScanAgain,
        )
        PairingConnectionUiState.NETWORK_UNAVAILABLE -> PairingProblem(
            title = "Rotki Engine is out of reach",
            message = "Check this device's connection to your Engine, then scan the code again.",
            primaryLabel = "Scan again",
            onPrimary = onScanAgain,
        )
        PairingConnectionUiState.LOCAL_SECURITY_UNAVAILABLE -> PairingProblem(
            title = "Secure device key unavailable",
            message = "Rotki Companion could not create the protected key required to register this device.",
            primaryLabel = "Back",
            onPrimary = onBack,
        )
        PairingConnectionUiState.LOCAL_STORAGE_UNAVAILABLE -> PairingProblem(
            title = "Device registration was not saved",
            message = "Nothing is considered paired. Check available storage and scan a fresh code.",
            primaryLabel = "Scan a new code",
            onPrimary = onScanAgain,
        )
        PairingConnectionUiState.LOCAL_CLEANUP_INCOMPLETE -> PairingProblem(
            title = "Local cleanup could not be verified",
            message = "Rotki Companion remains locked because removal of the local device key " +
                "and registration record could not be confirmed. Do not pair again yet.",
            primaryLabel = "Retry cleanup",
            onPrimary = onRetryCleanup,
        )
        PairingConnectionUiState.UNEXPECTED -> PairingProblem(
            title = "Device registration did not finish",
            message = "No portfolio data was stored. Return to Rotki and create a fresh pairing code.",
            primaryLabel = "Scan a new code",
            onPrimary = onScanAgain,
        )
    }
}

@Composable
private fun CleanupProgressStage(): Unit {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(UiTags.PAIRING_CONNECTING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Finishing local cleanup",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Rotki Companion stays locked until the local key and registration record are proven absent.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RegisteredStage(): Unit {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(UiTags.PAIRING_REGISTERED),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Device registered",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The device key and Engine registration are saved. Device proof and portfolio sync are the next development step.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RotkiWordmark(): Unit {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "R",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "rotki",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PairingIllustration(): Unit {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .border(
                    width = 8.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(18.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}
