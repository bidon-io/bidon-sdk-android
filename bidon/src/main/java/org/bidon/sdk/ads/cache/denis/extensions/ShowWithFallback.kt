package org.bidon.sdk.ads.cache.denis.extensions

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.cache.denis.lifecycle.LifecycleManager
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.rewarded.Reward
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers

private const val TAG = "[DenisCache] ShowWithFallback"

/**
 * Show best ad from ReadyToShowCache with automatic fallback on failure.
 *
 * If show fails, automatically tries the next best ad from cache recursively
 * until success or cache exhaustion.
 *
 * @param lifecycleManager Lifecycle manager for cancelling ongoing auctions
 * @param activity Activity context for showing the ad
 * @param onShown Called when ad is shown successfully
 * @param onClicked Called when ad is clicked
 * @param onClosed Called when ad is closed
 * @param onRevenuePaid Called when revenue is paid
 * @param onReward Called when user earns a reward (Rewarded ads only)
 * @param onExpired Called when a cached ad has expired
 * @param onShowFailed Called when show fails (for each failed attempt)
 * @param onWinnerSelected Called with the AdSource that was successfully shown
 * @return Result with Ad on success, BidonError on failure
 */
internal suspend fun showBestAdWithFallback(
    lifecycleManager: LifecycleManager,
    activity: Activity,
    onShown: (Ad) -> Unit = {},
    onClicked: (Ad) -> Unit = {},
    onClosed: (Ad) -> Unit = {},
    onRevenuePaid: (Ad, AdValue) -> Unit = { _, _ -> },
    onReward: (Ad, Reward?) -> Unit = { _, _ -> },
    onExpired: (Ad) -> Unit = {},
    onShowFailed: (BidonError) -> Unit = {},
    onWinnerSelected: (AdSource<*>) -> Unit = {}
): Result<Ad> {
    return tryShowNextAd(
        lifecycleManager = lifecycleManager,
        activity = activity,
        onShown = onShown,
        onClicked = onClicked,
        onClosed = onClosed,
        onRevenuePaid = onRevenuePaid,
        onReward = onReward,
        onExpired = onExpired,
        onShowFailed = onShowFailed,
        onWinnerSelected = onWinnerSelected
    )
}

/**
 * Internal recursive function to try showing ads from cache.
 */
private suspend fun tryShowNextAd(
    lifecycleManager: LifecycleManager,
    activity: Activity,
    onShown: (Ad) -> Unit,
    onClicked: (Ad) -> Unit,
    onClosed: (Ad) -> Unit,
    onRevenuePaid: (Ad, AdValue) -> Unit,
    onReward: (Ad, Reward?) -> Unit,
    onExpired: (Ad) -> Unit,
    onShowFailed: (BidonError) -> Unit,
    onWinnerSelected: (AdSource<*>) -> Unit
): Result<Ad> {
    // Get best ad from cache
    val entry = ReadyToShowCache.popBest()

    if (entry == null) {
        logInfo(TAG, "EXHAUSTED: No more ads in cache")
        return Result.failure(BidonError.AdNotReady)
    }

    val adSource = entry.value.adSource
    val demandId = entry.demandId
    val ecpm = entry.ecpm
    val auctionId = entry.auctionId

    // Cancel ongoing auction for this ad to prevent wasted processing
    val wasCancelled = lifecycleManager.cancelAuction(auctionId)
    if (wasCancelled) {
        logInfo(TAG, "CANCEL: Stopped ongoing auction $auctionId for $demandId")
    }

    logInfo(TAG, "SHOW: Attempting $demandId @ $${"%.2f".format(ecpm)}")

    // Show the ad based on type
    when (adSource) {
        is AdSource.Interstitial<*> -> adSource.show(activity)
        is AdSource.Rewarded<*> -> adSource.show(activity)
        is AdSource.Banner<*> -> {
            return Result.failure(BidonError.Unspecified(demandId = adSource.demandId))
        }
    }

    // Wait for show result
    return adSource.adEvent.first { event ->
        event is AdEvent.Shown || event is AdEvent.ShowFailed
    }.let { event ->
        when (event) {
            is AdEvent.Shown -> {
                logInfo(TAG, "SUCCESS: $demandId displayed @ $${"%.2f".format(ecpm)}")

                // Notify winner
                onWinnerSelected(adSource)

                // Send show impression (matches classic subscribeToWinner behavior)
                adSource.sendShowImpression()

                // Call onShown
                onShown(event.ad)

                // Listen to post-show events in background
                listenToPostShowEvents(
                    adSource = adSource,
                    onClicked = onClicked,
                    onClosed = onClosed,
                    onRevenuePaid = onRevenuePaid,
                    onReward = onReward,
                    onExpired = onExpired
                )

                Result.success(event.ad)
            }
            is AdEvent.ShowFailed -> {
                logInfo(TAG, "FAIL: $demandId - ${event.cause}, trying fallback")
                onShowFailed(event.cause)

                // Recursive retry with next best ad from cache
                tryShowNextAd(
                    lifecycleManager = lifecycleManager,
                    activity = activity,
                    onShown = onShown,
                    onClicked = onClicked,
                    onClosed = onClosed,
                    onRevenuePaid = onRevenuePaid,
                    onReward = onReward,
                    onExpired = onExpired,
                    onShowFailed = onShowFailed,
                    onWinnerSelected = onWinnerSelected
                )
            }
            else -> {
                Result.failure(BidonError.Unspecified(demandId = adSource.demandId))
            }
        }
    }
}

/**
 * Listen to post-show events (Clicked, Closed, PaidRevenue, OnReward, Expired) from the ad source.
 * Cancels the collection job when ad is closed.
 */
private fun listenToPostShowEvents(
    adSource: AdSource<*>,
    onClicked: (Ad) -> Unit,
    onClosed: (Ad) -> Unit,
    onRevenuePaid: (Ad, AdValue) -> Unit,
    onReward: (Ad, Reward?) -> Unit,
    onExpired: (Ad) -> Unit
) {
    val scope = CoroutineScope(SdkDispatchers.Main)
    var job: Job? = null
    job = scope.launch {
        adSource.adEvent.collect { event ->
            when (event) {
                is AdEvent.Clicked -> {
                    logInfo(TAG, "CLICK: ${adSource.demandId}")
                    onClicked(event.ad)
                    adSource.sendClickImpression()
                }
                is AdEvent.Closed -> {
                    logInfo(TAG, "CLOSE: ${adSource.demandId}")
                    onClosed(event.ad)
                    // Cancel job to stop listening after close
                    job?.cancel()
                }
                is AdEvent.PaidRevenue -> {
                    logInfo(TAG, "REVENUE: ${adSource.demandId} @ ${event.adValue}")
                    onRevenuePaid(event.ad, event.adValue)
                }
                is AdEvent.OnReward -> {
                    logInfo(TAG, "REWARD: ${adSource.demandId}")
                    onReward(event.ad, event.reward)
                    adSource.sendRewardImpression()
                }
                is AdEvent.Expired -> {
                    logInfo(TAG, "EXPIRED: ${adSource.demandId}")
                    onExpired(event.ad)
                }
                else -> {
                    // Ignore other events (Shown, ShowFailed, Fill, LoadFailed)
                }
            }
        }
    }
}
