package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo

internal class RtbResultsMerger {
    fun merge(
        cachedRtbResults: Collection<Pair<AdUnit, TokenInfo?>>,
        serverRtbAdUnits: List<AdUnit>,
        serverTokens: Map<String, TokenInfo>,
    ): Pair<List<AdUnit>, Map<AdUnit, TokenInfo>> {
        val tokens = mutableMapOf<AdUnit, TokenInfo>()
        val adUnits =
            buildList {
                for (serverAdUnit in serverRtbAdUnits) {
                    add(serverAdUnit)
                    serverTokens[serverAdUnit.demandId]
                        ?.let { tokens[serverAdUnit] = it }
                }
                for ((adUnit, tokenInfo) in cachedRtbResults) {
                    add(adUnit)
                    tokenInfo?.let { tokens[adUnit] = it }
                }
            }
        return adUnits to tokens
    }
}
