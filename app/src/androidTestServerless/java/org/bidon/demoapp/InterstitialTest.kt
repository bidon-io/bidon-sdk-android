package org.bidon.demoapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.test.runTest
import org.bidon.demoapp.theme.AppTheme
import org.bidon.demoapp.ui.InterstitialScreen
import org.bidon.sdk.auction.impl.ServerlessAuctionConfig
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.config.impl.ServerlessConfigSettings
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
            clickOnComposeButton("LOAD")
//            assertViewWithText("ROUND_1")
//            assertViewWithText("ROUND_2")
//            assertViewWithText("WINNER")

            clickOnComposeButton("SHOW")

//            assertViewWithText("onRevenuePaid")
//            assertViewWithText("onAdShown")
        }
    }

    @Test
    fun interstitial_OneRoundAdmob() {
        ServerlessConfigSettings.useAdapters("admob")
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
            clickOnComposeButton("LOAD")
            waitForCallbackText("ROUND_1")
            waitForCallbackText("WINNER")

            Thread.sleep(1000)
            clickOnComposeButton("SHOW")

            Thread.sleep(1000)
            clickOnXmlButton(buttonDescription = AdmobInterstitialCloseButtonDescription)

            waitForCallbackText("onRevenuePaid")
            waitForCallbackText("onAdShown")
            waitForCallbackText("onAdClosed")
        }
    }

    private fun setAuctionSettings() {
    }
}

private const val AdmobInterstitialCloseButtonDescription = "Interstitial close button"