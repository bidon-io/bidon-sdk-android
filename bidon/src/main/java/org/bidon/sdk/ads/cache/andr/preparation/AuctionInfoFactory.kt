package org.bidon.sdk.ads.cache.andr.preparation

import org.bidon.sdk.ads.AdUnitInfo
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.StatsAdUnit
import java.util.UUID

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

    fun create(auctionResult: AuctionResult): AuctionInfo {
        val stats = auctionResult.adSource.getStats()
        return AuctionInfo(
            auctionId = UUID.randomUUID().toString(),
            auctionConfigurationId = null,
            auctionConfigurationUid = null,
            auctionTimeout = 0,
            auctionPricefloor = stats.auctionPricefloor,
            noBids = null,
            adUnits =
                listOf(
                    AdUnitInfo(
                        demandId = stats.demandId.demandId,
                        label = stats.adUnit?.label,
                        price = stats.price,
                        uid = stats.adUnit?.uid,
                        bidType = stats.bidType?.code,
                        fillStartTs = stats.fillStartTs,
                        fillFinishTs = stats.fillFinishTs,
                        status = RoundStatus.Win.code,
                        ext = stats.adUnit?.extra?.toString(),
                    )
                ),
        )
    }
}
