package org.rotki.mobile.android.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.rotki.mobile.android.ui.UiTags

class CompanionHomeNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startsAtOverviewAndNavigatesToEveryTopLevelRoute() {
        composeRule.setContent {
            CompanionHome(HomeConnectionBannerState.CONNECTED)
        }

        composeRule.onNodeWithTag(UiTags.HOME_SHELL).assertIsDisplayed()
        assertSelectedDestination(label = "Overview", title = "Portfolio overview")

        listOf(
            "Portfolio" to "Your assets",
            "History" to "Recent activity",
            "Sources" to "Portfolio sources",
            "Overview" to "Portfolio overview",
        ).forEach { (label, title) ->
            composeRule.onNodeWithText(label).performClick()
            assertSelectedDestination(label, title)
        }
    }

    @Test
    fun reselectIsSingleTopAndBackFromSecondaryReturnsToOverview() {
        composeRule.setContent {
            CompanionHome(HomeConnectionBannerState.CONNECTED)
        }
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("History").performClick()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        assertSelectedDestination(label = "Overview", title = "Portfolio overview")
    }

    @Test
    fun authorizedDestinationRestoresFromSavedInstanceState() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            CompanionHome(HomeConnectionBannerState.CONNECTED)
        }
        composeRule.onNodeWithText("History").performClick()
        assertSelectedDestination(label = "History", title = "Recent activity")

        restorationTester.emulateSavedInstanceStateRestore()

        assertSelectedDestination(label = "History", title = "Recent activity")
    }

    private fun assertSelectedDestination(
        label: String,
        title: String,
    ) {
        composeRule.onNodeWithText(label).assertIsSelected()
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }
}
