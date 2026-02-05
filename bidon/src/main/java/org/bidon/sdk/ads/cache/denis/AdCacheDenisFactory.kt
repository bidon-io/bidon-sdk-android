package org.bidon.sdk.ads.cache.denis

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.denis.lifecycle.LifecycleManager
import org.bidon.sdk.ads.cache.denis.orchestration.CoordinationLayer
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.usecases.GetTokensWithSkipUseCase
import org.bidon.sdk.ads.cache.impl.AdCacheDenisImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.regulation.Regulation

/**
 * Factory for creating V2 AdCache instances with all denis package dependencies.
 *
 * Isolates complex dependency graph construction from AdCacheFactoryImpl.
 * This factory creates instance-scoped components (LifecycleManager, CoordinationLayer)
 * and wires them with processors and orchestrator.
 */
internal object AdCacheDenisFactory {

    /**
     * Create fully-wired V2 AdCache instance.
     *
     * Creates and assembles:
     * - LifecycleManager (instance-scoped for ad lifecycle)
     * - RtbProcessor and CpmProcessor (shared processors for load operations)
     * - CoordinationLayer (warm/cold start orchestration, creates orchestrator per-auction)
     * - AdCacheDenisImpl (facade entry point)
     *
     * Note: CallbackCoordinator and ParallelAuctionOrchestrator are created per-auction
     * inside CoordinationLayer.handleColdStart() with actual callbacks from cache() call.
     * This ensures multiple cache() calls fire their own callbacks correctly.
     *
     * @param demandAd Ad instance configuration
     * @param resolver Auction resolver (V1 compatibility - unused in V2)
     * @param adaptersSource Source of available ad adapters
     * @param getTokens Use case for collecting bidding tokens
     * @param getAuctionRequest Use case for creating auction requests
     * @param biddingConfig Bidding configuration (timeouts, etc.)
     * @param regulation Privacy/consent regulations
     * @return Fully-wired AdCache instance
     */
    fun create(
        demandAd: DemandAd,
        resolver: AuctionResolver,
        adaptersSource: AdaptersSource,
        getTokens: GetTokensUseCase,
        getAuctionRequest: GetAuctionRequestUseCase,
        biddingConfig: BiddingConfig,
        regulation: Regulation,
    ): AdCache {
        // Create instance-scoped lifecycle manager
        val lifecycleManager = LifecycleManager()

        // Create processors with dependencies (shared across auctions)
        val rtbProcessor = RtbProcessor(
            adaptersSource = adaptersSource,
            regulation = regulation,
        )
        val cpmProcessor = CpmProcessor(
            adaptersSource = adaptersSource,
            regulation = regulation,
        )

        // Create V2-specific token wrapper that filters cached demand IDs
        val getTokensWithSkip = GetTokensWithSkipUseCase(delegate = getTokens)

        // Create coordination layer with processors (orchestrator created per-auction)
        val coordinationLayer = CoordinationLayer(
            adaptersSource = adaptersSource,
            getTokensWithSkip = getTokensWithSkip,
            getAuctionRequest = getAuctionRequest,
            rtbProcessor = rtbProcessor,
            cpmProcessor = cpmProcessor,
            lifecycleManager = lifecycleManager,
        )

        return AdCacheDenisImpl(
            demandAd = demandAd,
            resolver = resolver,
            coordinationLayer = coordinationLayer,
            lifecycleManager = lifecycleManager,
            biddingConfig = biddingConfig,
        )
    }
}
