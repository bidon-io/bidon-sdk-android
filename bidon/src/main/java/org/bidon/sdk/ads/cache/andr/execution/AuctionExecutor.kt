package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo

internal interface AuctionExecutor {
    suspend fun execute(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): List<AuctionResult>
}