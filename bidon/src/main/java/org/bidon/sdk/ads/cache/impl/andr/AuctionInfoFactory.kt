package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.StatsAdUnit

internal class AuctionInfoFactory(
    private val tag: String,
) {
    fun createSuccess(
        response: AuctionResponse,
        roundStat: RoundStat? = null,
    ): AuctionInfo =
        create(response, roundStat).also {
            printStatsData(response, roundStat, it)
            logInfo(tag, "Rounds completed")
        }

    fun createFailure(
        response: AuctionResponse?,
        roundStat: RoundStat? = null,
    ): AuctionInfo? =
        response?.let {
            create(it, roundStat)
                .also { info -> printStatsData(it, roundStat, info) }
        }

    private fun create(
        response: AuctionResponse,
        roundStat: RoundStat?,
    ): AuctionInfo =
        AuctionInfo(
            response.auctionId,
            response.auctionConfigurationId,
            response.auctionConfigurationUid,
            response.auctionTimeout,
            response.pricefloor,
            roundStat?.noBids?.map(AdUnit::toAuctionNoBidInfo),
            roundStat?.demands?.map(StatsAdUnit::toAuctionInfo)
        )

    private fun printStatsData(
        auctionData: AuctionResponse,
        statResult: RoundStat?,
        auctionInfo: AuctionInfo,
    ) {
        logInfo(
            tag,
            "Was received: \nAdUnits: ${auctionData.adUnits?.size} \nNoBids: ${auctionData.noBids?.size}" +
                "\nWas sent:\nStats: ${statResult?.demands?.size} \nAuctionInfo AdUnits: ${auctionInfo.adUnits?.size} \n" +
                "AuctionInfo NoBids: ${auctionInfo.noBids?.size}"
        )
    }
}
