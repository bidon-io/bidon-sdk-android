package org.bidon.sdk.ads.cache.impl

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.denis.extensions.showBestAdWithFallback
import org.bidon.sdk.ads.cache.denis.lifecycle.LifecycleManager
import org.bidon.sdk.ads.cache.denis.orchestration.AuctionCompletionType
import org.bidon.sdk.ads.cache.denis.orchestration.CoordinationLayer
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers

/**
 * V2 implementation of AdCache that delegates to Phase 1-4 components.
 *
 * Acts as facade over:
 * - CoordinationLayer: Orchestrates warm/cold start auction flow
 * - LifecycleManager: Manages auction lifecycle and cancellation
 * - ReadyToShowCache: Stores ready-to-show ads
 *
 * Design pattern: Facade - simplifies complex subsystem interaction.
 * Entry point for SDK to use v2 cache implementation.
 */
internal class AdCacheDenisImpl(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver, // V1 compatibility - unused in V2
    private val coordinationLayer: CoordinationLayer,
    private val lifecycleManager: LifecycleManager,
    private val biddingConfig: BiddingConfig,
    private val scope: CoroutineScope = CoroutineScope(SdkDispatchers.Main + SupervisorJob()),
) : AdCache {

    /**
     * Start auction to cache ads.
     *
     * Delegates to CoordinationLayer which handles:
     * - Warm start: serve cached ad immediately
     * - Cold start: run full auction flow
     *
     * Launches on coroutine scope to avoid blocking caller.
     */
    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        scope.launch {
            val tokenTimeout = biddingConfig.tokenTimeout

            val completionType = coordinationLayer.coordinateAuction(
                adTypeParam = adTypeParam,
                demandAd = demandAd,
                tokenTimeout = tokenTimeout,
                onSuccess = { result, info -> onSuccess(result, info) },
                onFailure = { info, error -> onFailure(info, error) },
            )

            when (completionType) {
                is AuctionCompletionType.WarmStartServed -> {
                    logInfo(TAG, "cache: warm start served, auction complete")
                }
                is AuctionCompletionType.ColdStartInProgress -> {
                    logInfo(TAG, "cache: cold start in progress")
                }
                is AuctionCompletionType.ColdStartCompleted -> {
                    logInfo(TAG, "cache: cold start completed")
                }
            }
        }
    }

    /**
     * Peek at best ad without removing it.
     *
     * Non-destructive read from ReadyToShowCache.
     *
     * @return AuctionResult with highest eCPM or null if cache empty
     */
    override fun peek(): AuctionResult? {
        return ReadyToShowCache.peekBest()
    }

    /**
     * Remove and return best ad (highest eCPM).
     *
     * Cancels ongoing auction for the returned ad to prevent wasted processing.
     *
     * @return AuctionResult with highest eCPM or null if cache empty
     */
    override fun pop(): AuctionResult? {
        val entry = ReadyToShowCache.popBest()
        return if (entry != null) {
            lifecycleManager.cancelAuction(entry.auctionId)
            logInfo(TAG, "pop: served cached ad demandId=${entry.demandId}, ecpm=${entry.ecpm}")
            entry.value
        } else {
            null
        }
    }

    /**
     * Remove and return best ad, suspending until one is available.
     *
     * Preserves V1 behavior: suspends until cache has an ad,
     * then returns it with removal (pop semantics).
     * Cooperates with coroutine cancellation via delay checkpoints.
     *
     * @return AuctionResult with highest eCPM (never null, suspends until available)
     */
    override suspend fun poll(): AuctionResult {
        // V1 semantics: suspend until cache has an ad
        while (true) {
            val result = pop()
            if (result != null) return result
            // Wait briefly before checking again
            kotlinx.coroutines.delay(100)
        }
    }

    /**
     * Show best ad from cache with automatic fallback on failure.
     *
     * Delegates to showBestAdWithFallback extension which:
     * - Tries to show best ad from ReadyToShowCache
     * - On failure, automatically tries next best ad
     * - Continues until success or cache exhaustion
     *
     * Denis ad caching specific feature.
     *
     * @param activity Activity context for showing the ad
     * @param onShown Callback when ad shown successfully
     * @param onFailed Callback when all ads failed or cache empty
     */
    fun showBestWithFallback(
        activity: Activity,
        onShown: (Ad) -> Unit,
        onFailed: (Throwable) -> Unit
    ) {
        scope.launch {
            showBestAdWithFallback(activity)
                .onSuccess { ad ->
                    logInfo(TAG, "showBestWithFallback: ad shown successfully")
                    onShown(ad)
                }
                .onFailure { error ->
                    logInfo(TAG, "showBestWithFallback: all ads failed, error=$error")
                    onFailed(error)
                }
        }
    }

    /**
     * Clear cache - NO-OP per design decision.
     *
     * V2 design: Caches clear via TTL expiration only (no manual clear).
     * Periodic sweep job removes expired entries automatically.
     */
    override fun clear() {
        logInfo(TAG, "clear() called - NO-OP per design (caches clear via expiration only)")
    }

    /**
     * Configure cache settings - NO-OP per design decision.
     *
     * V2 design: Uses application-wide singleton cache with fixed capacity.
     * Calling setCapacity() on one instance would affect all instances.
     * Per user decision: withSettings should not be active in V2.
     *
     * @param settings Cache configuration (ignored)
     */
    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: V2 uses application-wide singleton cache with fixed capacity.
        // Calling setCapacity() on one instance would affect all instances.
        // Per user decision: withSettings should not be active in V2.
        logInfo(TAG, "withSettings() called - NO-OP in V2 (singleton cache, capacity managed globally)")
    }

    companion object {
        private const val TAG = "AdCacheDenisImpl"
    }
}
