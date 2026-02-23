package org.bidon.sdk.ads.cache.impl.andr

import android.os.SystemClock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.impl.DemandStatisticsRepository
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandMeasurement
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import java.util.LinkedList

internal class AuctionExecutor(
    private val tag: String,
    private val adaptersSource: AdaptersSource,
    private val requestAdUnit: RequestAdUnitUseCase,
    private val statsRepository: DemandStatisticsRepository,
    private val adUnitBuffer: AdStore<AdUnit, *>,
    private val adUnitListMerger: AdUnitListMerger,
    private val stopCondition: AuctionStopCondition,
) {
    suspend fun execute(
        context: AuctionContext,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
        auctionTimeout: Long,
        adUnits: List<AdUnit>,
        tokens: Map<String, TokenInfo>
    ): List<AuctionResult> {
        val auctionResults = mutableListOf<AuctionResult>()
        val pendingMeasurements = mutableListOf<DemandMeasurement>()

        val adUnitQueue =
            LinkedList(getSortedAdUnits(demandAd.adType, adUnits))
                .also { logInfo(tag, "AdUnits for request: ${it.size}") }

        val result =
            runCatching {
                withTimeout(auctionTimeout) {
                    var successCount = 0
                    while (adUnitQueue.isNotEmpty()) {
                        val adUnit = adUnitQueue.peek()
                        if (adUnit == null) {
                            logInfo(tag, "All adUnits were requested")
                            break
                        }

                        logInfo(tag, "Perform load next: \n$adUnit")

                        val tokenInfo = tokens[adUnit.demandId]

                        if (adUnit.pricefloor < priceFloor) {
                            logInfo(
                                tag,
                                "Request was skipped since the priceFloor: $priceFloor is less than " + "the next requested adUnit: ${adUnit.pricefloor}"
                            )
                            adUnitQueue.remove()
                            auctionResults.add(getBelowPriceFloorResult(adUnit, tokenInfo))
                            continue
                        }

                        val adSource = resolveAdSource(adUnit, demandAd, adTypeParam, tokenInfo)
                        if (adSource == null) {
                            adUnitQueue.remove()
                            auctionResults.add(
                                AuctionResult.AuctionFailed(
                                    adUnit,
                                    tokenInfo,
                                    RoundStatus.UnknownAdapter
                                )
                            )
                            logInfo(tag, "AdAdapter ${adUnit.demandId} not found")
                            continue
                        }

                        val (auctionResult, measurement) =
                            loadAdUnit(
                                context,
                                adSource,
                                adUnit,
                                demandAd,
                                adTypeParam,
                                priceFloor
                            )
                        auctionResults.add(auctionResult)
                        pendingMeasurements.add(measurement)

                        val next = adUnitQueue.poll()
                        if (auctionResult.roundStatus == RoundStatus.Successful) {
                            successCount++
                            if (stopCondition.shouldStop(successCount, auctionResult, next)) {
                                logInfo(
                                    tag,
                                    "Stop condition met after $successCount successful loads"
                                )
                                drainRemainingAdUnits(
                                    adUnitQueue,
                                    tokens,
                                    auctionResults,
                                    ::getBelowPriceFloorResult
                                )
                                break
                            }
                        }
                    }

                    auctionResults
                }
            }

        // Batch record stats
        statsRepository.record(pendingMeasurements)

        // Save unused RTB for caching
        adUnitBuffer.insert(*(adUnitQueue.filter { it.bidType == BidType.RTB }).toTypedArray())

        logInfo(tag, "Auction was finished")

        return result.getOrElse {
            val status =
                if (it is TimeoutCancellationException) {
                    RoundStatus.FillTimeoutReached
                } else {
                    it.asBidonErrorOrUnspecified().asRoundStatus()
                }
            drainRemainingAdUnits(adUnitQueue, tokens, auctionResults) { adUnit, token ->
                AuctionResult.AuctionFailed(adUnit, token, status)
            }
            auctionResults
        }
    }

    private fun getSortedAdUnits(
        adType: AdType,
        adUnits: List<AdUnit>,
    ): List<AdUnit> {
        // UCB1 sort
        val allStats = statsRepository.getAllStats(adType)
        return adUnitListMerger
            .merge(adUnitBuffer.popAll().toList(), adUnits)
            .sortedByRankDescending(allStats)
    }

    private fun resolveAdSource(
        adUnit: AdUnit,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        tokenInfo: TokenInfo?,
    ): AdSource<AdAuctionParams>? {
        val adSource =
            adaptersSource.adapters
                .find { it.demandId.demandId == adUnit.demandId }
                ?.also(Adapter::applyRegulation)
                ?.getAdSources(demandAd.adType, tag)
                ?.also { it.setStatisticAdType(adTypeParam.asStatisticAdType()) }

        if (adUnit.bidType == BidType.RTB) {
            tokenInfo?.let { adSource?.setTokenInfo(it) }
        }
        return adSource
    }

    private suspend fun loadAdUnit(
        context: AuctionContext,
        adSource: AdSource<AdAuctionParams>,
        adUnit: AdUnit,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
    ): Pair<AuctionResult, DemandMeasurement> {
        applyParams(context, adSource, adTypeParam, demandAd, priceFloor)

        val startTime = SystemClock.elapsedRealtime()
        val auctionResult = requestAdUnit.invoke(adSource, adUnit, adTypeParam, priceFloor)
        val latencyMs = SystemClock.elapsedRealtime() - startTime

        val measurement =
            DemandMeasurement(
                adUnit.demandId,
                demandAd.adType,
                System.currentTimeMillis(),
                auctionResult.adSource.getStats().price,
                auctionResult.roundStatus == RoundStatus.Successful,
                latencyMs
            )
        return auctionResult to measurement
    }

    private fun drainRemainingAdUnits(
        queue: LinkedList<AdUnit>,
        tokens: Map<String, TokenInfo>,
        into: MutableList<AuctionResult>,
        toResult: (AdUnit, TokenInfo?) -> AuctionResult,
    ) {
        for (adUnit in queue) {
            into.add(toResult(adUnit, tokens[adUnit.demandId]))
        }
    }

    private fun getBelowPriceFloorResult(
        adUnit: AdUnit,
        tokenInfo: TokenInfo?
    ): AuctionResult =
        when (adUnit.bidType) {
            BidType.RTB -> AuctionResult.AuctionFailed(adUnit, tokenInfo, RoundStatus.Lose)
            BidType.CPM -> AuctionResult.AuctionFailed(adUnit, null, RoundStatus.BelowPricefloor)
        }

    private fun applyParams(
        context: AuctionContext,
        adSource: AdSource<AdAuctionParams>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        auctionPriceFloor: Double,
    ) = with(adSource) {
        addRoundInfo(context.id, demandAd, auctionPriceFloor)
        setStatisticAdType(adTypeParam.asStatisticAdType())
        addAuctionConfigurationId(context.configurationId)
        addAuctionConfigurationUid(context.configurationUid)
        addExternalWinNotificationsEnabled(context.externalWinNotificationsEnabled)
    }
}