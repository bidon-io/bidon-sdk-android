package org.bidon.sdk.ads.cache.andr.preparation

import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.StatsAdUnit

internal class AuctionInfoFactory {
    fun create(
        response: AuctionResponse? = null,
        roundStat: RoundStat? = null,
    ): AuctionInfo? =
        response?.let {
            AuctionInfo(
                it.auctionId,
                it.auctionConfigurationId,
                it.auctionConfigurationUid,
                it.auctionTimeout,
                response.pricefloor,
                roundStat?.noBids?.map(AdUnit::toAuctionNoBidInfo),
                roundStat?.demands?.map(StatsAdUnit::toAuctionInfo)
            )
        }
}
