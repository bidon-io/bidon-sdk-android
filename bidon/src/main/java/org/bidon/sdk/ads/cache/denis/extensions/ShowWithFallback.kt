package org.bidon.sdk.ads.cache.denis.extensions

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
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
 * Thread-safety: Coroutine-based, safe for concurrent access.
 * Integration: Works with denis ad caching (ReadyToShowCache).
 *
 * Usage:
 * ```kotlin
 * scope.launch {
 *     val result = showBestAdWithFallback(
 *         activity = activity,
 *         onShown = { ad -> },
 *         onClicked = { ad -> },
 *         onClosed = { ad -> },
 *         onRevenuePaid = { ad, adValue -> },
 *         onShowFailed = { error -> },
 *         onWinnerSelected = { adSource -> }
 *     )
 *     result.onSuccess { ad ->
 *         // Ad shown successfully
 *     }.onFailure { error ->
 *         // All ads failed or cache empty
 *     }
 * }
 * ```
 *
 * @param activity Activity context for showing the ad
 * @param onShown Called when ad is shown successfully
 * @param onClicked Called when ad is clicked
 * @param onClosed Called when ad is closed
 * @param onRevenuePaid Called when revenue is paid
 * @param onShowFailed Called when show fails (for each failed attempt)
 * @param onWinnerSelected Called with the AdSource that was successfully shown
 * @return Result with Ad on success, BidonError on failure
 */
internal suspend fun showBestAdWithFallback(
    activity: Activity,
    onShown: (Ad) -> Unit = {},
    onClicked: (Ad) -> Unit = {},
    onClosed: (Ad) -> Unit = {},
    onRevenuePaid: (Ad, AdValue) -> Unit = { _, _ -> },
    onShowFailed: (BidonError) -> Unit = {},
    onWinnerSelected: (AdSource.Interstitial<*>) -> Unit = {}
): Result<Ad> {
    return tryShowNextAd(
        activity = activity,
        onShown = onShown,
        onClicked = onClicked,
        onClosed = onClosed,
        onRevenuePaid = onRevenuePaid,
        onShowFailed = onShowFailed,
        onWinnerSelected = onWinnerSelected
    )
}

/**
 * Internal recursive function to try showing ads from cache.
 */
private suspend fun tryShowNextAd(
    activity: Activity,
    onShown: (Ad) -> Unit,
    onClicked: (Ad) -> Unit,
    onClosed: (Ad) -> Unit,
    onRevenuePaid: (Ad, AdValue) -> Unit,
    onShowFailed: (BidonError) -> Unit,
    onWinnerSelected: (AdSource.Interstitial<*>) -> Unit
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

                // Notify winner to update in InterstitialImpl
                if (adSource is AdSource.Interstitial<*>) {
                    onWinnerSelected(adSource)
                }

                // Call onShown
                onShown(event.ad)

                // Listen to post-show events in background
                listenToPostShowEvents(
                    adSource = adSource,
                    onClicked = onClicked,
                    onClosed = onClosed,
                    onRevenuePaid = onRevenuePaid
                )

                Result.success(event.ad)
            }
            is AdEvent.ShowFailed -> {
                logInfo(TAG, "FAIL: $demandId - ${event.cause}, trying fallback")
                onShowFailed(event.cause)

                // Recursive retry with next best ad from cache
                tryShowNextAd(
                    activity = activity,
                    onShown = onShown,
                    onClicked = onClicked,
                    onClosed = onClosed,
                    onRevenuePaid = onRevenuePaid,
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
 * Listen to post-show events (Clicked, Closed, PaidRevenue) from the ad source.
 * Automatically cancels subscription when ad is closed.
 */
private fun listenToPostShowEvents(
    adSource: AdSource<*>,
    onClicked: (Ad) -> Unit,
    onClosed: (Ad) -> Unit,
    onRevenuePaid: (Ad, AdValue) -> Unit
) {
    val scope = CoroutineScope(SdkDispatchers.Main)
    scope.launch {
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
                    // Stop listening after close
                    return@collect
                }
                is AdEvent.PaidRevenue -> {
                    logInfo(TAG, "REVENUE: ${adSource.demandId} @ ${event.adValue}")
                    onRevenuePaid(event.ad, event.adValue)
                }
                else -> {
                    // Ignore other events (Shown, ShowFailed, etc.)
                }
            }
        }
    }
}
