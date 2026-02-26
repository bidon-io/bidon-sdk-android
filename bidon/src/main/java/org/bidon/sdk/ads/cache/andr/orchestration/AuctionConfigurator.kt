package org.bidon.sdk.ads.cache.andr.orchestration

import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ext.printWaterfall
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.logs.logging.impl.logError

internal class AuctionConfigurator(
    private val tag: String,
    private val adaptersCollector: AdaptersCollector,
    private val adaptersInfoCollector: AdaptersInfoCollector,
    private val getAuctionRequestUseCase: GetAuctionRequestUseCase,
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
    private val tokensCollector: TokensCollector,
) {
    suspend fun configure(
        auctionId: String,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
    ): Result<Pair<AuctionResponse, Map<String, TokenInfo>>> {
        val cachedRtbAdUnits =
            rtbResultsStore
                .peekAll()
                .filter { it.price >= adTypeParam.pricefloor }
        val biddingAdapters = adaptersCollector.collectBidding()
        val (adaptersInfo, tokens) =
            if (cachedRtbAdUnits.isNotEmpty()) {
                mapOf<String, AdapterInfo>() to mapOf<String, TokenInfo>()
            } else {
                val cachedDemandIds =
                    cachedRtbAdUnits.map(RtbResultStore.Entry::demandId).toSet()
                val adapters = biddingAdapters.filterNot { it.demandId.demandId in cachedDemandIds }
                val adaptersInfo = adaptersInfoCollector.collect(adapters)
                val tokens = tokensCollector.collect(adTypeParam, adapters)
                adaptersInfo to tokens
            }
        return request(auctionId, demandAd, adTypeParam, adaptersInfo, tokens)
    }

    private suspend fun request(
        auctionId: String,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        adaptersInfo: Map<String, AdapterInfo>,
        tokens: Map<String, TokenInfo>,
    ): Result<Pair<AuctionResponse, Map<String, TokenInfo>>> {
        val response =
            getAuctionRequestUseCase
                .request(adTypeParam, auctionId, demandAd, adaptersInfo, tokens)
                .onSuccess {
                    if (auctionId != it.auctionId) {
                        logError(
                            tag,
                            "Auction ID has been changed",
                            IllegalStateException()
                        )
                    }
                    it.printWaterfall(demandAd.adType)
                }
        return response.map { it to tokens }
    }
}