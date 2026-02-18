package org.bidon.sdk.ads.cache.denis.extensions

import android.app.Activity
import kotlinx.coroutines.flow.first
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.cache.denis.lifecycle.LifecycleManager
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

private const val TAG = "[DenisCache] ShowWithFallback"

/**
 * Show best ad from ReadyToShowCache with automatic fallback on failure.
 *
 * If show fails, automatically tries the next best ad from cache recursively
 * until success or cache exhaustion.
 *
 * Event callbacks (Shown, Clicked, Closed, PaidRevenue, OnReward, Expired) and
 * impression sending are handled by subscribeToWinner() in InterstitialImpl/RewardedImpl.
 * This function only handles the show/fallback flow and notifies via onWinnerSelected.
 *
 * @param lifecycleManager Lifecycle manager for cancelling ongoing auctions
 * @param activity Activity context for showing the ad
 * @param onShowFailed Called when show fails (for each failed attempt)
 * @param onWinnerSelected Called with the AdSource that was successfully shown
 * @return Result with Ad on success, BidonError on failure
 */
internal suspend fun showBestAdWithFallback(
    lifecycleManager: LifecycleManager,
    activity: Activity,
    onShowFailed: (BidonError) -> Unit = {},
    onWinnerSelected: (AdSource<*>) -> Unit = {}
): Result<Ad> {
    return tryShowNextAd(
        lifecycleManager = lifecycleManager,
        activity = activity,
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
    onShowFailed: (BidonError) -> Unit,
    onWinnerSelected: (AdSource<*>) -> Unit
): Result<Ad> {
    // Get best ad from cache
    val entry = ReadyToShowCache.popFirst()

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
                onWinnerSelected(adSource)
                Result.success(event.ad)
            }
            is AdEvent.ShowFailed -> {
                logInfo(TAG, "FAIL: $demandId - ${event.cause}, trying fallback")
                onShowFailed(event.cause)

                // Recursive retry with next best ad from cache
                tryShowNextAd(
                    lifecycleManager = lifecycleManager,
                    activity = activity,
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
