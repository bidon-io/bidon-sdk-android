package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType

internal class AdUnitPreparer(
    private val tag: String,
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
    private val rtbResultsMerger: RtbResultsMerger,
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

        val cachedRtbEntries = rtbResultsStore.popAll()

        logInfo(tag, "Popped ${cachedRtbEntries.size} cached RTB results")

        val (serverRtbAdUnits, cpmAdUnits) =
            (response.adUnits
                ?: emptyList()).partition { it.bidType == BidType.RTB }

        logInfo(tag, "Server: ${serverRtbAdUnits.size} RTB, ${cpmAdUnits.size} CPM")

        val (mergedRtbAdUnits, mergedTokens) =
            rtbResultsMerger.merge(
                cachedRtbEntries,
                serverRtbAdUnits,
                tokens
            )

        logInfo(tag, "Merged: ${mergedRtbAdUnits.size} RTB units, ${mergedTokens.size} tokens")

        val allAdUnits = mergedRtbAdUnits + cpmAdUnits

        logInfo(
            tag,
            "Prepared ${allAdUnits.size} units. Top: ${
                allAdUnits.take(3).joinToString { "${it.demandId}:${it.pricefloor}" }
            }"
        )

        return Result(context, allAdUnits, mergedTokens)
    }

    data class Result(
        val context: AuctionContext,
        val sortedAdUnits: List<AdUnit>,
        val tokens: Map<AdUnit, TokenInfo>,
    )
}
