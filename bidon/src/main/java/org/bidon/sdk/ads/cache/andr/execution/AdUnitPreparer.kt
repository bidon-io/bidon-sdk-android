package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.stats.models.BidType

internal class AdUnitPreparer(
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
    private val rtbResultsMerger: RtbResultsMerger,
    private val demandStatistics: DemandStatistics,
) {
    fun prepare(
        demandAd: DemandAd,
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): Result {
        val context =
            AuctionContext(
                response.auctionId,
                response.auctionConfigurationId ?: 0L,
                response.auctionConfigurationUid ?: "",
                response.externalWinNotificationsEnabled
            )

        val cachedRtbResults = rtbResultsStore.popAll().map(RtbResultStore.Entry::unwrap)
        val (serverRtbAdUnits, cpmAdUnits) =
            (response.adUnits
                ?: emptyList()).partition { it.bidType == BidType.RTB }
        val (mergedRtbAdUnits, mergedTokens) =
            rtbResultsMerger.merge(
                cachedRtbResults,
                serverRtbAdUnits,
                tokens
            )

        val allStats = demandStatistics.getAllStats(demandAd.adType)
        val sortedAdUnits =
            (mergedRtbAdUnits + cpmAdUnits).sortedByRankDescending(allStats, demandAd.adType)

        return Result(context, sortedAdUnits, mergedTokens)
    }

    data class Result(
        val context: AuctionContext,
        val sortedAdUnits: List<AdUnit>,
        val tokens: Map<AdUnit, TokenInfo>,
    )
}
