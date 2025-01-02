package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.ext.applyPricefloor
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Bidon Team on 02/01/2025.
 *
 * Implementation of [AdCache].
 */
internal class OldAdCacheImpl(
    adType: AdType,
    private val scope: CoroutineScope,
    private val resolver: AuctionResolver,
) : AdCache {

    private val tag = "${TAG}_${adType.code}"

    private val isLoading = MutableStateFlow(false)
    private val results = MutableStateFlow(emptyList<DemandResult>())
    private var auction: Auction? = null

    override suspend fun cache(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        load(demandAd, adTypeParam, onSuccess, onFailure)
    }

    override fun peek(): AdSource<*>? = results.value.firstOrNull()?.adSource

    override fun pop(): AdSource<*>? {
        return results.getAndUpdate {
            it.drop(1)
        }.firstOrNull()?.adSource
    }

    override fun all(): List<AdSource<*>> {
        return results.value.map { it.adSource }
    }

    override fun clear() {
        results.value = emptyList()
        if (isLoading.getAndUpdate { false }) {
            logInfo(tag, "Ad is loading, cancel auction")
            auction?.cancel()
            auction = null
        }
    }

    private fun load(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        logInfo(tag, "Cache started: ${results.value.asString()}")
        if (results.value.size >= MIN_CACHE_SIZE) {
            logInfo(tag, "Cache has enough ads")
            return
        }
        if (!isLoading.getAndUpdate { true }) {
            logInfo(tag, "Cache ad: $adTypeParam")
            auction = get()
            auction?.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam.applyPricefloor(
                    pricefloor = maxOf(
                        adTypeParam.pricefloor,
                        results.value.firstOrNull()?.adSource?.getStats()?.ecpm ?: 0.0
                    )
                ),
                onSuccess = { winners, auctionInfo ->
                    scope.launch {
                        results.update {
                            resolver.sortWinners(winners).take(CACHE_CAPACITY)
                        }
                        winners.intersect(results.value.toSet())
                            .forEach { it: DemandResult -> trackExpired(it) }
                        logInfo(tag, "Auction completed: ${results.value.asString()}")
                        isLoading.value = false
                        results.value.firstOrNull()
                            ?.let { onSuccess.invoke(it.adSource, auctionInfo) }
                    }
                },
                onFailure = { auctionInfo, cause ->
                    logInfo(tag, "Auction failed: ${results.value.asString()}")
                    onFailure.invoke(auctionInfo, cause)
                    isLoading.value = false
                },
            )
        } else {
            logInfo(tag, "Ad is already loading")
        }
    }

    private fun trackExpired(demandResult: DemandResult) {
        demandResult.adSource.adEvent.onEach { event ->
            if (event is AdEvent.Expired) {
                results.update { it - demandResult }
            }
        }.launchIn(scope)
    }

    private fun List<DemandResult>.asString(): String {
        return "(${this.size}) " + this.joinToString { auctionResult ->
            auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.ecpm}" }
        }
    }

    companion object {
        private const val MIN_CACHE_SIZE = 1
        private const val CACHE_CAPACITY = 1
    }
}