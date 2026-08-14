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
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.rotki.mobile.android.ui.navigation.HistoryRoute
import org.rotki.mobile.android.ui.navigation.OverviewRoute
import org.rotki.mobile.android.ui.navigation.PortfolioRoute
import org.rotki.mobile.android.ui.navigation.SourcesRoute

private const val HOME_SHELL_TEST_TAG: String = "home_shell"

/** Presentation-only connection state; it never grants access to the authenticated graph. */
public enum class HomeConnectionBannerState {
    CONNECTED,
    REFRESHING_COMPLETE,
    REFRESHING_PARTIAL,
    DEGRADED,
    UNREACHABLE,
}

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

/**
 * Renders the authenticated home graph after the application-level authority guard has passed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun CompanionHome(
    bannerState: HomeConnectionBannerState,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    Scaffold(
        modifier = modifier.testTag(HOME_SHELL_TEST_TAG),
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
                HomeDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination.hasRoute(item),
                        onClick = { navController.navigateTo(item) },
                        icon = {
                            DestinationIcon(
                                destination = item,
                                selected = currentDestination.hasRoute(item),
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
            ConnectionBanner(bannerState)
            HomeNavHost(navController)
        }
    }
}

@Composable
private fun HomeNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = OverviewRoute,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<OverviewRoute> {
            DestinationPlaceholder(HomeDestination.Overview)
        }
        composable<PortfolioRoute> {
            DestinationPlaceholder(HomeDestination.Portfolio)
        }
        composable<HistoryRoute> {
            DestinationPlaceholder(HomeDestination.History)
        }
        composable<SourcesRoute> {
            DestinationPlaceholder(HomeDestination.Sources)
        }
    }
}

private fun NavDestination?.hasRoute(destination: HomeDestination): Boolean =
    when (destination) {
        HomeDestination.Overview -> this?.hasRoute<OverviewRoute>() == true
        HomeDestination.Portfolio -> this?.hasRoute<PortfolioRoute>() == true
        HomeDestination.History -> this?.hasRoute<HistoryRoute>() == true
        HomeDestination.Sources -> this?.hasRoute<SourcesRoute>() == true
    }

private fun NavHostController.navigateTo(destination: HomeDestination) {
    when (destination) {
        HomeDestination.Overview -> navigateTopLevel(OverviewRoute)
        HomeDestination.Portfolio -> navigateTopLevel(PortfolioRoute)
        HomeDestination.History -> navigateTopLevel(HistoryRoute)
        HomeDestination.Sources -> navigateTopLevel(SourcesRoute)
    }
}

private fun <T : Any> NavHostController.navigateTopLevel(route: T) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun ConnectionBanner(state: HomeConnectionBannerState) {
    val banner =
        when (state) {
            HomeConnectionBannerState.CONNECTED -> {
                BannerCopy(
                    label = "Connected securely",
                    detail = "Your Engine remains authoritative.",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            HomeConnectionBannerState.REFRESHING_COMPLETE -> {
                BannerCopy(
                    label = "Refreshing",
                    detail = "Keeping your last complete portfolio visible.",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            HomeConnectionBannerState.REFRESHING_PARTIAL -> {
                BannerCopy(
                    label = "Refreshing",
                    detail = "Keeping available last-known values visible.",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            HomeConnectionBannerState.DEGRADED -> {
                BannerCopy(
                    label = "Some sources need attention",
                    detail = "Last-known values are preserved where possible.",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            HomeConnectionBannerState.UNREACHABLE -> {
                BannerCopy(
                    label = "Showing offline snapshot",
                    detail = "Rotki will reconnect while this app is open.",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant,
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
