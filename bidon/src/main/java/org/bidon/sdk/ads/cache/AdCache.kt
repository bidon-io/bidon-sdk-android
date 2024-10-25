package org.bidon.sdk.ads.cache

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.DemandResult

/**
 * Created by Bidon Team on 28/09/2023.
 */
internal interface AdCache : Cacheable {
    val demandAd: DemandAd

    /**
     * Caches ads.
     */
    fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (DemandResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    )

    /**
     * Exposes only, if exists
     */
    fun peek(): DemandResult?

    /**
     * Removes from cache if exists and exposes
     */
    fun pop(): DemandResult?

    /**
     * Waits for the first loaded, then removes from cache and exposes
     */
    suspend fun poll(): DemandResult

    fun clear()
}