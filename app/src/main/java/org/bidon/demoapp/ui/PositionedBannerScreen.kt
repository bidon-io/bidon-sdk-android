package org.bidon.demoapp.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.bidon.demoapp.component.AppButton
import org.bidon.demoapp.component.AppToolbar
import org.bidon.demoapp.component.Body2Text
import org.bidon.demoapp.component.ItemSelector
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerListener
import org.bidon.sdk.ads.banner.BannerManager
import org.bidon.sdk.ads.banner.BannerPosition
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logInfo

@Composable
fun PositionedBannerScreen(navController: NavHostController) {
    val activity = LocalContext.current as Activity
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val logFlow = remember {
        mutableStateOf(listOf("Log"))
    }
    val bannerFormat = remember {
        mutableStateOf(BannerFormat.Banner)
    }
    val bannerPosition = remember {
        mutableStateOf(BannerPosition.BottomCenter)
    }
    var banner: BannerManager? = null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(52.dp)
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppToolbar(
            title = "Positioned Banners",
            onNavigationButtonClicked = { navController.popBackStack() }
        )
        Column(modifier = Modifier.padding(8.dp)) {
            ItemSelector(
                title = "Format",
                items = BannerFormat.values().toList(),
                selectedItem = bannerFormat.value,
                horizontalAlignment = Alignment.Start,
                getItemTitle = {
                    when (it) {
                        BannerFormat.Banner -> "Banner 320x50"
                        BannerFormat.LeaderBoard -> "Leader Board 728x90"
                        BannerFormat.MRec -> "MRec 300x250"
                        BannerFormat.Adaptive -> "Smart/Adaptive 320x50"
                    }
                },
                onItemClicked = {
                    bannerFormat.value = it
                    banner?.setBannerFormat(it)
                }
            )
            Spacer(modifier = Modifier.padding(top = 10.dp))
            ItemSelector(
                title = "Position",
                items = BannerPosition.values().toList(),
                selectedItem = bannerPosition.value,
                horizontalAlignment = Alignment.Start,
                getItemTitle = {
                    it.name
                },
                onItemClicked = {
                    bannerPosition.value = it
                    banner?.setPosition(it)
                }
            )
            Spacer(modifier = Modifier.padding(top = 10.dp))
            AppButton(
                text = "Create",
            ) {
                banner = BannerManager(activity, bannerFormat.value).apply {
                    setBannerListener(
                        object : BannerListener {
                            override fun onAdLoaded(ad: Ad) {
                                logFlow.log("onAdLoaded WINNER:\n$ad")
                            }

                            override fun onAdLoadFailed(cause: BidonError) {
                                logFlow.log("onAdLoadFailed: $cause")
                            }

                            override fun onAdShown(ad: Ad) {
                                logFlow.log("onAdShown: $ad")
                            }

                            override fun onAdClicked(ad: Ad) {
                                logFlow.log("onAdClicked: $ad")
                            }

                            override fun onAdExpired(ad: Ad) {
                                logFlow.log("onAdExpired: $ad")
                            }

                            override fun onRevenuePaid(ad: Ad, adValue: AdValue) {
                                logFlow.log("onRevenuePaid: ad=$ad, adValue=$adValue")
                            }

                            override fun onAdShowFailed(cause: BidonError) {
                                logFlow.log("onAdShowFailed: $cause")
                            }
                        }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    text = "Load",
                ) {
                    banner?.loadAd(activity, pricefloor = 0.02)
                }
                AppButton(
                    modifier = Modifier.padding(start = 12.dp),
                    text = "Show",
                ) {
                    banner?.showAd()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(
                    text = "Hide",
                ) {
                    banner?.hideAd()
                }
                AppButton(
                    modifier = Modifier.padding(start = 12.dp),
                    text = "Destroy",
                ) {
                    banner?.destroyAd()
                    banner = null
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp),
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
                listState.animateScrollToItem(index = logFlow.value.lastIndex)
            }
        }
    }
}

private fun MutableState<List<String>>.log(string: String) {
    synchronized(this) {
        this.value = this.value + string
    }
    logInfo(TAG, string)
}

private const val TAG = "PositionedBannerScreen"
