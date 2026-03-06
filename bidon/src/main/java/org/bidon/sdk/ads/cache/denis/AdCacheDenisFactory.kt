package org.bidon.sdk.ads.cache.denis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.denis.lifecycle.CancellationManager
import org.bidon.sdk.ads.cache.denis.lifecycle.PeriodicSweepJob
import org.bidon.sdk.ads.cache.denis.orchestration.CoordinationLayer
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.ads.cache.denis.stats.CacheAuctionStat
import org.bidon.sdk.ads.cache.denis.stores.ReadyToShowCache
import org.bidon.sdk.ads.cache.denis.stores.RtbPayloadCache
import org.bidon.sdk.ads.cache.impl.AdCacheDenisImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.di.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for creating V2 AdCache instances with all denis package dependencies.
 *
 * Isolates complex dependency graph construction from AdCacheFactoryImpl.
 * Gets V2-specific dependencies directly from DI container, keeping them
 * encapsulated within V2 implementation.
 *
 * Shared components (caches, sweep job, scope) are keyed by ad type so that
 * multiple AdCache instances for the same ad type share a single cache.
 * Per-instance components (CancellationManager, CoordinationLayer, etc.)
 * are created fresh for each instance.
 */
internal object AdCacheDenisFactory {

    /**
     * Cached AdCache instances keyed by ad type label (e.g., "BANNER", "INTERSTITIAL").
     * All callers requesting the same ad type get the same AdCache instance.
     */
    private val instancesByAdType = ConcurrentHashMap<String, AdCache>()

    /**
     * Get or create AdCache instance for the given ad type.
     *
     * Returns the same instance for the same ad type — all InterstitialImpl
     * instances share one AdCache with one cache, one sweep job, etc.
     *
     * @param demandAd Ad instance configuration
     * @param resolver Auction resolver for CacheAuctionStat winner sorting
     * @return AdCache instance (shared per ad type)
     */
    fun create(
        demandAd: DemandAd,
        resolver: AuctionResolver,
    ): AdCache {
        val adTypeLabel = demandAd.adType.code.uppercase()
        return instancesByAdType.getOrPut(adTypeLabel) {
            createNew(demandAd, resolver, adTypeLabel)
        }
    }

    private fun createNew(
        demandAd: DemandAd,
        resolver: AuctionResolver,
        adTypeLabel: String,
    ): AdCache {
        // Get V2-specific dependencies from DI container
        val adaptersSource = get<AdaptersSource>()
        val getTokens = get<GetTokensUseCase>()
        val getAuctionRequest = get<GetAuctionRequestUseCase>()
        val biddingConfig = get<BiddingConfig>()
        val auctionStat = CacheAuctionStat(
            statsRequest = get<StatsRequestUseCase>(),
            resolver = resolver,
            adTypeLabel = adTypeLabel,
        )
        val readyToShowCache = ReadyToShowCache(adTypeLabel)
        val rtbPayloadCache = RtbPayloadCache(adTypeLabel)

        val instanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val periodicSweepJob = PeriodicSweepJob(instanceScope, readyToShowCache, rtbPayloadCache, adTypeLabel)
        val cancellationManager = CancellationManager(adTypeLabel)

        val rtbProcessor = RtbProcessor(
            adaptersSource = adaptersSource,
            rtbPayloadCache = rtbPayloadCache,
            adTypeLabel = adTypeLabel,
        )
        val cpmProcessor = CpmProcessor(
            adaptersSource = adaptersSource,
            readyToShowCache = readyToShowCache,
            adTypeLabel = adTypeLabel,
        )

        val coordinationLayer = CoordinationLayer(
            adaptersSource = adaptersSource,
            getTokens = getTokens,
            getAuctionRequest = getAuctionRequest,
            rtbProcessor = rtbProcessor,
            cpmProcessor = cpmProcessor,
            scope = instanceScope,
            cancellationManager = cancellationManager,
            auctionStat = auctionStat,
            readyToShowCache = readyToShowCache,
            rtbPayloadCache = rtbPayloadCache,
            adTypeLabel = adTypeLabel,
        )

        return AdCacheDenisImpl(
            demandAd = demandAd,
            coordinationLayer = coordinationLayer,
            periodicSweepJob = periodicSweepJob,
            cancellationManager = cancellationManager,
            biddingConfig = biddingConfig,
            readyToShowCache = readyToShowCache,
            adTypeLabel = adTypeLabel,
        )
    }
}
