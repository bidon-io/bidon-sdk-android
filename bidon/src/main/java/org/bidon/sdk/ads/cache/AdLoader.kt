package org.bidon.sdk.ads.cache

import kotlinx.coroutines.flow.StateFlow
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.impl.AdInstance
import org.bidon.sdk.auction.AdTypeParam

/**
 * Created by Bidon Team on 28/10/2024.
 *
 * Interface for ad loader.
 */
internal interface AdLoader {
    /**
     * Ad instances.
     */
    val adInstances: StateFlow<Set<AdInstance>>

    /**
     * Applies the load parameters.
     */
    fun applyLoadParams(demandAd: DemandAd, adTypeParam: AdTypeParam)

    /**
     * Consumes the result.
     */
    fun consumeAdInstance(adInstance: AdInstance)
}
