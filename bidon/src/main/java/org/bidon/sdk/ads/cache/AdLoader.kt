package org.bidon.sdk.ads.cache

import kotlinx.coroutines.flow.StateFlow
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.DemandResult

/**
 * Created by Bidon Team on 28/10/2024.
 */
internal interface AdLoader : Cacheable {
    /**
     * Results of the loaded ads.
     */
    val results: StateFlow<List<DemandResult>>

    /**
     * Loads ads.
     */
    fun load(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onReady: () -> Unit
    )

    /**
     * Consumes the result.
     */
    fun consumeResult(result: DemandResult)

    /**
     * Clears the loader.
     */
    fun clear()
}
