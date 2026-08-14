package org.rotki.mobile.android.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.rotki.mobile.android.ui.UiTags
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.SnapshotCoverage

private enum class HomeDestination(
    val label: String,
    val title: String,
    val description: String,
) {
    Overview(
        label = "Overview",
        title = "Portfolio overview",
        description = "Net worth and freshness will appear after the first secure snapshot.",
    ),
    Portfolio(
        label = "Portfolio",
        title = "Your assets",
        description = "Assets and liabilities stay exact and read-only in the Companion.",
    ),
    History(
        label = "History",
        title = "Recent activity",
        description = "A bounded, privacy-safe activity feed will live here.",
    ),
    Sources(
        label = "Sources",
        title = "Portfolio sources",
        description = "See freshness and request updates for each connected source.",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionHome(
    status: CompanionStatus,
    modifier: Modifier = Modifier,
) {
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val destination = HomeDestination.entries[selectedIndex]
    Scaffold(
        modifier = modifier.testTag(UiTags.HOME_SHELL),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "rotki",
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                HomeDestination.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = {
                            DestinationIcon(
                                destination = item,
                                selected = selectedIndex == index,
                            )
                        },
                        label = { Text(item.label) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ConnectionBanner(status)
            DestinationPlaceholder(destination)
        }
    }
}

@Composable
private fun ConnectionBanner(status: CompanionStatus) {
    val banner =
        when (status.rootState) {
            CompanionRootState.Refreshing -> {
                BannerCopy(
                    label = "Refreshing",
                    detail =
                        if (status.snapshotCoverage == SnapshotCoverage.Complete) {
                            "Keeping your last complete portfolio visible."
                        } else {
                            "Keeping available last-known values visible."
                        },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            CompanionRootState.Degraded -> {
                BannerCopy(
                    label = "Some sources need attention",
                    detail = "Last-known values are preserved where possible.",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            CompanionRootState.Unreachable -> {
                BannerCopy(
                    label = "Showing offline snapshot",
                    detail = "Rotki will reconnect while this app is open.",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                BannerCopy(
                    label = "Connected securely",
                    detail = "Your Engine remains authoritative.",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = banner.container,
        contentColor = banner.foreground,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .background(banner.foreground, CircleShape),
            )
            Column {
                Text(
                    text = banner.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = banner.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DestinationPlaceholder(destination: HomeDestination) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DestinationIcon(destination = destination, selected = true, size = 34)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = destination.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = destination.description,
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DestinationIcon(
    destination: HomeDestination,
    selected: Boolean,
    size: Int = 24,
) {
    val color =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Canvas(
        modifier =
            Modifier
                .size(size.dp)
                .semantics { contentDescription = destination.label },
    ) {
        val stroke = Stroke(width = this.size.minDimension * 0.1f, cap = StrokeCap.Round)
        when (destination) {
            HomeDestination.Overview -> {
                drawArc(color, 150f, 240f, false, style = stroke)
                drawLine(
                    color,
                    center,
                    Offset(this.size.width * 0.78f, this.size.height * 0.36f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }

            HomeDestination.Portfolio -> {
                val width = this.size.width
                drawLine(
                    color,
                    Offset(width * 0.2f, this.size.height * 0.8f),
                    Offset(
                        width * 0.2f,
                        this.size.height * 0.54f,
                    ),
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(width * 0.5f, this.size.height * 0.8f),
                    Offset(
                        width * 0.5f,
                        this.size.height * 0.26f,
                    ),
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(width * 0.8f, this.size.height * 0.8f),
                    Offset(
                        width * 0.8f,
                        this.size.height * 0.42f,
                    ),
                    stroke.width,
                    StrokeCap.Round,
                )
            }

            HomeDestination.History -> {
                drawArc(color, 35f, 300f, false, style = stroke)
                drawLine(color, center, Offset(center.x, this.size.height * 0.27f), stroke.width, StrokeCap.Round)
                drawLine(color, center, Offset(this.size.width * 0.72f, center.y), stroke.width, StrokeCap.Round)
            }

            HomeDestination.Sources -> {
                val x = this.size.width * 0.28f
                listOf(0.27f, 0.5f, 0.73f).forEach { y ->
                    drawCircle(color, radius = stroke.width * 0.65f, center = Offset(x, this.size.height * y))
                    drawLine(
                        color,
                        Offset(x + stroke.width * 1.8f, this.size.height * y),
                        Offset(
                            this.size.width * 0.8f,
                            this.size.height * y,
                        ),
                        stroke.width,
                        StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private data class BannerCopy(
    val label: String,
    val detail: String,
    val container: Color,
    val foreground: Color,
)
