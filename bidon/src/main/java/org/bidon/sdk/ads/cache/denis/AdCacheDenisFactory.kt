package org.bidon.sdk.ads.cache.denis

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.denis.lifecycle.AdInstanceScope
import org.bidon.sdk.ads.cache.denis.lifecycle.CancellationManager
import org.bidon.sdk.ads.cache.denis.lifecycle.PeriodicSweepJob
import org.bidon.sdk.ads.cache.denis.orchestration.CoordinationLayer
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stats.CacheAuctionStat
import org.bidon.sdk.ads.cache.denis.usecases.GetTokensWithSkipUseCase
import org.bidon.sdk.ads.cache.impl.AdCacheDenisImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.get

/**
 * Factory for creating V2 AdCache instances with all denis package dependencies.
 *
 * Isolates complex dependency graph construction from AdCacheFactoryImpl.
 * Gets V2-specific dependencies directly from DI container, keeping them
 * encapsulated within V2 implementation.
 *
 * This factory creates instance-scoped components (AdInstanceScope, PeriodicSweepJob,
 * CancellationManager, CoordinationLayer) and wires them with processors and orchestrator.
 */
internal object AdCacheDenisFactory {

    /**
     * Create fully-wired V2 AdCache instance.
     *
     * Creates and assembles:
     * - AdInstanceScope, PeriodicSweepJob, CancellationManager (instance-scoped lifecycle)
     * - RtbProcessor and CpmProcessor (shared processors for load operations)
     * - CoordinationLayer (warm/cold start orchestration, creates orchestrator per-auction)
     * - AdCacheDenisImpl (facade entry point)
     *
     * Note: CallbackCoordinator and ParallelAuctionOrchestrator are created per-auction
     * inside CoordinationLayer.handleColdStart() with actual callbacks from cache() call.
     * This ensures multiple cache() calls fire their own callbacks correctly.
     *
     * V2-specific dependencies are obtained directly from DI container to avoid
     * polluting AdCacheFactoryImpl with V2-only dependencies.
     *
     * @param demandAd Ad instance configuration
     * @param resolver Auction resolver for CacheAuctionStat winner sorting
     * @return Fully-wired AdCache instance
     */
    fun create(
        demandAd: DemandAd,
        resolver: AuctionResolver,
    ): AdCache {
        // Get V2-specific dependencies from DI container
        val adaptersSource = get<AdaptersSource>()
        val getTokens = get<GetTokensUseCase>()
        val getAuctionRequest = get<GetAuctionRequestUseCase>()
        val biddingConfig = get<BiddingConfig>()
        // Use cache-specific AuctionStat that preserves Successful status for non-winner cached ads
        val auctionStat = CacheAuctionStat(
            statsRequest = get<StatsRequestUseCase>(),
            resolver = resolver,
        )
        // Create instance-scoped lifecycle components
        val adInstanceScope = AdInstanceScope()
        val periodicSweepJob = PeriodicSweepJob(adInstanceScope)
        val cancellationManager = CancellationManager()

        // Create processors with dependencies (shared across auctions)
        val rtbProcessor = RtbProcessor(
            adaptersSource = adaptersSource,
        )
        val cpmProcessor = CpmProcessor(
            adaptersSource = adaptersSource,
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
            scope = adInstanceScope.scope,
            cancellationManager = cancellationManager,
            auctionStat = auctionStat,
        )

        return AdCacheDenisImpl(
            demandAd = demandAd,
            coordinationLayer = coordinationLayer,
            adInstanceScope = adInstanceScope,
            periodicSweepJob = periodicSweepJob,
            cancellationManager = cancellationManager,
            biddingConfig = biddingConfig,
        )
    }
}
