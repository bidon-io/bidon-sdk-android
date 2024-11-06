package org.bidon.sdk.ads.cache

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam

/**
 * Created by Bidon Team on 28/09/2023.
 */
internal interface AdCache {
    /**
     * Caches ads.
     */
    fun cache(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    )

    /**
     * Exposes only, if exists
     */
    fun peek(): AdSource<*>?

    /**
     * Removes from cache if exists and exposes
     */
    fun pop(): AdSource<*>?

    /**
     * Clears the cache.
     */
    fun clear()
}
