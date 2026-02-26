package org.bidon.sdk.ads.cache.andr.preparation

import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore

internal class AdaptersInfoCollector(
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
) {
    fun collect(adapters: Collection<Adapter>): Map<String, AdapterInfo> {
        val cachedDemandIds =
            rtbResultsStore.peekAll().map(RtbResultStore.Entry::demandId).toSet()
        // Filter and apply regulations to adapters
        val biddingAdapters =
            adapters
                .filterIsInstance<Adapter.Bidding>()
                .filterNot { it.demandId.demandId in cachedDemandIds }
                .onEach(Adapter::applyRegulation)
        return biddingAdapters.associate { it.demandId.demandId to it.adapterInfo }
    }
}