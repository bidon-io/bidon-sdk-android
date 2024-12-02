package org.bidon.sdk.ads.cache.impl

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    adSettings: AdSettings,
    activityProvider: ActivityProvider,
) : AdLoader {

    override val adInstances = MutableStateFlow(emptySet<AdInstance>())

    private val tag = "${TAG}_${adType.code}"
    private val state = MutableStateFlow<State>(State.Idle)
    private val scope = CoroutineScope(SdkDispatchers.Default)

    private val maxCacheSize = adSettings.cacheSize
    private val initialRetryDelayMs = adSettings.retryDelayMs

    private var retryJob: Job? = null

    init {
        observeActivityUpdates(activityProvider)
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
                    startAuction(demandAd, adTypeParam, initialRetryDelayMs)
                } else {
                    logInfo(tag, "Cache is full, and no ads meet the replacement criteria. Skipping load.")
                    state.value = State.Awaiting(demandAd, adTypeParam)
                }
            }

            is State.Loading -> {
                logInfo(tag, "Restarting auction with new parameters.")
                cancelRetry()
                startAuction(demandAd, adTypeParam, initialRetryDelayMs)
            }
        }
    }

    override fun consumeAdInstance(adInstance: AdInstance) {
        adInstances.update { it - adInstance }
        logInfo(tag, "Ad consumed: ${adInstance.adSource}")
        when (val currentState = state.value) {
            is State.Idle -> {
                logInfo(tag, "No action required after consumption. Current state: $currentState")
            }

            is State.Awaiting -> {
                startAuction(currentState.demandAd, currentState.adTypeParam, initialRetryDelayMs)
            }

            is State.Loading -> {
                logInfo(tag, "Restarting auction after consumption.")
                cancelRetry()
                startAuction(currentState.demandAd, currentState.adTypeParam, initialRetryDelayMs)
            }
        }
    }

    private fun startAuction(demandAd: DemandAd, adTypeParam: AdTypeParam, retryDelayMs: Long) {
        logInfo(tag, "Starting auction for: $adTypeParam")
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
                startAuction(currentState.demandAd, currentState.adTypeParam, initialRetryDelayMs)
            } else {
                logInfo(tag, "Cache is sufficient. Loading paused.")
                state.value = State.Awaiting(currentState.demandAd, currentState.adTypeParam)
            }
        } else {
            logInfo(tag, "Ignored auction success. Current state: $currentState")
        }
    }

    private fun handleAuctionFailure() {
        val currentState = state.value
        if (currentState is State.Loading) {
            logInfo(tag, "Auction failed. Current ad cache: ${adInstances.value.asString()}")
            retryJob = scope.launch {
                val nextRetryDelay = calculateRetryDelay(currentState.retryDelayMs)
                logInfo(tag, "Retrying auction in $nextRetryDelay ms.")
                delay(nextRetryDelay)
                if (state.value is State.Loading) {
                    startAuction(currentState.demandAd, currentState.adTypeParam, nextRetryDelay)
                } else {
                    logInfo(tag, "Retry aborted. Current state changed: ${state.value}")
                }
            }
        } else {
            logInfo(tag, "Ignored auction failure. Current state: $currentState")
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

    private fun shouldLoadAd(adTypeParam: AdTypeParam): Boolean {
        val instances = adInstances.value
        return instances.size < maxCacheSize ||
            instances.any { it.ecpm < adTypeParam.pricefloor }
    }

    private fun calculateRetryDelay(currentDelay: Long): Long {
        return min(currentDelay * 2, AdCacheSettingsProvider.MAX_RETRY_DELAY_MS)
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun observeActivityUpdates(activityProvider: ActivityProvider) {
        activityProvider.resumedActivityFlow
            .onEach { weakActivity ->
                weakActivity.get()?.let { activity ->
                    updateStateWithActivity(activity)
                }
            }
            .launchIn(scope)
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
