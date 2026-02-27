package org.bidon.sdk.ads.cache.andr.store

import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.andr.AdCacheStrategy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

internal class AdStoreProvider(
    private val coroutineContext: CoroutineContext,
) {
    private val auctionResultStores = ConcurrentHashMap<AdType, AuctionResultStore>()

    private val rtbResultStores = ConcurrentHashMap<AdType, RtbResultStore>()

    @Synchronized
    fun auctionResultStore(
        adCacheStrategy: AdCacheStrategy,
        adType: AdType
    ): AuctionResultStore =
        auctionResultStores.getOrPut(adType) {
            AuctionResultStore(
                tag = "AndrCache_${adType.code}",
                coroutineContext = coroutineContext,
                capacity = adCacheStrategy.auctionResultStoreCapacity
            )
        }

    @Synchronized
    fun rtbResultStore(
        adCacheStrategy: AdCacheStrategy,
        adType: AdType
    ): RtbResultStore = rtbResultStores.getOrPut(adType) { RtbResultStore(tag = "AndrCache_${adType.code}") }
}
