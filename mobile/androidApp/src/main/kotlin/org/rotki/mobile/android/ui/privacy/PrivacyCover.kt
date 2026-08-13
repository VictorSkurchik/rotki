package org.rotki.mobile.android.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import org.rotki.mobile.android.ui.UiTags

@Composable
internal fun PrivacyCover(modifier: Modifier = Modifier): Unit {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111217))
            .testTag(UiTags.PRIVACY_COVER)
            .semantics { contentDescription = "Portfolio hidden" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF30364F)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "R",
                    color = Color(0xFFDDE2FF),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = "Portfolio hidden",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Return to Rotki to unlock it.",
                color = Color(0xFFC7C5D0),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
