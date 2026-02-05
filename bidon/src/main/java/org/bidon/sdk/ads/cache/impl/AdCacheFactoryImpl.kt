package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheFactory
import org.bidon.sdk.ads.cache.AdCacheVersion
import org.bidon.sdk.ads.cache.denis.lifecycle.LifecycleManager
import org.bidon.sdk.ads.cache.denis.orchestration.CallbackCoordinator
import org.bidon.sdk.ads.cache.denis.orchestration.CoordinationLayer
import org.bidon.sdk.ads.cache.denis.orchestration.ParallelAuctionOrchestrator
import org.bidon.sdk.ads.cache.denis.processors.CpmProcessor
import org.bidon.sdk.ads.cache.denis.processors.RtbProcessor
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Factory implementation that creates version-specific AdCache instances.
 */
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val biddingConfig: BiddingConfig,
    private val regulation: Regulation,
) : AdCacheFactory {

    override fun create(demandAd: DemandAd): AdCache {
        val version = AdCacheVersion.fromInt(demandAd.getExtras()["cache_size"] as? Int)
        return when (version) {
            AdCacheVersion.V1 -> AdCacheImpl(
                demandAd = demandAd,
                scope = CoroutineScope(SdkDispatchers.Main),
                resolver = resolver
            )

            AdCacheVersion.V2 -> {
                // Create instance-scoped lifecycle manager
                val lifecycleManager = LifecycleManager()

                // Create processors with dependencies
                val rtbProcessor = RtbProcessor(
                    adaptersSource = adaptersSource,
                    regulation = regulation,
                )
                val cpmProcessor = CpmProcessor(
                    adaptersSource = adaptersSource,
                    regulation = regulation,
                )

                // Create callback coordinator with no-op callbacks
                // NOTE: This is a temporary limitation - orchestrator should be created
                // per-auction with actual callbacks, not shared across auctions
                val callbackCoordinator = CallbackCoordinator(
                    onAdLoaded = { _, _ -> }, // No-op: callbacks handled elsewhere
                    onAdLoadFailed = { _, _ -> }, // No-op: callbacks handled elsewhere
                )

                // Create parallel auction orchestrator
                val orchestrator = ParallelAuctionOrchestrator(
                    rtbProcessor = rtbProcessor,
                    cpmProcessor = cpmProcessor,
                    callbackCoordinator = callbackCoordinator,
                )

                // Create coordination layer with all dependencies
                val coordinationLayer = CoordinationLayer(
                    adaptersSource = adaptersSource,
                    getTokens = getTokens,
                    getAuctionRequest = getAuctionRequest,
                    orchestrator = orchestrator,
                    lifecycleManager = lifecycleManager,
                )

                AdCacheDenisImpl(
                    demandAd = demandAd,
                    resolver = resolver,
                    coordinationLayer = coordinationLayer,
                    lifecycleManager = lifecycleManager,
                    biddingConfig = biddingConfig,
                )
            }

            AdCacheVersion.V3 -> {
                AdCacheAndreiImpl(
                    demandAd = demandAd,
                    resolver = resolver
                )
            }
            AdCacheVersion.V4 -> {
                AdCacheVladimirImpl(
                    demandAd = demandAd,
                    resolver = resolver
                )
            }
            AdCacheVersion.V5 -> {
                AdCacheAlexImpl(
                    demandAd = demandAd,
                    resolver = resolver,
                )
            }
        }
    }
}
