package org.bidon.sdk.ads.cache.denis.orchestration

import org.bidon.sdk.ads.cache.denis.stores.CacheEntry
import org.bidon.sdk.auction.models.AuctionResult

/**
 * Sealed class hierarchy representing three auction start states.
 *
 * Drives cold/warm start decision at auction entry point:
 * - WarmStart: READY_TO_SHOW cache has ads → serve immediately (<1s)
 * - ColdStartWithCache: RTB_PAYLOAD cache has entries → skip tokens for cached adapters
 * - PureColdStart: Both caches empty → full token collection with user pricefloor
 *
 * Exhaustive when expressions ensure compile-time safety.
 * Each state carries necessary data for subsequent processing.
 */
internal sealed class AuctionStartState {
    /**
     * Warm start: READY_TO_SHOW cache has ads, serve immediately.
     *
     * User decision: "Always serve immediately if READY_TO_SHOW cache is not empty"
     *
     * @property bestAd Entry with highest eCPM from cache for immediate callback
     */
    data class WarmStart(
        val bestAd: CacheEntry<AuctionResult>
    ) : AuctionStartState()

    /**
     * Cold start with RTB optimization: RTB_PAYLOAD cache has entries.
     *
     * Skip token collection for cached adapters (partial warm start).
     *
     * @property cachedDemandIds Set of demandIds with valid RTB payloads (skip their tokens)
     */
    data class ColdStartWithCache(
        val cachedDemandIds: Set<String>,
    ) : AuctionStartState()

    /**
     * Pure cold start: Both caches empty, full auction required.
     *
     * Respect publisher's minimum eCPM.
     *
     * @property userPricefloor Publisher-configured minimum pricefloor
     */
    data class PureColdStart(
        val userPricefloor: Double
    ) : AuctionStartState()
}
