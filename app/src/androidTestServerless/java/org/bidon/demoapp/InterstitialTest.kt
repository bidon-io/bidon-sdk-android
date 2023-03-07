package org.bidon.demoapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.test.runTest
import org.bidon.demoapp.theme.AppTheme
import org.bidon.demoapp.ui.InterstitialScreen
import org.bidon.sdk.auction.impl.ServerlessAuctionConfig
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.junit.Rule
import org.junit.Test

/**
 * Created by Aleksei Cherniaev on 03/03/2023.
 */
class InterstitialTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun interstitial_load() = runTest {
        ServerlessAuctionConfig.setLocalAuctionResponse(
            pricefloor = 0.001,
            rounds = listOf(
                Round(
                    id = "ROUND_1",
                    demandIds = listOf("admob"),
                    timeoutMs = 10000
                ),
                Round(
                    id = "ROUND_2",
                    demandIds = listOf("dtexchange", "unityads"),
                    timeoutMs = 10000
                ),
            ),
            lineItems = listOf(
                LineItem(
                    demandId = "admob",
                    pricefloor = 0.01,
                    adUnitId = "ca-app-pub-3940256099942544/1033173712"
                ),
                LineItem(
                    demandId = "dtexchange",
                    pricefloor = 0.02,
                    adUnitId = "150946"
                ),
                LineItem(
                    demandId = "unityads",
                    pricefloor = 0.03,
                    adUnitId = "Interstitial_Android"
                ),
            )
        )
        rule.setContent {
            MaterialTheme {
                InterstitialScreen(
                    navController = rememberNavController(),
                )
            }
        }

        with(rule) {
            StepSdkInitialization.perform(activity)
            clickOnButton("LOAD")
            assertViewWithText("ROUND_1")
            assertViewWithText("ROUND_2")
            assertViewWithText("WINNER")

            clickOnButton("SHOW")
            assertViewWithText("onRevenuePaid")
            assertViewWithText("onAdShown")
        }
    }

    @Test
    fun interstitial_OneRoundAdmob() {
        ServerlessAuctionConfig.setLocalAuctionResponse(
            pricefloor = 0.0,
            rounds = listOf(
                Round(
                    id = "ROUND_1",
                    demandIds = listOf("admob"),
                    timeoutMs = 10000
                )
            ),
            lineItems = listOf(
                LineItem(
                    demandId = "admob",
                    pricefloor = 0.01,
                    adUnitId = "ca-app-pub-3940256099942544/1033173712"
                )
            )
        )
        rule.setContent {
            AppTheme {
                InterstitialScreen(navController = rememberNavController())
            }
        }

        with(rule) {
            StepSdkInitialization.perform(activity)
            clickOnButton("LOAD")
            assertViewWithText("ROUND_1")
            assertViewWithText("WINNER")
            clickOnButton("SHOW")
            // closeAdmobInterstitial()
            assertViewWithText("onRevenuePaid")
            assertViewWithText("onAdShown")
            Thread.sleep(6000)
        }
    }
}

internal fun ComposeContentTestRule.assertViewWithText(text: String, timeout: Long = 15000) {
    var countdown = timeout
    val step = timeout / 10
    while (countdown > 0 && this@assertViewWithText.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) {
        Thread.sleep(step)
        countdown -= step
        println("11122222 $countdown, $step")
    }
    require(this@assertViewWithText.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size == 1) {
        "No view with text($text) found"
    }
}

internal fun ComposeContentTestRule.clickOnButton(text: String) = onNodeWithText(text, ignoreCase = true).performClick()

typealias BidonRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

internal fun BidonRule.clickBackButton() {
    activityRule.scenario.onActivity {
        it.onBackPressedDispatcher.onBackPressed()
    }
}

internal fun BidonRule.closeAdmobInterstitial() {
    onNodeWithContentDescription(label = "Interstitial close button").performClick()
}
