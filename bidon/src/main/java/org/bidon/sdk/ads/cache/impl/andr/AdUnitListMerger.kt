package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.stats.models.BidType

internal class AdUnitListMerger {
    fun merge(
        cachedAdUnits: List<AdUnit>,
        serverAdUnits: List<AdUnit>,
    ): List<AdUnit> {
        // RTB merge: keep higher-priced bid per demandId
        val cachedByDemand = cachedAdUnits.associateBy(AdUnit::demandId)
        return buildList {
            val usedCachedIds = mutableSetOf<String>()
            serverAdUnits.forEach {
                val cached = cachedByDemand[it.demandId]
                if (cached != null && it.bidType == BidType.RTB) {
                    usedCachedIds.add(it.demandId)
                    add(if (cached.pricefloor >= it.pricefloor) cached else it)
                } else {
                    add(it)
                }
            }
            cachedAdUnits
                .filter { it.demandId !in usedCachedIds }
                .forEach(::add)
        }
    }
}