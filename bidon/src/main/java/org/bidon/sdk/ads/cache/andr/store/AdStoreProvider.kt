package org.bidon.sdk.ads.cache.andr.store

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.andr.AdCacheConfig
import java.util.concurrent.ConcurrentHashMap

internal class AdStoreProvider {
    private val auctionResultStores = ConcurrentHashMap<AdType, AuctionResultStore>()

    private val rtbResultStores = ConcurrentHashMap<AdType, RtbResultStore>()

    @Synchronized
    fun auctionResultStore(
        adCacheConfig: AdCacheConfig,
        adType: AdType
    ): AuctionResultStore =
        auctionResultStores.getOrPut(adType) {
            AuctionResultStore(
                tag = "AndrCache_${adType.code}",
                capacity = adCacheConfig.auctionResultStoreCapacity
            )
        }

    @Synchronized
    fun rtbResultStore(
        adCacheConfig: AdCacheConfig,
        adType: AdType
    ): RtbResultStore = rtbResultStores.getOrPut(adType) { RtbResultStore(tag = "AndrCache_${adType.code}") }
}
