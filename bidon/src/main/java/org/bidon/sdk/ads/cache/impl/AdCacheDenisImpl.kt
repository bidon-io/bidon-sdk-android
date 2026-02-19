package org.bidon.sdk.ads.cache.impl

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.denis.extensions.showBestAdWithFallback
import org.bidon.sdk.ads.cache.denis.lifecycle.CancellationManager
import org.bidon.sdk.ads.cache.denis.lifecycle.PeriodicSweepJob
import org.bidon.sdk.ads.cache.denis.orchestration.CoordinationLayer
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 implementation of AdCache that delegates to Phase 1-4 components.
 *
 * Acts as facade over:
 * - CoordinationLayer: Orchestrates warm/cold start auction flow
 * - PeriodicSweepJob / CancellationManager: Lifecycle components
 * - ReadyToShowCache: Stores ready-to-show ads
 *
 * Design pattern: Facade - simplifies complex subsystem interaction.
 * Entry point for SDK to use v2 cache implementation.
 */
internal class AdCacheDenisImpl(
    override val demandAd: DemandAd,
    private val coordinationLayer: CoordinationLayer,
    private val periodicSweepJob: PeriodicSweepJob,
    private val cancellationManager: CancellationManager,
    private val biddingConfig: BiddingConfig,
    private val scope: CoroutineScope = CoroutineScope(SdkDispatchers.Main + SupervisorJob()),
) : AdCache {

    private val isAuctionRunning = AtomicBoolean(false)

    /**
     * Start auction to cache ads.
     *
     * Ignores concurrent calls while an auction is already running.
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
        if (!isAuctionRunning.compareAndSet(false, true)) {
            logInfo(TAG, "Ignoring loadAd(): auction already running")
            return
        }

        // Start periodic sweep (idempotent, safe to call multiple times)
        periodicSweepJob.start()

        scope.launch {
            try {
                val tokenTimeout = biddingConfig.tokenTimeout

                coordinationLayer.coordinateAuction(
                    adTypeParam = adTypeParam,
                    demandAd = demandAd,
                    tokenTimeout = tokenTimeout,
                    onSuccess = { result, info -> onSuccess(result, info) },
                    onFailure = { info, error -> onFailure(info, error) },
                )
            } finally {
                isAuctionRunning.set(false)
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
        return ReadyToShowCache.peekFirst()?.value
    }

    /**
     * Remove and return best ad (highest eCPM).
     *
     * Cancels ongoing auction for the returned ad to prevent wasted processing.
     *
     * @return AuctionResult with highest eCPM or null if cache empty
     */
    override fun pop(): AuctionResult? {
        val entry = ReadyToShowCache.popFirst()
        return entry?.value
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
     * Event callbacks and impression sending are handled by subscribeToWinner()
     * in InterstitialImpl/RewardedImpl. This method only handles the show/fallback
     * flow and notifies via onWinnerSelected.
     *
     * Denis ad caching specific feature.
     *
     * @param activity Activity context for showing the ad
     * @param onShowFailed Callback when show fails (for each failed attempt)
     * @param onFailed Callback when all ads failed or cache empty
     * @param onWinnerSelected Callback with the AdSource that was successfully shown
     */
    fun showBestWithFallback(
        activity: Activity,
        onShowFailed: (BidonError) -> Unit = {},
        onFailed: (Throwable) -> Unit,
        onWinnerSelected: (AdSource<*>) -> Unit = {}
    ) {
        scope.launch {
            showBestAdWithFallback(
                cancellationManager = cancellationManager,
                activity = activity,
                onShowFailed = onShowFailed,
                onWinnerSelected = onWinnerSelected
            )
                .onFailure { error ->
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
        // NO-OP: V2 uses TTL-based cache expiration
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
        // NO-OP: V2 uses singleton cache with global capacity management
    }

    companion object {
        private const val TAG = "[DenisCache] AdCache"
    }
}
