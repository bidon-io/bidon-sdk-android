package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheSettingsResolver
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Bidon Team on 28/09/2023.
 */
internal class AdCacheImpl(
    adType: AdType,
    private val scope: CoroutineScope,
    private val resolver: AdCacheSettingsResolver,
) : AdCache {

    private val tag = "${TAG}_${adType.code}"
    private val sorter = resolver.resolveSorter(adType)
    private val isLoading = MutableStateFlow(false)
    private val adLoaders = MutableStateFlow<Map<Any, AdLoader>>(emptyMap())

    private var cacheJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: StateFlow<Set<AdInstance>> = adLoaders
        .flatMapLatest { loaders ->
            combine(loaders.values.map { it.results }) { allResults ->
                sorter.invoke(allResults.flatMap { it }).toSet()
            }
        }
        .onEach { sortedResults ->
            logInfo(tag, "Cache results updated: ${sortedResults.size} ads: ${sortedResults.asString()}")
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    override fun cache(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit, // ignore
    ) {
        if (!isLoading.getAndUpdate { true }) {
            cacheJob = scope.launch {
                logInfo(tag, "Cache started for demandAd: ${demandAd.adType}")
                getOrCreateAdLoader(demandAd, adTypeParam)?.load(adTypeParam)
                val (winners, auctionInfo) = results.first { it.isNotEmpty() }.first()
                isLoading.value = false
                onSuccess(winners, auctionInfo)
            }
        } else {
            logInfo(tag, "Cache is already started")
        }
    }

    override fun peek(): AdSource<*>? = results.value.firstOrNull()?.adSource

    override fun pop(): AdSource<*>? {
        val adInstance = results.value.firstOrNull()
        adInstance?.let { consumeAdInstance(it) }
        return adInstance?.adSource
    }

    override fun clear() {
        // we don't need to clear adLoaders and results
        if (isLoading.getAndUpdate { false }) {
            logInfo(tag, "Cache canceled")
            cacheJob?.cancel()
            cacheJob = null
        }
    }

    private fun getOrCreateAdLoader(demandAd: DemandAd, adTypeParam: AdTypeParam): AdLoader? {
        val key = resolver.resolveAuctionKey(adTypeParam)
        return adLoaders.updateAndGet { currentLoaders ->
            if (key in currentLoaders) {
                currentLoaders
            } else {
                val settings = resolver.resolveSettings(adTypeParam)
                currentLoaders + (key to createAdLoader(demandAd, settings))
            }
        }[key]
    }

    private fun createAdLoader(
        demandAd: DemandAd,
        settings: AdCacheSettingsProvider.AdSettings
    ): AdLoader {
        return get<AdLoader> { params(demandAd, settings) }.apply {
            logInfo(tag, "AdLoader created with settings: $settings")
        }
    }

    private fun consumeAdInstance(adInstance: AdInstance) {
        logInfo(tag, "Cache consume: $adInstance")
        adLoaders.value.values.forEach { loader ->
            if (loader.results.value.contains(adInstance)) {
                loader.consumeAdInstance(adInstance)
            }
        }
    }
}
