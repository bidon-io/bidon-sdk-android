package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Bidon Team on 28/10/2024.
 */
internal class AdLoaderImpl(
    private val scope: CoroutineScope,
) : AdLoader {

    override val results = MutableStateFlow(emptyList<DemandResult>())

    private val tag = "${TAG}_TODO"
    private val isLoading = MutableStateFlow(false)
    private var settings: Cacheable.Settings = Cacheable.DefaultSettings
    private var auction: Auction? = null

    override fun withSettings(settings: Cacheable.Settings) {
        this.settings = settings
    }

    override fun load(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onReady: () -> Unit
    ) {
        logInfo(tag, "Cache started: ${results.value.asString()}")
        if (results.value.size >= settings.cacheCapacity) {
            logInfo(tag, "Cache has enough ads")
            return
        }
        if (!isLoading.getAndUpdate { true }) {
            logInfo(tag, "Cache ad: $adTypeParam")
            auction = get()
            auction?.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                onSuccess = { winners, auctionInfo ->
                    scope.launch {
                        results.update { it + winners }
                        winners.intersect(results.value.toSet()).forEach { trackExpired(it) }
                        logInfo(tag, "Auction completed: ${results.value.asString()}")
                        isLoading.value = false
                        onReady.invoke()
                        load(demandAd, adTypeParam, onReady)
                    }
                },
                onFailure = { _, _ ->
                    scope.launch {
                        logInfo(tag, "Auction failed: ${results.value.asString()}")
                        isLoading.value = false
                        load(demandAd, adTypeParam, onReady)
                    }
                },
            )
        } else {
            logInfo(tag, "Ad is already loading")
        }
    }

    override fun consumeResult(result: DemandResult) {
        results.update { it - result }
        // TODO: 28/10/2024 [glavatskikh] need to load new ads
    }

    override fun clear() {
        results.value = emptyList()
        if (isLoading.getAndUpdate { false }) {
            logInfo(tag, "Ad is loading, cancel auction")
            auction?.cancel()
            auction = null
        }
    }

    private fun trackExpired(actionResult: DemandResult) {
        actionResult.adSource.adEvent.onEach { event ->
            if (event is AdEvent.Expired) {
                results.update { it - actionResult }
            }
        }.launchIn(scope)
    }

    private fun List<DemandResult>.asString(): String {
        return "(${this.size}) " + this.joinToString { auctionResult ->
            auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.ecpm}" }
        }
    }
}