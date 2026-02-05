package org.bidon.demoapp

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.bidon.demoapp.component.AppButton
import org.bidon.demoapp.component.Body2Text
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.interstitial.InterstitialAd
import org.bidon.sdk.ads.interstitial.InterstitialListener
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logInfo

/**
 * Test-specific interstitial screen with cache version configuration.
 *
 * Unlike production InterstitialScreen, this composable:
 * - Accepts cacheVersion parameter to set cache_size extra
 * - Has minimal UI (just LOAD/SHOW/DESTROY + log)
 * - Used in E2E tests to validate V2 ad caching
 */
@Composable
fun TestInterstitialScreen(
    cacheVersion: Int? = null,
    pricefloor: Double = BidonSdk.DefaultPricefloor,
    auctionKey: String = "1O16GQT380000",
) {
    val activity = LocalContext.current as Activity
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val logFlow = remember { mutableStateOf(listOf("Log")) }

    val interstitial = remember {
        InterstitialAd(auctionKey = auctionKey).apply {
            // Set cache version via extra BEFORE any loadAd() call
            cacheVersion?.let { version ->
                addExtra("cache_size", version)
                logFlow.log("cache_size=$version (V$version)")
            }
            setInterstitialListener(
                object : InterstitialListener {
                    override fun onAdLoaded(ad: Ad, auctionInfo: AuctionInfo) {
                        logFlow.log("onAdLoaded")
                    }

                    override fun onAdLoadFailed(auctionInfo: AuctionInfo?, cause: BidonError) {
                        logFlow.log("onAdLoadFailed: $cause")
                    }

                    override fun onAdShowFailed(cause: BidonError) {
                        logFlow.log("onAdShowFailed: $cause")
                    }

                    override fun onAdShown(ad: Ad) {
                        logFlow.log("onAdShown")
                    }

                    override fun onAdClicked(ad: Ad) {
                        logFlow.log("onAdClicked")
                    }

                    override fun onAdClosed(ad: Ad) {
                        logFlow.log("onAdClosed")
                    }

                    override fun onAdExpired(ad: Ad) {
                        logFlow.log("onAdExpired")
                    }

                    override fun onRevenuePaid(ad: Ad, adValue: AdValue) {
                        logFlow.log("onRevenuePaid")
                    }
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(text = "Load") {
                interstitial.loadAd(activity, pricefloor = pricefloor)
            }
            AppButton(
                modifier = Modifier.padding(start = 8.dp),
                text = "Show"
            ) {
                interstitial.showAd(activity)
            }
            AppButton(
                modifier = Modifier.padding(start = 8.dp),
                text = "Destroy"
            ) {
                interstitial.destroyAd()
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
            state = listState
        ) {
            items(logFlow.value) { logLine ->
                Column(
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .background(MaterialTheme.colorScheme.secondary, MaterialTheme.shapes.medium)
                        .padding(4.dp)
                ) {
                    Body2Text(text = logLine)
                }
            }
            coroutineScope.launch {
                if (logFlow.value.isNotEmpty()) {
                    listState.animateScrollToItem(index = logFlow.value.lastIndex)
                }
            }
        }
    }
}

private fun MutableState<List<String>>.log(string: String) {
    synchronized(this) {
        this.value = this.value + string
    }
    logInfo("TestInterstitialScreen", string)
}
