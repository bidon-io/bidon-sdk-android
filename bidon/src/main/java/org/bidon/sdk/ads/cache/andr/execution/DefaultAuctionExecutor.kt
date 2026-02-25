package org.bidon.sdk.ads.cache.andr.execution

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.cache.andr.ext.asStatisticAdType
import org.bidon.sdk.ads.cache.andr.ext.getAdSources
import org.bidon.sdk.ads.cache.andr.ext.rtb
import org.bidon.sdk.ads.cache.andr.ext.sortedByRankDescending
import org.bidon.sdk.ads.cache.andr.store.AdStore
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.store.RtbResultStore
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandMeasurement
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.LinkedList

internal class DefaultAuctionExecutor(
    private val tag: String,
    private val adaptersSource: AdaptersSource,
    private val requestAdUnit: RequestAdUnitUseCase,
    private val rtbResultStore: AdStore<RtbResultStore.Entry>,
    private val rtbResultsMerger: RtbResultsMerger,
    private val statsRepository: DemandStatistics,
    private val stopCondition: AuctionStopCondition,
) : AuctionExecutor {
    override suspend fun execute(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): List<AuctionResult> {
        val context =
            AuctionContext(
                response.auctionId,
                response.auctionConfigurationId ?: 0L,
                response.auctionConfigurationUid ?: "",
                response.externalWinNotificationsEnabled
            )
        // Pop cached RTB, merge with server RTB, sort
        val cachedRtbResults = rtbResultStore.popAll().map(RtbResultStore.Entry::unwrap)
        val (serverRtbAdUnits, cpmAdUnits) =
            (response.adUnits ?: emptyList())
                .partition { it.bidType == BidType.RTB }
        val (mergedRtbAdUnits, mergedTokens) =
            rtbResultsMerger.merge(cachedRtbResults, serverRtbAdUnits, tokens)

        val allStats = statsRepository.getAllStats(demandAd.adType)
        val sortedAdUnits = (mergedRtbAdUnits + cpmAdUnits).sortedByRankDescending(allStats)

        val executionResult =
            execute(
                context,
                demandAd,
                adTypeParam,
                response.pricefloor,
                response.auctionTimeout,
                sortedAdUnits,
                mergedTokens
            )
        return executionResult.also { notifyWinLoss(it, response.externalWinNotificationsEnabled) }
    }

    private suspend fun execute(
        context: AuctionContext,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
        auctionTimeout: Long,
        adUnits: List<AdUnit>,
        tokens: Map<AdUnit, TokenInfo>
    ): List<AuctionResult> {
        val auctionResults = mutableListOf<AuctionResult>()
        val pendingMeasurements = mutableListOf<DemandMeasurement>()

        val adUnitQueue =
            LinkedList(adUnits)
                .also { logInfo(tag, "AdUnits for request: ${it.size}") }

        val result =
            runCatching {
                withTimeout(auctionTimeout) {
                    var successCount = 0
                    while (adUnitQueue.isNotEmpty()) {
                        val batch =
                            collectBatch(
                                adUnitQueue,
                                demandAd,
                                adTypeParam,
                                priceFloor,
                                tokens,
                                auctionResults
                            )
                        if (batch.isEmpty()) {
                            break
                        }

                        logInfo(tag, "Loading batch of ${batch.size} ad units")
                        val results =
                            batch
                                .map { (adUnit, adSource) ->
                                    async {
                                        loadAdUnit(
                                            context,
                                            adSource,
                                            adUnit,
                                            demandAd,
                                            adTypeParam,
                                            priceFloor
                                        )
                                    }
                                }.awaitAll()

                        for ((auctionResult, measurement) in results) {
                            adUnitQueue.poll()
                            auctionResults.add(auctionResult)
                            pendingMeasurements.add(measurement)
                            if (auctionResult.roundStatus == RoundStatus.Successful) successCount++
                        }

                        if (stopCondition.shouldStop(
                                successCount,
                                results.last().first,
                                adUnitQueue.peek()
                            )
                        ) {
                            logInfo(tag, "Stop condition met after $successCount successful loads")
                            drainRemainingAdUnits(
                                adUnitQueue,
                                tokens,
                                auctionResults,
                                ::getBelowPriceFloorResult
                            )
                            break
                        }
                    }

                    auctionResults
                }
            }

        // Batch record stats
        statsRepository.record(pendingMeasurements)

        // Save unused RTB for caching
        val rtbAdUnits =
            adUnitQueue
                .rtb()
                .fold(mutableSetOf<RtbResultStore.Entry>(), { acc, adUnit ->
                    val tokenInfo = tokens[adUnit]
                    if (tokenInfo != null) {
                        acc.add(RtbResultStore.Entry(context.id, tokenInfo, adUnit))
                    }
                    acc
                })
        rtbResultStore.insert(rtbAdUnits) { it }

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

    private fun collectBatch(
        queue: LinkedList<AdUnit>,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
        tokens: Map<AdUnit, TokenInfo>,
        auctionResults: MutableList<AuctionResult>,
    ): List<Pair<AdUnit, AdSource<AdAuctionParams>>> {
        val batch = mutableListOf<Pair<AdUnit, AdSource<AdAuctionParams>>>()
        val iterator = queue.iterator()
        while (iterator.hasNext() && batch.size < BATCH_SIZE) {
            val adUnit = iterator.next()
            val tokenInfo = tokens[adUnit]

            if (adUnit.pricefloor < priceFloor) {
                iterator.remove()
                logInfo(
                    tag,
                    "Request was skipped since the priceFloor: $priceFloor is less than " +
                        "the next requested adUnit: ${adUnit.pricefloor}"
                )
                auctionResults.add(getBelowPriceFloorResult(adUnit, tokenInfo))
                continue
            }

            val adSource = resolveAdSource(adUnit, demandAd, adTypeParam, tokenInfo)
            if (adSource == null) {
                iterator.remove()
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

            // Loadable — keep in queue until result is processed
            batch.add(adUnit to adSource)
        }
        return batch
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

        val startTime = SystemTimeNow
        val auctionResult = requestAdUnit.invoke(adSource, adUnit, adTypeParam, priceFloor)
        val latencyMs = SystemTimeNow - startTime

        val measurement =
            DemandMeasurement(
                adUnit.demandId,
                demandAd.adType,
                SystemTimeNow,
                auctionResult.adSource.getStats().price,
                auctionResult.roundStatus == RoundStatus.Successful,
                latencyMs
            )
        return auctionResult to measurement
    }

    private fun drainRemainingAdUnits(
        queue: LinkedList<AdUnit>,
        tokens: Map<AdUnit, TokenInfo>,
        into: MutableList<AuctionResult>,
        toResult: (AdUnit, TokenInfo?) -> AuctionResult,
    ) {
        for (adUnit in queue) {
            into.add(toResult(adUnit, tokens[adUnit]))
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

    private fun notifyWinLoss(
        finalResults: List<AuctionResult>,
        externalWinNotificationsEnabled: Boolean,
    ) {
        val winners =
            finalResults
                .filter { it.roundStatus == RoundStatus.Successful }

        winners.forEach {
            val adSource = it.adSource
            // For internal statistics
            adSource.markWin()
            // For AdNetworks - notify winner only if external notifications are disabled
            // Bidding demands should not be notified (server notifies them)
            if (!externalWinNotificationsEnabled) {
                if (it !is AuctionResult.Bidding && adSource is WinLossNotifiable) {
                    adSource.notifyWin()
                    logInfo(
                        tag,
                        "Notified win to adapter: ${adSource.demandId} (external_win_notifications=false)"
                    )
                } else if (it is AuctionResult.Bidding) {
                    logInfo(
                        tag,
                        "Skipped win notification for bidding demand: ${adSource.demandId}"
                    )
                }
            } else {
                logInfo(
                    tag,
                    "Skipped win notification to adapter: ${adSource.demandId} (external_win_notifications=true, will be notified externally)"
                )
            }
        }

        val winnerAdSource = winners.firstOrNull()?.adSource ?: return

        // Notify all losers regardless of external_win_notifications flag
        (finalResults - winners.toSet())
            .filterIsInstance<AuctionResult.Network>()
            .forEach {
                val loserAdSource = it.adSource
                // Bidding demands should not be notified.
                // All losers should be notified immediately regardless of external_win_notifications
                if (loserAdSource is WinLossNotifiable) {
                    logInfo(tag, "Notified loss: ${loserAdSource.demandId}")
                    loserAdSource.notifyLoss(
                        winnerAdSource.demandId.demandId,
                        winnerAdSource.getStats().price
                    )
                }
                logInfo(tag, "Loser notified: ${loserAdSource.demandId}")
            }
    }

    private companion object {
        const val BATCH_SIZE = 2
    }
}