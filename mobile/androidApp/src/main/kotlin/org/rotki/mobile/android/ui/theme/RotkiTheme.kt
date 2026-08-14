package org.rotki.mobile.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF4E5BA6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E5FF),
        onPrimaryContainer = Color(0xFF18245F),
        secondary = Color(0xFF5D6475),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE1E6F9),
        onSecondaryContainer = Color(0xFF191E2C),
        tertiary = Color(0xFFB84B16),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDBCB),
        onTertiaryContainer = Color(0xFF3A0B00),
        background = Color(0xFFF9F9FF),
        onBackground = Color(0xFF1B1B20),
        surface = Color(0xFFF9F9FF),
        onSurface = Color(0xFF1B1B20),
        surfaceVariant = Color(0xFFE3E2E9),
        onSurfaceVariant = Color(0xFF46464F),
        outline = Color(0xFF777680),
        error = Color(0xFFBA1A1A),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFBBC3FF),
        onPrimary = Color(0xFF1E2B69),
        primaryContainer = Color(0xFF354282),
        onPrimaryContainer = Color(0xFFE0E5FF),
        secondary = Color(0xFFC4C9DD),
        onSecondary = Color(0xFF2E3343),
        secondaryContainer = Color(0xFF454A5A),
        onSecondaryContainer = Color(0xFFE1E6F9),
        tertiary = Color(0xFFFFB692),
        onTertiary = Color(0xFF612000),
        tertiaryContainer = Color(0xFF893300),
        onTertiaryContainer = Color(0xFFFFDBCB),
        background = Color(0xFF121318),
        onBackground = Color(0xFFE4E1E9),
        surface = Color(0xFF121318),
        onSurface = Color(0xFFE4E1E9),
        surfaceVariant = Color(0xFF46464F),
        onSurfaceVariant = Color(0xFFC7C5D0),
        outline = Color(0xFF91909A),
        error = Color(0xFFFFB4AB),
    )

private val RotkiShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
    )

@Composable
internal fun RotkiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = RotkiShapes,
        content = content,
    )
}
