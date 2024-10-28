package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Bidon Team on 28/09/2023.
 */
internal class AdCacheImpl(
    private val scope: CoroutineScope,
    private val resolver: AuctionResolver,
) : AdCache {

    private val tag = "${TAG}_TODO"
    private val defaultAuctionKey = "default"
    private val adLoaders = ConcurrentHashMap<String, AdLoader>()
    private var settings: Cacheable.Settings = Cacheable.DefaultSettings
    private val results = MutableStateFlow<List<DemandResult>>(emptyList())

    init {
        @Suppress("OPT_IN_USAGE")
        adLoaders.values.asFlow()
            .flatMapMerge { it.results }
            .onEach { ads ->
                results.value = resolver.sortWinners(ads)
                logInfo(tag, "Cache updated with ${ads.size} ads: ${ads.asString()}")
            }
            .launchIn(scope)
    }

    override fun withSettings(settings: Cacheable.Settings) {
        this.settings = settings
    }

    override fun cache(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (DemandResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(tag, "Cache started for demandAd: ${demandAd.adType}")
        val adLoader = getOrCreateAdLoader(adTypeParam.auctionKey ?: defaultAuctionKey)
        adLoader.withSettings(settings)
        adLoader.load(demandAd, adTypeParam) {
            val demandResult = peek()
            if (demandResult == null) {
                onFailure(null, IllegalStateException("No ads loaded"))
            } else {
                // TODO: 28/10/2024 [glavatskikh] mock data
                onSuccess(
                    demandResult, AuctionInfo(
                        auctionId = "mock",
                        auctionConfigurationId = 0,
                        auctionConfigurationUid = "mock",
                        auctionTimeout = 0,
                        auctionPricefloor = 0.0,
                        noBids = emptyList(),
                        adUnits = emptyList(),
                    )
                )
            }
        }
    }

    override fun peek(): DemandResult? = results.value.firstOrNull()

    override fun pop(): DemandResult? {
        val result = results.getAndUpdate { it.drop(1) }.firstOrNull()
        result?.let { consumeResult(it) }
        return result
    }

    override suspend fun poll(): DemandResult {
        val next = results.first { it.isNotEmpty() }.first()
        results.update { it - next }
        consumeResult(next)
        return next
    }

    override fun clear() {
        // Do nothing
    }

    private fun getOrCreateAdLoader(key: String): AdLoader {
        return adLoaders.getOrPut(key) {
            AdLoaderImpl(scope).apply {
                logInfo(tag, "AdLoader created for key: $key with settings: $settings")
            }
        }
    }

    private fun consumeResult(result: DemandResult) {
        adLoaders.values.forEach { loader ->
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
