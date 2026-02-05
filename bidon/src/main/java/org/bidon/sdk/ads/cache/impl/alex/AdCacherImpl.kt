package org.bidon.sdk.ads.cache.impl.alex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult

internal class AdCacherImpl(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : AdCache {
    private val fillBucket: MutableStateFlow<List<CacheItem.FillEntry>> = MutableStateFlow(listOf())
    private val bidBucket: MutableStateFlow<List<CacheItem.BidEntry>> = MutableStateFlow(listOf())
    private val cacheItem: StateFlow<List<CacheItem>> =
        combine(fillBucket, bidBucket) { fills, bids ->
            buildList {
                addAll(fills)
                addAll(bids)
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    override fun cache(
        adTypeParam: org.bidon.sdk.auction.AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        TODO("Not yet implemented")
    }

    override fun peek(): AuctionResult? {
        return cacheItem.value.firstOrNull()?.auctionResult
    }

    override fun pop(): AuctionResult? {
        return cacheItem.value.firstOrNull()
            ?.also { item ->
                fillBucket.update {
                    it.filter { entry -> entry != item }
                }
                bidBucket.update {
                    it.filter { entry -> entry != item }
                }
            }?.auctionResult
    }

    override suspend fun poll(): AuctionResult {
        return cacheItem.first { it.isNotEmpty() }.firstOrNull()?.auctionResult ?: poll()
    }

    override fun clear() {
        fillBucket.value = emptyList()
        bidBucket.value = emptyList()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // No-op
    }
}