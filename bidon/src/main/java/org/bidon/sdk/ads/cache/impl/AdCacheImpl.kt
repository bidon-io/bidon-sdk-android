package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
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
    private val adType: AdType,
    private val adSettings: AdSettings,
    private val adCacheSorter: AdCacheSorter,
) : AdCache {

    private val tag = "${TAG}_${adType.code}"
    private val adLoaders = MutableStateFlow<Map<String, AdLoader>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val adInstances: StateFlow<Set<AdInstance>> = adLoaders
        .flatMapLatest { loaders ->
            combine(loaders.values.map { it.adInstances }) { allResults ->
                adCacheSorter.sort(allResults.flatMap { it }).toSet()
            }
        }
        .onEach { sortedResults ->
            logInfo(tag, "Cache updated: ${sortedResults.asString()}")
        }
        .stateIn(
            scope = CoroutineScope(SdkDispatchers.Default),
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    override suspend fun cache(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (AdSource<*>, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        logInfo(tag, "Starting cache for demandAd: ${demandAd.adType}")
        try {
            processAdLoaders(demandAd, adTypeParam)
            val winner = adInstances
                .first { set -> set.any { it.ecpm >= adTypeParam.pricefloor } }
                .first()
            onSuccess(winner.adSource, winner.auctionInfo)
        } catch (e: Exception) {
            logInfo(tag, "Cache failed: ${e.message}")
            onFailure(null, e)
        }
    }

    override fun peek(): AdSource<*>? = adInstances.value.firstOrNull()?.adSource

    override fun pop(): AdSource<*>? {
        val adInstance = adInstances.value.firstOrNull()
        adInstance?.let { consumeAdInstance(it) }
        return adInstance?.adSource
    }

    override fun all(): List<AdSource<*>> = adInstances.value.map { it.adSource }

    private fun processAdLoaders(demandAd: DemandAd, adTypeParam: AdTypeParam) {
        adLoaders.updateAndGet { currentLoaders ->
            val key = adTypeParam.auctionKeyOrDefault
            if (key in currentLoaders) {
                currentLoaders
            } else {
                currentLoaders + (key to createAdLoader(adType, adSettings))
            }
        }.values.forEach { it.applyLoadParams(demandAd, adTypeParam) }
    }

    private fun createAdLoader(adType: AdType, settings: AdSettings): AdLoader {
        return get<AdLoader> { params(adType, settings) }.also {
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
