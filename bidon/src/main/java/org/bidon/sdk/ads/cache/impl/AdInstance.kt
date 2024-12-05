package org.bidon.sdk.ads.cache.impl

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.ads.AuctionInfo

/**
 * Created by Bidon Team on 06/11/2024.'
 *
 * Represents an ad instance.
 */
internal data class AdInstance(
    val adSource: AdSource<*>,
    val auctionInfo: AuctionInfo,
) {
    val ecpm: Double get() = adSource.getStats().ecpm
}

internal fun Set<AdInstance>.asString(): String {
    return "(${this.size}) " + this.joinToString { auctionResult ->
        auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.ecpm}" }
    }
}
