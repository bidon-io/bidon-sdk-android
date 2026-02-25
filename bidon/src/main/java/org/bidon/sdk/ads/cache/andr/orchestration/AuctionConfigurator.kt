package org.bidon.sdk.ads.cache.andr.orchestration

import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ext.printWaterfall
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.logs.logging.impl.logError

internal class AuctionConfigurator(
    private val tag: String,
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val biddingConfig: BiddingConfig,
) {
    suspend fun configure(
        auctionId: String,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
    ): Result<Pair<AuctionResponse, Map<String, TokenInfo>>> {
        val adapters = adaptersSource.adapters.associate { it.demandId.demandId to it.adapterInfo }
        val tokens = getTokens(adTypeParam, adaptersSource, biddingConfig.tokenTimeout)
        val response =
            getAuctionRequest
                .request(adTypeParam, auctionId, demandAd, adapters, tokens)
                .onSuccess {
                    if (auctionId != it.auctionId) {
                        logError(tag, "Auction ID has been changed", IllegalStateException())
                    }
                    it.printWaterfall(demandAd.adType)
                }
        return response.map { it to tokens }
    }
}
