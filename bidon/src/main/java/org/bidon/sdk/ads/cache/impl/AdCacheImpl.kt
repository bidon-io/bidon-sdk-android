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
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Bidon Team on 28/09/2023.
 */
internal class AdCacheImpl(
    private val scope: CoroutineScope,
    private val resolver: AuctionResolver,
) : AdCache {

    private val tag = "${TAG}_TODO"
    private var settings: Cacheable.Settings = Cacheable.DefaultSettings
    private val adLoaders = MutableStateFlow<Map<String, AdLoader>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: StateFlow<List<DemandResult>> = adLoaders
        .flatMapLatest { loaders ->
            combine(loaders.values.map { it.results }) { allResults ->
                resolver.sortWinners(allResults.flatMap { it })
            }
        }
        .onEach { sortedResults ->
            logInfo(tag, "Cache results updated: ${sortedResults.size} ads: ${sortedResults.asString()}")
        }
        .stateIn(
            scope = scope, // TODO: 31/10/2024 [glavatskikh] Do we need use Dispatchers.Default here?
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    override fun withSettings(settings: Cacheable.Settings) {
        this.settings = settings
    }

    override fun cache(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (DemandResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit, // ignore
    ) {
        scope.launch {
            logInfo(tag, "Cache started for demandAd: ${demandAd.adType}")
            val key = adTypeParam.auctionKey ?: "default"
            getOrCreateAdLoader(key, demandAd, settings)?.load(adTypeParam)
            val result = results.first { it.isNotEmpty() }.first()
            onSuccess(
                result, AuctionInfo(
                    auctionId = "auctionId",
                    auctionConfigurationId = 0L,
                    auctionConfigurationUid = "auctionConfigurationUid",
                    auctionTimeout = 0,
                    auctionPricefloor = 0.0,
                    noBids = emptyList(),
                    adUnits = emptyList(),
                )
            )
        }
    }

    override fun peek(): DemandResult? = results.value.firstOrNull()

    override fun pop(): DemandResult? = peek()?.also { consumeResult(it) }

    override fun clear() = Unit // Do nothing

    private fun getOrCreateAdLoader(
        key: String,
        demandAd: DemandAd,
        settings: Cacheable.Settings
    ): AdLoader? {
        return adLoaders.updateAndGet { currentLoaders ->
            if (key in currentLoaders) {
                currentLoaders
            } else {
                currentLoaders + (key to createAdLoader(demandAd, settings))
            }
        }[key]
    }

    private fun createAdLoader(demandAd: DemandAd, settings: Cacheable.Settings): AdLoader {
        return get<AdLoader> { params(demandAd) }.apply {
            withSettings(settings)
            logInfo(tag, "AdLoader created with settings: $settings")
        }
    }

    private fun consumeResult(result: DemandResult) {
        adLoaders.value.values.forEach { loader ->
            if (loader.results.value.contains(result)) {
                loader.consumeResult(result)
            }
        }
    }

    private fun List<DemandResult>.asString(): String {
        return "(${this.size}) " + this.joinToString { auctionResult ->
            auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.ecpm}" }
        }
    }
}
