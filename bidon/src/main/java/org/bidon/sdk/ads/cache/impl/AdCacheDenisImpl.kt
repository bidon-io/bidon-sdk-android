package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
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
     * Remove and return best ad immediately or throw if cache empty.
     *
     * V2 behavior: Returns immediately from current cache state (non-blocking).
     * V1 behavior: Suspended waiting for results.first() (blocking).
     *
     * @return AuctionResult with highest eCPM
     * @throws NoSuchElementException if cache is empty
     */
    override suspend fun poll(): AuctionResult {
        val result = pop()
        return result ?: throw NoSuchElementException("Cache is empty")
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
     * Configure cache settings.
     *
     * @param settings Cache configuration (capacity, etc.)
     */
    override fun withSettings(settings: Cacheable.Settings) {
        ReadyToShowCache.setCapacity(settings.cacheCapacity)
        logInfo(TAG, "Cache settings applied: capacity=${settings.cacheCapacity}")
    }

    companion object {
        private const val TAG = "AdCacheDenisImpl"
    }
}
