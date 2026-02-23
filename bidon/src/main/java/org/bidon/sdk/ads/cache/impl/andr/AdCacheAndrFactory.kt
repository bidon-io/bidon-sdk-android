package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.impl.AdCacheAndreiImpl
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.utils.SdkDispatchers

internal object AdCacheAndrFactory {
    fun create(
        demandAd: DemandAd,
        resolver: AuctionResolver,
    ): AdCache {
        // TODO : Add AdType
        return AdCacheAndreiImpl(
            demandAd = demandAd,
            resolver = resolver,
            executionDispatcher = SdkDispatchers.IO,
            callbackDispatcher = SdkDispatchers.Main,
        )
    }
}