package org.bidon.sdk.ads.cache.impl

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.impl.AuctionImpl
import org.bidon.sdk.auction.impl.PriceFloorStrategy
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.impl.ExecuteAuctionAndreiUseCaseImpl
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.impl.DemandStatisticsRepository
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheAndreiImpl(
    override val demandAd: DemandAd,
    private val scope: CoroutineScope,
    private val resolver: AuctionResolver,
) : AdCache {
    private val tag = "${TAG}_${demandAd.adType.code}"

    private val isLoading = AtomicBoolean(false)

    private val auctionResults = MutableStateFlow(listOf<AuctionResult>())

    private var settings: Cacheable.Settings = Cacheable.DefaultSettings

    private var _auction: Auction? = null

    private val _rtbAdUnits = MutableStateFlow(listOf<CachedAdUnit>())

    private val _cachedPriceFloor = MutableStateFlow(0.0)

    override fun withSettings(settings: Cacheable.Settings) {
        this.settings = settings
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        load(adTypeParam, onSuccess, onFailure)
    }

    override fun peek(): AuctionResult? = auctionResults.value.firstOrNull()

    override fun pop(): AuctionResult? =
        auctionResults
            .getAndUpdate { it.drop(1) }
            .firstOrNull()

    override suspend fun poll(): AuctionResult =
        auctionResults
            .getAndUpdate { it.drop(1) }
            .first()

    override fun clear() {
        _rtbAdUnits.update { emptyList() }

        auctionResults
            .getAndUpdate { emptyList() }
            .destroy()

        if (!isLoading.getAndSet(false)) {
            return
        }

        logInfo(tag, "Ad is loading, cancel auction")

        _auction?.cancel()
        _auction = null
    }

    private fun load(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(tag, "Cache started: ${auctionResults.value.asString()}")

        if (isLoading.getAndSet(true)) {
            logInfo(tag, "Ad is already loading")
            return
        }

        logInfo(tag, "Cache ad: $adTypeParam")

        val now = SystemClock.elapsedRealtime()
        val rtbAdUnits =
            _rtbAdUnits
                .updateAndGet { it.filter { it.expireAt > now } }
                .map { it.adUnit }

        val executeAuction =
            ExecuteAuctionAndreiUseCaseImpl(
                adaptersSource = get(),
                requestAdUnit = get(),
                regulation = get(),
                statsRepository = get(),
                cachedRtbBids = rtbAdUnits,
                stopCondition =
                    object : AuctionStopCondition {
                        override fun shouldStop(
                            successCount: Int,
                            lastResult: AuctionResult,
                            next: AdUnit?
                        ): Boolean = successCount >= 1
                    },
            )
        _auction =
            AuctionImpl(
                adaptersSource = get(),
                getTokens = get(),
                getAuctionRequest = get(),
                executeAuction = executeAuction,
                auctionStat = get(),
                biddingConfig = get(),
            )
        val demandStatsRepository = get<DemandStatisticsRepository>()

        val adType = demandAd.adType
        val priceFloor =
            _cachedPriceFloor
                .updateAndGet {
                    val originalFloor =
                        if (it > 0) {
                            it
                        } else {
                            demandStatsRepository
                                .getPriceFloor(adType)
                                .takeIf { floor -> floor > 0 }
                                ?: adTypeParam.pricefloor
                        }
                    get<PriceFloorStrategy>().calculate(
                        adType = adType,
                        originalFloor = originalFloor,
                        recentFillRate =
                            demandStatsRepository.getRecentFillRate(
                                adType,
                                windowMinutes = 10
                            ),
                        bidDistribution =
                            demandStatsRepository.getBidDistribution(
                                adType,
                                windowDays = 3
                            ),
                    )
                }.also { demandStatsRepository.savePriceFloor(adType, it) }

        _auction?.start(
            demandAd = demandAd,
            adTypeParam = adTypeParam.copy(priceFloor = priceFloor),
            onSuccess = { winners, auctionInfo ->
                scope.launch {
                    updateRtbAdUnits(executeAuction.unusedRtbAdUnits)
                    updateCache(winners)
                        .also {
                            logInfo(
                                tag,
                                "Auction completed: ${auctionResults.value.asString()}"
                            )
                        }.also { isLoading.set(false) }
                        ?.let { onSuccess.invoke(it, auctionInfo) }
                }
            },
            onFailure = { auctionInfo, cause ->
                scope.launch {
                    logInfo(tag, "Auction failed: ${auctionResults.value.asString()}")
                    isLoading.set(false)
                    onFailure.invoke(auctionInfo, cause)
                }
            },
        )
    }

    private fun List<AuctionResult>.destroy() {
        forEach { it.adSource.destroy() }
    }

    private suspend fun updateCache(winners: List<AuctionResult>): AuctionResult? {
        val sortedWinners = resolver.sortWinners(winners)
        val adsToCache = sortedWinners.take(settings.cacheCapacity)
        val adsToDiscard = sortedWinners - adsToCache.toSet()

        // Destroy ads that don't fit in cache
        adsToDiscard.destroy()

        // Destroy previously cached ads and update cache
        auctionResults
            .getAndUpdate { adsToCache }
            .destroy()

        // Subscribe to expiration events
        adsToCache.forEach { auctionResult ->
            auctionResult.adSource.adEvent
                .onEach { event ->
                    if (event is AdEvent.Expired) {
                        auctionResults.update { it - auctionResult }
                    }
                }.launchIn(scope)
        }

        return adsToCache.firstOrNull()
    }

    private fun updateRtbAdUnits(adUnits: List<AdUnit>) {
        val now = SystemClock.elapsedRealtime()
        val newRtb = adUnits.filter { it.bidType == BidType.RTB }
        val newByDemand = newRtb.associateBy { it.demandId }
        _rtbAdUnits.update { oldRtbAdUnits ->
            val updatedOld =
                oldRtbAdUnits.map { cached ->
                    val newer = newByDemand[cached.adUnit.demandId]
                    if (newer != null && newer.pricefloor > cached.adUnit.pricefloor) {
                        CachedAdUnit(newer, now + RTB_CACHE_TTL_MS)
                    } else {
                        cached
                    }
                }
            val existingIds = oldRtbAdUnits.map { it.adUnit.demandId }.toSet()
            val brandNew =
                newRtb
                    .filter { it.demandId !in existingIds }
                    .map { CachedAdUnit(it, now + RTB_CACHE_TTL_MS) }
            updatedOld + brandNew
        }
    }

    private fun AdTypeParam.copy(priceFloor: Double): AdTypeParam {
        val auctionKey = "1O16GQT380000"
        return when (val param = this) {
            is AdTypeParam.Banner -> {
                AdTypeParam.Banner(
                    activity = param.activity,
                    pricefloor = priceFloor,
                    auctionKey = auctionKey,
                    bannerFormat = param.bannerFormat,
                    containerWidth = param.containerWidth,
                )
            }

            is AdTypeParam.Interstitial -> {
                AdTypeParam.Interstitial(
                    activity = param.activity,
                    pricefloor = priceFloor,
                    auctionKey = auctionKey,
                )
            }

            is AdTypeParam.Rewarded -> {
                AdTypeParam.Rewarded(
                    activity = param.activity,
                    pricefloor = priceFloor,
                    auctionKey = auctionKey,
                )
            }
        }
    }

    private fun List<AuctionResult>.asString(): String =
        "(${this.size}) " +
            joinToString { auctionResult ->
                auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.price}" }
            }

    private data class CachedAdUnit(
        val adUnit: AdUnit,
        val expireAt: Long,
    )

    companion object {
        private const val RTB_CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
