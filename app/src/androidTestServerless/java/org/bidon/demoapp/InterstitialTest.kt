package org.bidon.demoapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.test.runTest
import org.bidon.demoapp.theme.AppTheme
import org.bidon.demoapp.ui.InterstitialScreen
import org.bidon.sdk.auction.impl.ServerlessAuctionConfig
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.logs.logging.impl.logInfo
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
            onRoot().printToLog(Tag)
            clickOnButton("LOAD")
            assertViewWithText("ROUND_1")
            assertViewWithText("onAdLoaded WINNER:")
            logInfo(Tag, "1112222------")
            clickOnButton("SHOW")
            // closeAdmobInterstitial()
            assertViewWithText("onRevenuePaid")
            assertViewWithText("onAdShown")
            Thread.sleep(6000)
        }
    }
}

internal fun BidonRule.assertViewWithText(text: String, timeout: Long = 10000) {
    logInfo(Tag, "11122222 IN $text ${this@assertViewWithText.activityRule.scenario.state}")
//    var countdown = timeout
//    val step = timeout / 10
//    while (countdown > 0 && this@assertViewWithText.onAllNodesWithText(text, ignoreCase = true, substring = true)
//            .fetchSemanticsNodes().isEmpty()
//    ) {
//        Thread.sleep(step)
//        countdown -= step
//        println("11122222 $text $countdown, $step, ${this@assertViewWithText.activityRule.scenario.state}")
//    }
    waitUntil(timeout) {
        onAllNodesWithText(text, ignoreCase = true, substring = true).fetchSemanticsNodes().size == 1
    }
//    require(this@assertViewWithText.onAllNodesWithText(text, ignoreCase = true, substring = true).fetchSemanticsNodes().size == 1) {
//        "No view with text($text) found"
//    }
    logInfo(Tag, "11122222 SUCCESS")
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

private const val Tag = "TESTME"