package org.bidon.sdk.ads.cache.andr.store

import org.bidon.sdk.ads.AdType
import java.util.concurrent.ConcurrentHashMap

internal class AdStoreProvider {
    private val auctionResultStores = ConcurrentHashMap<AdType, AuctionResultStore>()

    private val rtbResultStores = ConcurrentHashMap<AdType, RtbResultStore>()

    @Synchronized
    fun auctionResultStore(adType: AdType): AuctionResultStore = auctionResultStores.getOrPut(adType) { AuctionResultStore() }

    @Synchronized
    fun rtbResultStore(adType: AdType): RtbResultStore = rtbResultStores.getOrPut(adType) { RtbResultStore() }
}
