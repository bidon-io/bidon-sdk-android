package org.bidon.sdk.ads.cache.andr.execution

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.cache.andr.analytics.DemandMeasurement
import org.bidon.sdk.ads.cache.andr.analytics.DemandStatistics
import org.bidon.sdk.ads.cache.andr.ext.asStatisticAdType
import org.bidon.sdk.ads.cache.andr.ext.rtb
import org.bidon.sdk.ads.cache.andr.preparation.AdaptersCollector
import org.bidon.sdk.ads.cache.andr.store.AdStore
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
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.LinkedList

internal class DefaultAuctionExecutor(
    private val tag: String,
    private val adSourceResolver: AdSourceResolver,
    private val adUnitPreparer: AdUnitPreparer,
    private val adaptersCollector: AdaptersCollector,
    private val batchSize: Int,
    private val requestAdUnitUseCase: RequestAdUnitUseCase,
    private val rtbResultStore: AdStore<RtbResultStore.Entry>,
    private val demandStatistics: DemandStatistics,
    private val stopCondition: AuctionStopCondition,
    private val winLossNotifier: WinLossNotifier,
) : AuctionExecutor {
    override suspend fun execute(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): List<AuctionResult> {
        val (context, mergedAdUnits, mergedTokens) =
            adUnitPreparer.prepare(demandAd, response, tokens)
        val executionResult =
            execute(
                context,
                demandAd,
                adTypeParam,
                response.pricefloor,
                response.auctionTimeout,
                mergedAdUnits,
                mergedTokens
            )
        return executionResult.also {
            winLossNotifier.notify(it, response.externalWinNotificationsEnabled)
        }
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
            LinkedList(adUnits).also {
                logInfo(
                    tag,
                    "Prepared: ${it.size} adUnits, ${tokens.size} tokens, pricefloor=$priceFloor, timeout=${auctionTimeout}ms"
                )
            }

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
        demandStatistics.record(pendingMeasurements)

        logInfo(tag, "Recorded ${pendingMeasurements.size} measurements")

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

        logInfo(tag, "Auction finished. Saved ${rtbAdUnits.size} unused RTB units to cache")

        return result.getOrElse {
            val status =
                if (it is TimeoutCancellationException) {
                    RoundStatus.FillTimeoutReached
                } else {
                    it.asBidonErrorOrUnspecified().asRoundStatus()
                }
            logInfo(
                tag,
                "Auction error: $it, draining ${adUnitQueue.size} remaining, status=$status"
            )
            drainRemainingAdUnits(adUnitQueue, tokens, auctionResults) { adUnit, token ->
                AuctionResult.AuctionFailed(adUnit, token, status)
            }
            auctionResults
        }
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
        while (iterator.hasNext() && batch.size < batchSize) {
            val adUnit = iterator.next()
            val tokenInfo = tokens[adUnit]

            if (adUnit.pricefloor < priceFloor) {
                iterator.remove()
                logInfo(
                    tag,
                    "Skipped ${adUnit.demandId}: pricefloor ${adUnit.pricefloor} < auction floor $priceFloor"
                )
                auctionResults.add(getBelowPriceFloorResult(adUnit, tokenInfo))
                continue
            }

            val adSource =
                adSourceResolver.resolve(
                    adUnit,
                    demandAd,
                    adTypeParam,
                    adaptersCollector.collectAll(),
                    tokenInfo
                )
            if (adSource == null) {
                iterator.remove()
                auctionResults.add(
                    AuctionResult.AuctionFailed(
                        adUnit,
                        tokenInfo,
                        RoundStatus.UnknownAdapter
                    )
                )
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
        val auctionResult = requestAdUnitUseCase.invoke(adSource, adUnit, adTypeParam, priceFloor)
        val latencyMs = SystemTimeNow - startTime
        logInfo(
            tag,
            "Loaded ${adUnit.demandId}: status=${auctionResult.roundStatus}, price=${auctionResult.adSource.getStats().price}, latency=${latencyMs}ms"
        )

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

}
