package org.bidon.sdk.ads.cache.andr.execution

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.cache.andr.ext.asStatisticAdType
import org.bidon.sdk.ads.cache.andr.ext.getAdSources
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.stats.models.BidType

internal class AdSourceResolver(
    private val tag: String,
    private val adaptersSource: AdaptersSource,
) {
    fun resolve(
        adUnit: AdUnit,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        tokenInfo: TokenInfo?,
    ): AdSource<AdAuctionParams>? {
        val adSource =
            adaptersSource.adapters
                .find { it.demandId.demandId == adUnit.demandId }
                ?.also(Adapter::applyRegulation)
                ?.getAdSources(demandAd.adType, tag)
                ?.also { it.setStatisticAdType(adTypeParam.asStatisticAdType()) }

        if (adUnit.bidType == BidType.RTB) {
            tokenInfo?.let { adSource?.setTokenInfo(it) }
        }
        return adSource
    }
}
