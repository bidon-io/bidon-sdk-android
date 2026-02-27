package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.AdCacheStrategy
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType

internal class AdUnitPreparer(
    private val tag: String,
    private val adCacheStrategy: AdCacheStrategy,
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

        logInfo(tag, "Popped ${cachedRtbResults.size} cached RTB results")

        val (serverRtbAdUnits, cpmAdUnits) =
            (response.adUnits
                ?: emptyList()).partition { it.bidType == BidType.RTB }

        logInfo(tag, "Server: ${serverRtbAdUnits.size} RTB, ${cpmAdUnits.size} CPM")

        val (mergedRtbAdUnits, mergedTokens) =
            rtbResultsMerger.merge(
                cachedRtbResults,
                serverRtbAdUnits,
                tokens
            )

        logInfo(tag, "Merged: ${mergedRtbAdUnits.size} RTB units, ${mergedTokens.size} tokens")

        val allStats = demandStatistics.getAllStats(demandAd.adType)
        val sortedAdUnits =
            (mergedRtbAdUnits + cpmAdUnits)
                .sortedByRankDescending(allStats, adCacheStrategy.rankingWeights)

        logInfo(
            tag,
            "Ranked ${sortedAdUnits.size} units. Top: ${
                sortedAdUnits.take(3).joinToString { "${it.demandId}:${it.pricefloor}" }
            }"
        )

        return Result(context, sortedAdUnits, mergedTokens)
    }

    data class Result(
        val context: AuctionContext,
        val sortedAdUnits: List<AdUnit>,
        val tokens: Map<AdUnit, TokenInfo>,
    )
}
