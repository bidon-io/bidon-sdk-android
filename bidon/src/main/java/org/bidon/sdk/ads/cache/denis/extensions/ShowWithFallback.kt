package org.bidon.sdk.ads.cache.denis.extensions

import android.app.Activity
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.cache.denis.lifecycle.CancellationManager
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
 * After a successful show, subscribes to ad events (Shown, Clicked, Closed,
 * PaidRevenue, ShowFailed, etc.) and dispatches them via [onEvent].
 * Impression sending is the caller's responsibility via [onEvent].
 *
 * @param cancellationManager Cancellation manager for cancelling ongoing auctions
 * @param activity Activity context for showing the ad
 * @param eventScope Scope for launching event collection after successful show
 * @param onEvent Called for each ad event (Shown, Clicked, Closed, ShowFailed, PaidRevenue, etc.)
 * @return Result with Ad on success, BidonError on failure
 */
internal suspend fun showBestAdWithFallback(
    cancellationManager: CancellationManager,
    activity: Activity,
    eventScope: CoroutineScope,
    onEvent: (AdSource<*>, AdEvent) -> Unit
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
    val wasCancelled = cancellationManager.cancelIfMatching(auctionId)
    if (wasCancelled) {
        logInfo(TAG, "CANCEL: Stopped ongoing auction $auctionId for $demandId")
    }

    // Show the ad based on type
    when (adSource) {
        is AdSource.Interstitial<*> -> adSource.show(activity)
        is AdSource.Rewarded<*> -> adSource.show(activity)
        is AdSource.Banner<*> -> {
            return Result.failure(BidonError.Unspecified(demandId = adSource.demandId))
        }
    }

    // Start event collection BEFORE waiting for show result to avoid
    // missing events (e.g. PaidRevenue) emitted right after Shown.
    // Both collectors receive all events from SharedFlow (replay=0).
    val eventJob: Job = adSource.adEvent.onEach { event ->
        onEvent(adSource, event)
    }.launchIn(eventScope)

    // Wait for show result
    return adSource.adEvent.first { event ->
        event is AdEvent.Shown || event is AdEvent.ShowFailed
    }.let { event ->
        when (event) {
            is AdEvent.Shown -> {
                logInfo(TAG, "SUCCESS: $demandId displayed @ $${"%.2f".format(ecpm)}")
                Result.success(event.ad)
            }
            is AdEvent.ShowFailed -> {
                logInfo(TAG, "FAIL: $demandId - ${event.cause}, trying fallback")
                eventJob.cancel()

                // Recursive retry with next best ad from cache
                showBestAdWithFallback(
                    cancellationManager = cancellationManager,
                    activity = activity,
                    eventScope = eventScope,
                    onEvent = onEvent
                )
            }
            else -> {
                eventJob.cancel()
                Result.failure(BidonError.Unspecified(demandId = adSource.demandId))
            }
        }
    }
}