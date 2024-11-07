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
import kotlin.math.min

/**
 * Created by Bidon Team on 28/10/2024.
 *
 * Implementation of [AdLoader].
 */
internal class AdLoaderImpl(
    override val demandAd: DemandAd,
    private val settings: AdCacheSettingsProvider.AdSettings,
    private val scope: CoroutineScope,
) : AdLoader {

    override val results = MutableStateFlow(emptySet<AdInstance>())

    private val tag = "${TAG}_${demandAd.adType.code}"
    private val isLoading = MutableStateFlow(false)
    private val currentRetryDelayMs = MutableStateFlow(settings.retryDelayMs)

    private var adTypeParam: AdTypeParam? = null // TODO: 04/11/2024 [glavatskikh] Potential memory leak
    private var auction: Auction? = null

    override fun load(adTypeParam: AdTypeParam) {
        logInfo(tag, "Loading ad(s): ${results.value.asString()}")
        this.adTypeParam = adTypeParam

        if (results.value.size >= settings.cacheSize) {
            logInfo(tag, "Ad cache size reached. Skipping load.")
            return
        }

        if (!isLoading.getAndUpdate { true }) {
            logInfo(tag, "Starting auction for ad type: $adTypeParam")
            auction = get()
            auction?.start(
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                onSuccess = { winners, auctionInfo ->
                    // For now, we are taking only the first winner; support for multiple winners is planned
                    val winner = winners.first()
                    val adInstance = AdInstance(winner.adSource, auctionInfo)
                    results.update { it + adInstance }
                    trackExpired(adInstance)

                    logInfo(tag, "Auction successful. Current ad cache: ${results.value.asString()}")
                    isLoading.value = false
                    scope.launch {
                        logInfo(tag, "Resetting retry delay to: ${settings.retryDelayMs} ms")
                        currentRetryDelayMs.value = settings.retryDelayMs
                        load(adTypeParam) // Attempt to load more ads if cache isn't full
                    }
                },
                onFailure = { _, _ ->
                    logInfo(tag, "Auction failed. Current ad cache: ${results.value.asString()}")
                    isLoading.value = false
                    scope.launch {
                        val nextRetryDelay = min(currentRetryDelayMs.value * 2, AdCacheSettingsProvider.MAX_RETRY_DELAY_MS)
                        logInfo(tag, "Retrying after delay: $nextRetryDelay ms")
                        delay(currentRetryDelayMs.getAndUpdate { nextRetryDelay }.toLong())
                        load(adTypeParam)
                    }
                }
            )
        } else {
            logInfo(tag, "Load operation is already in progress.")
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
        currentRetryDelayMs.value = settings.retryDelayMs

        if (isLoading.getAndUpdate { false }) {
            logInfo(tag, "Clearing ad cache and cancelling ongoing auction.")
            auction?.cancel()
            auction = null
        }
    }

    private fun trackExpired(adInstance: AdInstance) {
        adInstance.adSource.adEvent.onEach { event ->
            if (event is AdEvent.Expired) {
                results.update { it - adInstance }
                logInfo(tag, "Ad expired and removed from cache: ${adInstance.adSource}")
            }
        }.launchIn(scope)
    }
}

private const val TAG = "AdLoader"
