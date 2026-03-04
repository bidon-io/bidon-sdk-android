package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.ext.asStatisticAdType
import org.bidon.sdk.ads.cache.andr.ext.getAdSources
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType

internal class AdSourceResolver(
    private val tag: String,
) {
    fun resolve(
        adUnit: AdUnit,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        adapters: Collection<Adapter>,
        tokenInfo: TokenInfo?,
    ): AdSource<AdAuctionParams>? {
        val adSource =
            adapters
                .firstOrNull { it.demandId.demandId == adUnit.demandId }
                ?.getAdSources(demandAd.adType, tag)
                ?.also { it.setStatisticAdType(adTypeParam.asStatisticAdType()) }
        if (adUnit.bidType == BidType.RTB) {
            tokenInfo?.let { adSource?.setTokenInfo(it) }
        }
        if (adSource != null) {
            logInfo(tag, "Resolved ${adUnit.demandId} (bidType=${adUnit.bidType})")
        } else {
            logInfo(tag, "No adapter for ${adUnit.demandId} among ${adapters.size} adapters")
        }
        return adSource
    }
}
