package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Bidon Team on 28/10/2024.
 */
internal class AdLoaderImpl(
    override val demandAd: DemandAd,
    private val settings: AdCacheSettingsProvider.AdSettings,
    private val scope: CoroutineScope,
) : AdLoader {

    override val results = MutableStateFlow(emptySet<AdInstance>())

    private val tag = "${TAG}_${demandAd.adType.code}"
    private val isLoading = MutableStateFlow(false)

    private var adTypeParam: AdTypeParam? = null // TODO: 04/11/2024 [glavatskikh] can leak
    private var auction: Auction? = null

    override fun load(adTypeParam: AdTypeParam) {
        logInfo(tag, "AdLoader ad(s): ${results.value.asString()}")
        this.adTypeParam = adTypeParam
        if (results.value.size >= settings.cacheSize) {
            logInfo(tag, "AdLoader has enough ads")
            return
        }
        if (!isLoading.getAndUpdate { true }) {
            logInfo(tag, "AdLoader start: $adTypeParam")
            auction = get()
            auction?.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                onSuccess = { winners, auctionInfo ->
                    // TODO: 31/10/2024 [glavatskikh] In the future we will have several winners,
                    //  but for now we are taking only the first
                    val winner = winners.first()
                    val adInstance = AdInstance(winner.adSource, auctionInfo)
                    results.update { it + adInstance }
                    trackExpired(adInstance)
                    logInfo(tag, "AdLoader auction completed: ${results.value.asString()}")
                    isLoading.value = false
                    scope.launch {
                        load(adTypeParam)
                    }
                },
                onFailure = { _, _ ->
                    logInfo(tag, "AdLoader auction failed: ${results.value.asString()}")
                    isLoading.value = false
                    scope.launch {
                        delay(settings.retryDelayMs)
                        load(adTypeParam)
                    }
                },
            )
        } else {
            logInfo(tag, "AdLoader is already loading")
        }
    }

    override fun consumeAdInstance(adInstance: AdInstance) {
        scope.launch {
            results.update { it - adInstance }
            load(adTypeParam ?: return@launch)
        }
    }

    override fun clear() {
        results.value = emptySet()
        adTypeParam = null
        if (isLoading.getAndUpdate { false }) {
            logInfo(tag, "AdLoader is loading, cancel auction")
            auction?.cancel()
            auction = null
        }
    }

    private fun trackExpired(adInstance: AdInstance) {
        adInstance.adSource.adEvent.onEach { event ->
            if (event is AdEvent.Expired) {
                results.update { it - adInstance }
            }
        }.launchIn(scope)
    }
}
