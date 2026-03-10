package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.concurrent.TimeUnit

internal class RtbResultsMerger {
    fun merge(
        cachedRtbEntries: Collection<RtbResultStore.Entry>,
        serverRtbAdUnits: List<AdUnit>,
        serverTokens: Map<String, TokenInfo>,
    ): Pair<List<AdUnit>, Map<AdUnit, TokenInfo>> {
        val tokens = mutableMapOf<AdUnit, TokenInfo>()
        val adUnitsByDemand = linkedMapOf<String, AdUnit>()

        for (serverAdUnit in serverRtbAdUnits) {
            adUnitsByDemand[serverAdUnit.demandId] = serverAdUnit
            serverTokens[serverAdUnit.demandId]?.let { tokens[serverAdUnit] = it }
        }
        for (entry in cachedRtbEntries) {
            val adUnit = entry.adUnit
            val existing = adUnitsByDemand[adUnit.demandId]
            if (existing == null) {
                adUnitsByDemand[adUnit.demandId] = adUnit
                tokens[adUnit] = entry.tokenInfo
            } else {
                val remainingTtl = entry.expireAt - SystemTimeNow
                val priceDiff = (adUnit.pricefloor - existing.pricefloor) / existing.pricefloor
                if (priceDiff > PRICE_THRESHOLD && remainingTtl > MIN_REMAINING_TTL) {
                    tokens.remove(existing)
                    adUnitsByDemand[adUnit.demandId] = adUnit
                    tokens[adUnit] = entry.tokenInfo
                }
            }
        }
        return adUnitsByDemand.values.toList() to tokens
    }

    companion object {
        private val MIN_REMAINING_TTL = TimeUnit.MINUTES.toMillis(2)

        private const val PRICE_THRESHOLD = 0.05 // 5%
    }
}
