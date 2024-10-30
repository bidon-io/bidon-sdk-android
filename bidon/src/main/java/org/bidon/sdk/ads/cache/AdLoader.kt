package org.bidon.sdk.ads.cache

import kotlinx.coroutines.flow.StateFlow
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.DemandResult

/**
 * Created by Bidon Team on 28/10/2024.
 */
internal interface AdLoader : Cacheable {

    val demandAd: DemandAd

    /**
     * Results of the loaded ads.
     */
    val results: StateFlow<Set<DemandResult>>

    /**
     * Loads ads.
     */
    fun load(adTypeParam: AdTypeParam)

    /**
     * Consumes the result.
     */
    fun consumeResult(result: DemandResult)

    /**
     * Clears the loader.
     */
    fun clear()
}
