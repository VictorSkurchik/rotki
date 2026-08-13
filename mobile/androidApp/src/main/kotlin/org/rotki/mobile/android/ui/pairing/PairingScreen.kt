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
import org.rotki.mobile.auth.PairingPresentation
import org.rotki.mobile.auth.PairingRejectionCategory
import org.rotki.mobile.auth.PairingUiState

@Composable
internal fun PairingScreen(
    presentation: PairingPresentation,
    onStartScanning: () -> Unit,
    onRetryScanning: () -> Unit,
    onCancelScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    scanner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    Surface(modifier = modifier.fillMaxSize()) {
        when (presentation.state) {
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
    primaryLabel: String,
    onPrimary: () -> Unit,
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
            Button(
                onClick = onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(primaryLabel)
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
            text = "Pairing code accepted",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "This build has validated the code. Secure Engine connection is the next development step; no portfolio data has been stored.",
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
