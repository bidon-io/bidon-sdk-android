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
    private val adInstances: StateFlow<Set<AdInstance>> = adLoaders
        .flatMapLatest { loaders ->
            combine(loaders.values.map { it.adInstances }) { allResults ->
                sorter.sort(allResults.flatMap { it }).toSet()
            }
        }
        .onEach { sortedResults ->
            logInfo(tag, "Cache updated: ${sortedResults.asString()}")
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        if (isLoading.compareAndSet(expect = false, update = true)) {
            cacheJob = scope.launch {
                try {
                    logInfo(tag, "Starting cache for demandAd: ${demandAd.adType}")
                    processAdLoaders(adTypeParam)

                    val winner = adInstances
                        .first { set -> set.any { it.ecpm >= adTypeParam.pricefloor } }
                        .first()
                    onSuccess(winner.adSource, winner.auctionInfo)
                } catch (e: Exception) {
                    logInfo(tag, "Cache failed: ${e.message}")
                    onFailure(null, e)
                } finally {
                    isLoading.value = false
                }
            }
        } else {
            logInfo(tag, "Cache is already running.")
        }
    }

    override fun peek(): AdSource<*>? = adInstances.value.firstOrNull()?.adSource

    override fun pop(): AdSource<*>? {
        val adInstance = adInstances.value.firstOrNull()
        adInstance?.let { consumeAdInstance(it) }
        return adInstance?.adSource
    }

    override fun all(): List<AdSource<*>> = adInstances.value.map { it.adSource }

    override fun clear() {
        if (isLoading.compareAndSet(expect = true, update = false)) {
            logInfo(tag, "Cache active job canceled")
            cacheJob?.cancel()
            cacheJob = null
        }
    }

    private fun processAdLoaders(adTypeParam: AdTypeParam) {
        adLoaders.updateAndGet { currentLoaders ->
            val key = adTypeParam.auctionKey ?: DEFAULT_LOADER_KEY
            if (key in currentLoaders) {
                currentLoaders
            } else {
                currentLoaders + (key to createAdLoader(demandAd, settings))
            }
        }.values.forEach { it.applyAdTypeParam(adTypeParam) }
    }

    private fun createAdLoader(demandAd: DemandAd, settings: AdSettings): AdLoader {
        // TODO: 15/11/2024 [glavatskikh] DI needed?
        return AdLoaderImpl(
            demandAd = demandAd,
            adSettings = settings,
            scope = CoroutineScope(SdkDispatchers.Main),
            activityProvider = get(),
        ).also {
            logInfo(tag, "AdLoader created with settings: $settings")
        }
    }

    private fun consumeAdInstance(adInstance: AdInstance) {
        logInfo(tag, "Consuming ad instance: $adInstance")
        adLoaders.value.values.forEach { loader ->
            if (loader.adInstances.value.contains(adInstance)) {
                loader.consumeAdInstance(adInstance)
            }
        }
    }
}

private const val DEFAULT_LOADER_KEY = "default"
