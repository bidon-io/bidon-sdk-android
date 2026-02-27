package org.bidon.sdk.ads.cache.andr.preparation

import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.logs.logging.impl.logInfo

internal class AdaptersCollector(
    private val tag: String,
    private val adaptersSource: AdaptersSource,
    private val rtbResultsStore: AdStore<RtbResultStore.Entry>,
) {
    fun collectAll(): Collection<Adapter> = adaptersSource.adapters.onEach(Adapter::applyRegulation)

    fun collectBidding(): Collection<Adapter.Bidding> = collectAll().filterIsInstance<Adapter.Bidding>()

    fun collectWithoutCached(): Collection<Adapter.Bidding> {
        val cachedDemandIds =
            rtbResultsStore.peekAll().map(RtbResultStore.Entry::demandId).toSet()
        // Filter and apply regulations to adapters
        return collectBidding()
            .filterNot { it.demandId.demandId in cachedDemandIds }
            .also {
                logInfo(
                    tag,
                    "Cached demand IDs: $cachedDemandIds, bidding after filter: ${it.size}"
                )
            }
    }
}