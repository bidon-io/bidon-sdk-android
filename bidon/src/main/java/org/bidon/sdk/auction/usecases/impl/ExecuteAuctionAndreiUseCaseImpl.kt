package org.bidon.sdk.auction.usecases.impl

import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.cache.impl.andr.sortedByRankDescending
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.impl.DemandStatisticsRepository
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandMeasurement
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import java.util.LinkedList

internal class ExecuteAuctionAndreiUseCaseImpl(
    private val adaptersSource: AdaptersSource,
    private val requestAdUnit: RequestAdUnitUseCase,
    private val regulation: Regulation,
    private val statsRepository: DemandStatisticsRepository,
    private val cachedRtbBids: List<AdUnit>,
    private val stopCondition: AuctionStopCondition,
) : ExecuteAuctionUseCase {
    private var adUnitQueue: LinkedList<AdUnit> = LinkedList()

    var unusedRtbAdUnits: List<AdUnit> = emptyList()
        private set

    override suspend fun invoke(
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        auctionTimeout: Long,
        adUnits: List<AdUnit>,
        resultsCollector: ResultsCollector,
        tokens: Map<String, TokenInfo>
    ) {
        val priceFloor = adTypeParam.pricefloor
        val pendingMeasurements = mutableListOf<DemandMeasurement>()

        runCatching {
            val result =
                withTimeoutOrNull(auctionTimeout) {
                    // RTB merge: keep higher-priced bid per demandId
                    val cachedByDemand = cachedRtbBids.associateBy { it.demandId }
                    val mergedAdUnits =
                        buildList {
                            val usedCachedIds = mutableSetOf<String>()
                            adUnits.forEach { serverUnit ->
                                val cached = cachedByDemand[serverUnit.demandId]
                                if (cached != null && serverUnit.bidType == BidType.RTB) {
                                    usedCachedIds.add(serverUnit.demandId)
                                    add(if (cached.pricefloor >= serverUnit.pricefloor) cached else serverUnit)
                                } else {
                                    add(serverUnit)
                                }
                            }
                            cachedRtbBids.filter { it.demandId !in usedCachedIds }.forEach { add(it) }
                        }

                    // UCB1 sort
                    val allStats = statsRepository.getAllStats(demandAd.adType)
                    val sortedAdUnits = mergedAdUnits.sortedByRankDescending(allStats)

                    adUnitQueue = LinkedList(sortedAdUnits)
                    logInfo(TAG, "AdUnits for request: ${adUnitQueue.size}")

                    var successCount = 0
                    while (adUnitQueue.isNotEmpty()) {
                        val adUnit = adUnitQueue.peek()
                        if (adUnit == null) {
                            logInfo(TAG, "All adUnits were requested")
                            break
                        }

                        logInfo(TAG, "Perform load next: \n$adUnit")

                        val tokenInfo = tokens[adUnit.demandId]

                        if (adUnit.pricefloor < priceFloor) {
                            logInfo(
                                TAG,
                                "Request was skipped since the priceFloor: $priceFloor is less than " +
                                    "the next requested adUnit: ${adUnit.pricefloor}"
                            )
                            adUnitQueue.remove()
                            resultsCollector.add(
                                getBelowPriceFloorResult(
                                    adUnit = adUnit,
                                    tokenInfo = tokenInfo
                                )
                            )
                            continue
                        }

                        val adSource =
                            adaptersSource.adapters
                                .find { it.demandId.demandId == adUnit.demandId }
                                ?.also { it.applyRegulation() }
                                ?.getAdSources(demandAd.adType)
                                ?.also { it.setStatisticAdType(adTypeParam.asStatisticAdType()) }

                        if (adUnit.bidType == BidType.RTB) {
                            tokenInfo?.let { adSource?.setTokenInfo(it) }
                        }

                        if (adSource != null) {
                            applyParams(
                                auctionId = auctionId,
                                auctionConfigurationId = auctionConfigurationId,
                                auctionConfigurationUid = auctionConfigurationUid,
                                externalWinNotificationsEnabled = externalWinNotificationsEnabled,
                                adSource = adSource,
                                adTypeParam = adTypeParam,
                                demandAd = demandAd,
                                auctionPricefloor = priceFloor,
                            )

                            val startTime = SystemClock.elapsedRealtime()

                            val auctionResult =
                                requestAdUnit
                                    .invoke(
                                        adSource = adSource,
                                        adTypeParam = adTypeParam,
                                        adUnit = adUnit,
                                        priceFloor = priceFloor,
                                    ).also {
                                        resultsCollector.add(it)
                                    }

                            // Collect measurement
                            val latencyMs = SystemClock.elapsedRealtime() - startTime
                            val filled = auctionResult.roundStatus == RoundStatus.Successful
                            val bidPrice = auctionResult.adSource.getStats().price
                            pendingMeasurements.add(
                                DemandMeasurement(
                                    demandId = adUnit.demandId,
                                    adType = demandAd.adType,
                                    timestamp = System.currentTimeMillis(),
                                    bidPrice = bidPrice,
                                    filled = filled,
                                    latencyMs = latencyMs
                                )
                            )

                            val next = adUnitQueue.poll()
                            if (auctionResult.roundStatus == RoundStatus.Successful) {
                                successCount++
                                if (stopCondition.shouldStop(successCount, auctionResult, next)) {
                                    logInfo(
                                        TAG,
                                        "Stop condition met after $successCount successful loads"
                                    )
                                    adUnitQueue.forEach {
                                        resultsCollector.add(
                                            getBelowPriceFloorResult(
                                                adUnit = it,
                                                tokenInfo = tokens[it.demandId]
                                            )
                                        )
                                    }
                                    break
                                }
                            }
                        } else {
                            adUnitQueue.remove()
                            resultsCollector.add(
                                AuctionResult.AuctionFailed(
                                    adUnit = adUnit,
                                    roundStatus = RoundStatus.UnknownAdapter,
                                    tokenInfo = tokens[adUnit.demandId]
                                )
                            )
                            logInfo(TAG, "AdAdapter ${adUnit.demandId} not found")
                        }
                    }

                    // Batch record stats
                    statsRepository.record(pendingMeasurements)

                    // Save unused RTB for caching
                    unusedRtbAdUnits = adUnitQueue.filter { it.bidType == BidType.RTB }

                    logInfo(TAG, "Auction was finished")

                    resultsCollector
                        .getRoundResults()
                        .let { roundResult ->
                            (roundResult as? RoundResult.Results)
                                ?.let { it.networkResults + (it.biddingResult as? BiddingResult.FilledAd)?.results.orEmpty() }
                                .orEmpty()
                        }
                }
            if (result.isNullOrEmpty()) {
                finishWithStatus(
                    tokens = tokens,
                    resultsCollector = resultsCollector,
                    status = RoundStatus.FillTimeoutReached
                )
                logInfo(TAG, "Auction was finished by timeout: $auctionTimeout")
            }
        }.onFailure {
            finishWithStatus(
                tokens = tokens,
                resultsCollector = resultsCollector,
                status = it.asBidonErrorOrUnspecified().asRoundStatus()
            )
            logError(TAG, "Failed to execute auction", it)
        }
    }

    private fun finishWithStatus(
        tokens: Map<String, TokenInfo>?,
        resultsCollector: ResultsCollector,
        status: RoundStatus
    ) {
        adUnitQueue.forEach {
            resultsCollector.add(
                AuctionResult.AuctionFailed(
                    adUnit = it,
                    roundStatus = status,
                    tokenInfo = tokens?.get(it.demandId)
                )
            )
        }
    }

    private fun getBelowPriceFloorResult(
        adUnit: AdUnit,
        tokenInfo: TokenInfo?
    ): AuctionResult =
        when (adUnit.bidType) {
            BidType.RTB -> {
                AuctionResult.AuctionFailed(
                    adUnit = adUnit,
                    roundStatus = RoundStatus.Lose,
                    tokenInfo = tokenInfo,
                )
            }

            BidType.CPM -> {
                AuctionResult.AuctionFailed(
                    adUnit = adUnit,
                    roundStatus = RoundStatus.BelowPricefloor,
                    tokenInfo = null
                )
            }
        }

    private fun applyParams(
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        adSource: AdSource<AdAuctionParams>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionPricefloor: Double,
    ) {
        adSource.addRoundInfo(
            auctionId = auctionId,
            demandAd = demandAd,
            auctionPricefloor = auctionPricefloor,
        )
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
        adSource.addAuctionConfigurationId(auctionConfigurationId)
        adSource.addAuctionConfigurationUid(auctionConfigurationUid)
        adSource.addExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }

    private fun Adapter.getAdSources(adType: AdType): AdSource<AdAuctionParams>? {
        val adapterDemandId = demandId
        return when (adType) {
            AdType.Interstitial -> {
                (this as? AdProvider.Interstitial<AdAuctionParams>)?.let { adapter ->
                    runCatching {
                        adapter.interstitial().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create interstitial ad source", it)
                    }.getOrNull()
                }
            }

            AdType.Rewarded -> {
                (this as? AdProvider.Rewarded<AdAuctionParams>)?.let { adapter ->
                    runCatching {
                        adapter.rewarded().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create rewarded ad source", it)
                    }.getOrNull()
                }
            }

            AdType.Banner -> {
                (this as? AdProvider.Banner<AdAuctionParams>)?.let { adapter ->
                    runCatching {
                        adapter.banner().apply { addDemandId(adapterDemandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create banner ad source", it)
                    }.getOrNull()
                }
            }
        }
    }

    private fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType =
        when (this) {
            is AdTypeParam.Banner -> {
                StatisticsCollector.AdType.Banner(
                    format =
                        when (bannerFormat) {
                            BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                            BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                            BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                            BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                        }
                )
            }

            is AdTypeParam.Interstitial -> {
                StatisticsCollector.AdType.Interstitial
            }

            is AdTypeParam.Rewarded -> {
                StatisticsCollector.AdType.Rewarded
            }
        }
}

private const val TAG = "ExecuteAuctionAndreiUseCase"
