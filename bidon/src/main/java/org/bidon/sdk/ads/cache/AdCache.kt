package org.bidon.sdk.ads.cache

import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam

/**
 * Created by Bidon Team on 28/09/2023.
 *
 * Interface for caching ads.
 */
internal interface AdCache {
    /**
     * Caches ads.
     */
    suspend fun cache(
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
     * Returns all ads in the cache.
     */
    fun all(): List<AdSource<*>>
}
