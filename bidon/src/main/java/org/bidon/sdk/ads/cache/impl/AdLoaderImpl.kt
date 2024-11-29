package org.bidon.sdk.ads.cache.impl

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.banner.helper.ActivityProvider
import org.bidon.sdk.ads.cache.AdLoader
import org.bidon.sdk.ads.ext.applyActivity
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.cache.AdCacheSettingsProvider
import org.bidon.sdk.cache.AdCacheSettingsProvider.AdSettings
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import kotlin.math.min

/**
 * Created by Bidon Team on 28/10/2024.
 *
 * Implementation of [AdLoader].
 */
internal class AdLoaderImpl(
    adType: AdType,
    activityProvider: ActivityProvider,
    private val adSettings: AdSettings,
) : AdLoader {

    override val adInstances = MutableStateFlow(emptySet<AdInstance>())

    private val tag = "${TAG}_${adType.code}"
    private val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SdkDispatchers.Default)

    private val maxCacheSize get() = adSettings.cacheSize
    private val initialRetryDelayMs get() = adSettings.retryDelayMs

    init {
        activityProvider.resumedActivityFlow
            .onEach { weakActivity ->
                weakActivity.get()?.let { activity ->
                    updateStateWithActivity(activity)
                }
            }
            .launchIn(scope)
    }

    private sealed class State {
        object Idle : State()
        class Awaiting(val demandAd: DemandAd, val adTypeParam: AdTypeParam) : State()
        class Loading(val demandAd: DemandAd, val adTypeParam: AdTypeParam, val retryDelayMs: Long) : State()
    }

    override fun applyLoadParams(demandAd: DemandAd, adTypeParam: AdTypeParam) {
        logInfo(tag, "Applying ad type param: $adTypeParam")
        when (state.value) {
            is State.Idle,
            is State.Awaiting -> {
                if (shouldLoadAd(adTypeParam)) {
                    initiateLoading(demandAd, adTypeParam, initialRetryDelayMs)
                } else {
                    state.value = State.Awaiting(demandAd, adTypeParam)
                    logInfo(tag, "Cache is full, and no ads meet the replacement criteria. Skipping load.")
                }
            }

            is State.Loading -> {
                logInfo(tag, "Load already in progress.")
            }
        }
    }

    override fun consumeAdInstance(adInstance: AdInstance) {
        adInstances.update { it - adInstance }
        logInfo(tag, "Ad instance consumed: ${adInstance.adSource}")
        (state.value as? State.Awaiting)?.let {
            initiateLoading(it.demandAd, it.adTypeParam, initialRetryDelayMs)
        }
    }

    private fun shouldLoadAd(adTypeParam: AdTypeParam): Boolean {
        val instances = adInstances.value
        return instances.size < maxCacheSize ||
            instances.any { it.ecpm < adTypeParam.pricefloor }
    }

    private fun initiateLoading(demandAd: DemandAd, adTypeParam: AdTypeParam, retryDelayMs: Long) {
        logInfo(tag, "Starting auction for ad type: $adTypeParam")
        state.value = State.Loading(demandAd, adTypeParam, retryDelayMs)
        get<Auction>().start(
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            onSuccess = { winners, auctionInfo -> handleAuctionSuccess(winners, auctionInfo) },
            onFailure = { _, _ -> handleAuctionFailure() }
        )
    }

    private fun handleAuctionSuccess(winners: List<DemandResult>, auctionInfo: AuctionInfo) {
        val currentState = state.value
        if (currentState is State.Loading) {
            // For now, we are taking only the first winner; support for multiple winners is planned
            val winner = winners.first()
            val newAdInstance = AdInstance(winner.adSource, auctionInfo)
            updateAdInstances(newAdInstance)

            logInfo(tag, "Auction successful. Current ad cache: ${adInstances.value.asString()}")
            if (shouldLoadAd(currentState.adTypeParam)) {
                initiateLoading(currentState.demandAd, currentState.adTypeParam, initialRetryDelayMs)
            } else {
                state.value = State.Awaiting(currentState.demandAd, currentState.adTypeParam)
                logInfo(tag, "Cache is sufficient. Loading paused.")
            }
        } else {
            logInfo(tag, "Auction success ignored. Current state: $currentState")
        }
    }

    private fun handleAuctionFailure() {
        val currentState = state.value
        if (currentState is State.Loading) {
            val nextRetryDelayMs = calculateRetryDelay(currentState.retryDelayMs)
            logInfo(tag, "Auction failed. Current ad cache: ${adInstances.value.asString()}, Retrying in $nextRetryDelayMs ms.")

            scope.launch {
                delay(nextRetryDelayMs)
                if (state.value is State.Loading) {
                    initiateLoading(currentState.demandAd, currentState.adTypeParam, nextRetryDelayMs)
                } else {
                    logInfo(tag, "Retry aborted. Current state: ${state.value}")
                }
            }
        } else {
            logInfo(tag, "Auction failure ignored. Current state: $currentState")
        }
    }

    private fun updateAdInstances(newAdInstance: AdInstance) {
        adInstances.update { currentCache ->
            if (currentCache.size < maxCacheSize) {
                trackExpired(newAdInstance)
                currentCache + newAdInstance
            } else {
                val lowestValueAd = currentCache.minByOrNull { it.ecpm }
                if (lowestValueAd != null && newAdInstance.ecpm > lowestValueAd.ecpm) {
                    logInfo(tag, "Replacing lower-value ad: ${lowestValueAd.adSource} with new ad: ${newAdInstance.adSource}")
                    trackExpired(newAdInstance)
                    currentCache - lowestValueAd + newAdInstance
                } else {
                    logInfo(tag, "New ad discarded. Lower value than current cache.")
                    currentCache
                }
            }
        }
    }

    private fun trackExpired(adInstance: AdInstance) {
        adInstance.adSource.adEvent.onEach { event ->
            if (event is AdEvent.Expired) {
                logInfo(tag, "Ad expired and removed from cache: ${adInstance.adSource}")
                consumeAdInstance(adInstance)
            }
        }.launchIn(scope)
    }

    private fun calculateRetryDelay(currentDelay: Long): Long {
        return min(currentDelay * 2, AdCacheSettingsProvider.MAX_RETRY_DELAY_MS)
    }

    private fun updateStateWithActivity(activity: Activity) {
        state.update { currentState ->
            when (currentState) {
                is State.Awaiting -> {
                    val updatedAdTypeParam = currentState.adTypeParam.applyActivity(activity)
                    logInfo(tag, "Updated adTypeParam for Awaiting state with new Activity: $activity")
                    State.Awaiting(currentState.demandAd, updatedAdTypeParam)
                }

                is State.Loading -> {
                    val updatedAdTypeParam = currentState.adTypeParam.applyActivity(activity)
                    logInfo(tag, "Updated adTypeParam for Loading state with new Activity: $activity")
                    State.Loading(currentState.demandAd, updatedAdTypeParam, currentState.retryDelayMs)
                }

                else -> currentState
            }
        }
    }
}

private const val TAG = "AdLoader"
