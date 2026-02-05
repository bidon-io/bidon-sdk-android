package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.impl.alex.AdCacheStorage
import org.bidon.sdk.ads.cache.impl.alex.UserFlow
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get

/**
 * V5 implementation of AdCache with two-bucket caching strategy:
 * - FillEntry for BidType.CPM - loaded ads ready to show
 * - BidEntry for BidType.RTB - raw bids with payload, not yet filled (30min TTL)
 */
internal class AdCacheAlexImpl(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver,
) : AdCache {

    private var adCache: AdCache? = null
    private var winner: AuctionResult? = null
    private val userFlow: UserFlow by lazy {
        get()
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        logInfo(TAG, "Cache started ${adTypeParam.auctionKey} for ${demandAd.adType}")
        val adCache = AdCacheStorage.getCache(
            auctionKey = adTypeParam.auctionKey ?: "default",
            resolver = resolver,
            demandAd = demandAd
        ).also {
            adCache = it
        }
        adCache.cache(
            adTypeParam = when (adTypeParam) {
                is AdTypeParam.Banner -> error("Not implemented yet")
                is AdTypeParam.Interstitial -> {
                    AdTypeParam.Interstitial(
                        activity = adTypeParam.activity,
                        pricefloor = userFlow.getAveragePrice(demandAd.adType) * 0.5f,
                        auctionKey = "1O16GQT380000"//adTypeParam.auctionKey,
                    ).also {
                        logInfo(
                            TAG,
                            "Transformed Interstitial AdTypeParam with new pricefloor=${it.pricefloor} for auctionKey=${it.auctionKey}"
                        )
                    }
                }

                is AdTypeParam.Rewarded -> error("Not implemented yet")
            },
            onSuccess = { auctionResult, auctionInfo ->
                winner = auctionResult
                onSuccess(auctionResult, auctionInfo)
            },
            onFailure = { a, t ->
                userFlow.recordImpression(
                    demandAd.adType,
                    userFlow.getAveragePrice(demandAd.adType) * 0.5f
                )
                onFailure(a, t)
            }
        )
    }

    override fun peek(): AuctionResult? {
        return winner
    }

    override fun pop(): AuctionResult? {
        val winner = winner
        userFlow.recordImpression(
            adType = demandAd.adType,
            price = winner?.adSource?.getAd()?.price ?: 0.0
        )
        this.winner = null
        return winner
    }

    override suspend fun poll(): AuctionResult {
        error("No one is using this method yet, we can implement it when needed")
    }

    override fun clear() {
        winner = null
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // Currently no settings to apply
    }

    companion object {
        private const val TAG = "AdCacheAlex"
    }
}
