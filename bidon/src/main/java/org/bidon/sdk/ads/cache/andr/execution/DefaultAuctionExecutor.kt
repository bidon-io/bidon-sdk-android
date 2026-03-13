package org.bidon.sdk.ads.cache.andr.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
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

private class StopConditionMet : Exception()

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultAuctionExecutor(
    private val tag: String,
    private val adSourceResolver: AdSourceResolver,
    private val adUnitPreparer: AdUnitPreparer,
    private val adaptersCollector: AdaptersCollector,
    private val batchSize: Int,
    private val rtbResultsStoreTtl: Long,
    private val requestAdUnitUseCase: RequestAdUnitUseCase,
    private val rtbResultStore: AdStore<RtbResultStore.Entry>,
    private val stopCondition: AuctionStopCondition,
    private val winLossNotifier: WinLossNotifier,
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
        val (mergedAdUnits, mergedTokens) =
            adUnitPreparer.prepare(response, tokens)
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

        logInfo(
            tag,
            "Prepared: ${adUnits.size} adUnits, ${tokens.size} tokens, pricefloor=$priceFloor, timeout=${auctionTimeout}ms"
        )

        // 1. Pre-filter: resolve loadable ad units synchronously
        val loadable =
            prepareLoadable(
                adUnits, demandAd, adTypeParam, priceFloor, tokens, auctionResults
            )

        // 2. Load concurrently with sliding window
        val loaded = mutableSetOf<AdUnit>()

        val result =
            runCatching {
                withTimeout(auctionTimeout) {
                    loadable.entries
                        .asFlow()
                        .flatMapMerge(batchSize) { (adUnit, adSource) ->
                            flow {
                                emit(
                                    adUnit to
                                        loadAdUnit(
                                            context,
                                            adSource,
                                            adUnit,
                                            demandAd,
                                            adTypeParam,
                                            priceFloor
                                        )
                                )
                            }
                        }.collect { (adUnit, auctionResult) ->
                            loaded.add(adUnit)
                            auctionResults.add(auctionResult)

                            val successCount =
                                auctionResults.count { it.roundStatus == RoundStatus.Successful }
                            if (stopCondition.shouldStop(successCount, auctionResult, null)) {
                                logInfo(
                                    tag,
                                    "Stop condition met after $successCount successful loads"
                                )
                                throw StopConditionMet()
                            }
                        }

                    auctionResults
                }
            }

        // 3. Drain units that were not loaded (cancelled in-flight or never started)
        val remaining = loadable.keys.filterNot { it in loaded }

        // Save unused RTB for caching
        val rtbAdUnits =
            remaining
                .rtb()
                .fold(mutableSetOf<RtbResultStore.Entry>()) { acc, adUnit ->
                    val tokenInfo = tokens[adUnit]
                    if (tokenInfo != null) {
                        acc.add(
                            RtbResultStore.Entry(
                                context.id,
                                tokenInfo,
                                adUnit,
                                expireAt = SystemTimeNow + rtbResultsStoreTtl
                            )
                        )
                    }
                    acc
                }
        rtbResultStore.insert(rtbAdUnits) { it }

        logInfo(tag, "Auction finished. Saved ${rtbAdUnits.size} unused RTB units to cache")

        return result.getOrElse { error ->
            val toResult: (AdUnit, TokenInfo?) -> AuctionResult =
                when (error) {
                    is StopConditionMet -> ::getBelowPriceFloorResult

                    is TimeoutCancellationException -> { adUnit, token ->
                        AuctionResult.AuctionFailed(adUnit, token, RoundStatus.FillTimeoutReached)
                    }

                    else -> { adUnit, token ->
                        AuctionResult.AuctionFailed(
                            adUnit,
                            token,
                            error.asBidonErrorOrUnspecified().asRoundStatus()
                        )
                    }
                }
            if (error !is StopConditionMet) {
                logInfo(tag, "Auction error: $error, draining ${remaining.size} remaining")
            }
            remaining.forEach { auctionResults.add(toResult(it, tokens[it])) }
            auctionResults
        }
    }

    private fun prepareLoadable(
        adUnits: List<AdUnit>,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
        tokens: Map<AdUnit, TokenInfo>,
        auctionResults: MutableList<AuctionResult>,
    ): Map<AdUnit, AdSource<AdAuctionParams>> {
        val loadable = mutableMapOf<AdUnit, AdSource<AdAuctionParams>>()
        for (adUnit in adUnits) {
            val tokenInfo = tokens[adUnit]
            if (adUnit.pricefloor < priceFloor) {
                logInfo(
                    tag,
                    "Skipped ${adUnit.demandId}: pricefloor ${adUnit.pricefloor} < auction floor $priceFloor"
                )
                auctionResults.add(getBelowPriceFloorResult(adUnit, tokenInfo))
                continue
            }
            val adSource =
                adSourceResolver.resolve(
                    adUnit, demandAd, adTypeParam, adaptersCollector.collectAll(), tokenInfo
                )
            if (adSource == null) {
                auctionResults.add(
                    AuctionResult.AuctionFailed(adUnit, tokenInfo, RoundStatus.UnknownAdapter)
                )
                continue
            }
            loadable[adUnit] = adSource
        }
        return loadable
    }

    private suspend fun loadAdUnit(
        context: AuctionContext,
        adSource: AdSource<AdAuctionParams>,
        adUnit: AdUnit,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        priceFloor: Double,
    ): AuctionResult {
        applyParams(context, adSource, adTypeParam, demandAd, priceFloor)

        val startTime = SystemTimeNow
        val auctionResult = requestAdUnitUseCase.invoke(adSource, adUnit, adTypeParam, priceFloor)
        val latencyMs = SystemTimeNow - startTime
        logInfo(
            tag,
            "Loaded ${adUnit.demandId}: status=${auctionResult.roundStatus}, price=${auctionResult.adSource.getStats().price}, latency=${latencyMs}ms"
        )

        return auctionResult
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
