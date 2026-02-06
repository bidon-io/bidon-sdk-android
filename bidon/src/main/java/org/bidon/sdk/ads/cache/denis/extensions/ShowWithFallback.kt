package org.bidon.sdk.ads.cache.denis.extensions

import android.app.Activity
import kotlinx.coroutines.flow.first
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo

private const val TAG = "ShowWithFallback"

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
 *     val result = showBestAdWithFallback(activity)
 *     result.onSuccess { ad ->
 *         // Ad shown successfully
 *     }.onFailure { error ->
 *         // All ads failed or cache empty
 *     }
 * }
 * ```
 *
 * @param activity Activity context for showing the ad
 * @return Result with Ad on success, BidonError on failure
 */
internal suspend fun showBestAdWithFallback(activity: Activity): Result<Ad> {
    return tryShowNextAd(activity)
}

/**
 * Internal recursive function to try showing ads from cache.
 */
private suspend fun tryShowNextAd(activity: Activity): Result<Ad> {
    // Get best ad from cache
    val entry = ReadyToShowCache.popBest()

    if (entry == null) {
        logInfo(TAG, "🚫 FALLBACK EXHAUSTED: no more ads in cache")
        return Result.failure(BidonError.AdNotReady)
    }

    val adSource = entry.value.adSource
    val demandId = entry.demandId
    val ecpm = entry.ecpm
    val cacheRemaining = ReadyToShowCache.size()

    logInfo(TAG, "📺 SHOW ATTEMPT: $demandId @ $${"%.2f".format(ecpm)} (cache remaining: $cacheRemaining ads)")

    // Show the ad based on type
    when (adSource) {
        is AdSource.Interstitial<*> -> adSource.show(activity)
        is AdSource.Rewarded<*> -> adSource.show(activity)
        is AdSource.Banner<*> -> {
            logInfo(TAG, "Banner ads not supported in showWithFallback")
            return Result.failure(BidonError.Unspecified(demandId = adSource.demandId))
        }
    }

    // Wait for show result
    return adSource.adEvent.first { event ->
        event is AdEvent.Shown || event is AdEvent.ShowFailed
    }.let { event ->
        when (event) {
            is AdEvent.Shown -> {
                logInfo(TAG, "✅ SHOW SUCCESS: $demandId displayed")
                Result.success(event.ad)
            }
            is AdEvent.ShowFailed -> {
                logInfo(TAG, "❌ SHOW FAILED: $demandId (${event.cause}), trying fallback...")
                // Recursive retry with next best ad from cache
                tryShowNextAd(activity)
            }
            else -> {
                logInfo(TAG, "⚠️ UNEXPECTED EVENT: $event")
                Result.failure(BidonError.Unspecified(demandId = adSource.demandId))
            }
        }
    }
}
