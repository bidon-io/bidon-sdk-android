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
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdCacheSorter
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdSettings
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Bidon Team on 28/09/2023.
 *
 * Implementation of [AdCache].
 */
internal class AdCacheImpl(
    override val demandAd: DemandAd,
    private val settings: AdSettings,
    private val sorter: AdCacheSorter,
    private val scope: CoroutineScope,
) : AdCache {

    private val tag = "${TAG}_${demandAd.adType.code}"
    private val isLoading = MutableStateFlow(false)
    private val adLoaders = MutableStateFlow<Map<String, AdLoader>>(emptyMap())

    private var cacheJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: StateFlow<Set<AdInstance>> = adLoaders
        .flatMapLatest { loaders ->
            combine(loaders.values.map { it.results }) { allResults ->
                sorter.sort(allResults.flatMap { it }).toSet()
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
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit, // ignore
    ) {
        if (!isLoading.getAndUpdate { true }) {
            cacheJob = scope.launch {
                logInfo(tag, "Cache started for demandAd: ${demandAd.adType}")
                val adLoader = getOrCreateAdLoader(adTypeParam) {
                    createAdLoader(demandAd, settings)
                }
                adLoader.load(adTypeParam)
                val (winner, auctionInfo) = results.first { it.isNotEmpty() }.first()
                isLoading.value = false
                onSuccess(winner, auctionInfo)
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

    private fun getOrCreateAdLoader(
        adTypeParam: AdTypeParam,
        createAdLoader: () -> AdLoader
    ): AdLoader {
        val key = adTypeParam.auctionKey ?: DEFAULT_LOADER_KEY
        return adLoaders.updateAndGet { currentLoaders ->
            if (key in currentLoaders) {
                currentLoaders
            } else {
                currentLoaders + (key to createAdLoader())
            }
        }[key] ?: error("AdLoader should exist after updateAndGet")
    }

    private fun createAdLoader(demandAd: DemandAd, settings: AdSettings): AdLoader {
        // TODO: 15/11/2024 [glavatskikh] DI needed?
        return AdLoaderImpl(
            demandAd = demandAd,
            adSettings = settings,
            scope = CoroutineScope(SdkDispatchers.Main),
            activityProvider = get()
        ).also {
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

private const val DEFAULT_LOADER_KEY = "default"
