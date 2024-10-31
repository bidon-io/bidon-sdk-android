package org.bidon.sdk.ads.cache

import kotlinx.coroutines.flow.StateFlow
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.impl.AdInstance
import org.bidon.sdk.auction.AdTypeParam

/**
 * Created by Bidon Team on 28/10/2024.
 */
internal interface AdLoader : Cacheable {

    val demandAd: DemandAd

    /**
     * Results of the loaded ads.
     */
    val results: StateFlow<Set<AdInstance>>

    /**
     * Loads ads.
     */
    fun load(adTypeParam: AdTypeParam)

    /**
     * Consumes the result.
     */
    fun consumeAdInstance(adInstance: AdInstance)

    /**
     * Clears the loader.
     */
    fun clear()
}
