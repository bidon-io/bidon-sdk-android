package org.bidon.sdk.ads.cache.andr.execution

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
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): Pair<List<AdUnit>, Map<AdUnit, TokenInfo>> {
        val cachedRtbEntries = rtbResultsStore.popAll()
        logInfo(tag, "Popped ${cachedRtbEntries.size} cached RTB results")

        val (serverRtbAdUnits, cpmAdUnits) =
            (response.adUnits ?: emptyList()).partition { it.bidType == BidType.RTB }
        logInfo(tag, "Server: ${serverRtbAdUnits.size} RTB, ${cpmAdUnits.size} CPM")

        val (mergedRtbAdUnits, mergedTokens) =
            rtbResultsMerger.merge(cachedRtbEntries, serverRtbAdUnits, tokens)
        logInfo(tag, "Merged: ${mergedRtbAdUnits.size} RTB units, ${mergedTokens.size} tokens")

        val allAdUnits = (mergedRtbAdUnits + cpmAdUnits).sortedByDescending { it.pricefloor }
        logInfo(
            tag,
            "Prepared ${allAdUnits.size} units. Top: ${
                allAdUnits.take(3).joinToString { "${it.demandId}:${it.pricefloor}" }
            }"
        )

        return allAdUnits to mergedTokens
    }
}
