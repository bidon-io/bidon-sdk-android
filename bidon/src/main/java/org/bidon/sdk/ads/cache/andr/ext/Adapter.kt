package org.bidon.sdk.ads.cache.andr.ext

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.logs.logging.impl.logError

internal fun Adapter.getAdSources(
    adType: AdType,
    tag: String
): AdSource<AdAuctionParams>? {
    val adapterDemandId = demandId
    return when (adType) {
        AdType.Interstitial -> {
            (this as? AdProvider.Interstitial<AdAuctionParams>)
                ?.let { adapter ->
                    runCatching { adapter.interstitial().apply { addDemandId(adapterDemandId) } }
                        .onFailure { logError(tag, "Failed to create interstitial ad source", it) }
                        .getOrNull()
                }
        }

        AdType.Rewarded -> {
            (this as? AdProvider.Rewarded<AdAuctionParams>)
                ?.let { adapter ->
                    runCatching { adapter.rewarded().apply { addDemandId(adapterDemandId) } }
                        .onFailure { logError(tag, "Failed to create rewarded ad source", it) }
                        .getOrNull()
                }
        }

        AdType.Banner -> {
            (this as? AdProvider.Banner<AdAuctionParams>)
                ?.let { adapter ->
                    runCatching { adapter.banner().apply { addDemandId(adapterDemandId) } }
                        .onFailure { logError(tag, "Failed to create banner ad source", it) }
                        .getOrNull()
                }
        }
    }
}