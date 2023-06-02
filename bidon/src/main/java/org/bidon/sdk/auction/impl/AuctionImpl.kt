package org.bidon.sdk.auction.impl

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdLoadingType
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.DemandId
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.AuctionState
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.BiddingDemandId
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.auction.usecases.ConductBiddingAuctionUseCase
import org.bidon.sdk.auction.usecases.ConductNetworkAuctionUseCase
import org.bidon.sdk.auction.usecases.DeferredAdEvent
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.DemandStat
import org.bidon.sdk.stats.RoundStat
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.UUID

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
internal class AuctionImpl(
    private val adaptersSource: AdaptersSource,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val statsRequest: StatsRequestUseCase,
    private val conductBiddingAuction: ConductBiddingAuctionUseCase,
    private val conductNetworkAuction: ConductNetworkAuctionUseCase,
) : Auction {
    private val state = MutableStateFlow(AuctionState.Initialized)
    private val auctionResults = MutableStateFlow(listOf<AuctionResult>())
    private val statsRound = mutableListOf<RoundStat>()
    private val statsAuctionResults = mutableListOf<AuctionResult>()
    private val mutableLineItems = mutableListOf<LineItem>()
    private var _auctionDataResponse: AuctionResponse? = null
    private val auctionDataResponse: AuctionResponse
        get() = requireNotNull(_auctionDataResponse)

    override suspend fun start(
        demandAd: DemandAd,
        resolver: AuctionResolver,
        adTypeParamData: AdTypeParam
    ): Result<List<AuctionResult>> = runCatching {
        if (state.compareAndSet(
                expect = AuctionState.Initialized,
                update = AuctionState.InProgress
            )
        ) {
            logInfo(Tag, "Action started $this")
            // Request for Auction-data at /auction
            val auctionStartTs = SystemTimeNow
            getAuctionRequest.request(
                additionalData = adTypeParamData,
                auctionId = UUID.randomUUID().toString(),
                demandAd = demandAd,
                adapters = adaptersSource.adapters.associate {
                    it.demandId.demandId to it.adapterInfo
                }
            ).onSuccess { auctionData ->
                _auctionDataResponse = auctionData
                mutableLineItems.addAll(auctionData.lineItems ?: emptyList())
                // Start auction
                conductRounds(
                    rounds = auctionData.rounds ?: listOf(),
                    sourcePriceFloor = auctionData.pricefloor ?: 0.0,
                    pricefloor = auctionData.pricefloor ?: 0.0,
                    resolver = resolver,
                    demandAd = demandAd,
                    adTypeParamData = adTypeParamData
                )
                logInfo(Tag, "Rounds completed")

                // Finding winner
                val finalResults = auctionResults.value

                logInfo(Tag, "Action finished with ${finalResults.size} results")
                finalResults.forEachIndexed { index, auctionResult ->
                    logInfo(Tag, "Action result #$index: $auctionResult")
                }
                notifyWinLoss(finalResults)

                // Finish auction
                state.value = AuctionState.Finished
                sendStatsAsync(
                    demandAd,
                    auctionStartTs = auctionStartTs,
                    auctionFinishTs = SystemTimeNow
                )
            }.getOrThrow()
        }
        state.first { it == AuctionState.Finished }
        val results = auctionResults.value.toList()
        clearData()
        results.ifEmpty {
            throw BidonError.NoAuctionResults
        }
    }

    private fun clearData() {
        auctionResults.value = emptyList()
        statsRound.clear()
        statsAuctionResults.clear()
        mutableLineItems.clear()
        _auctionDataResponse = null
    }

    private fun notifyWinLoss(finalResults: List<AuctionResult>) {
        val winner = finalResults.getOrNull(0) ?: return
        winner.adSource.markWin()
        finalResults.drop(1)
            .forEach { auctionResult ->
                val adSource = auctionResult.adSource
                if (adSource is WinLossNotifiable) {
                    logInfo(Tag, "Notified loss: ${adSource.demandId}")
                    adSource.notifyLoss(winner.adSource.demandId.demandId, winner.ecpm)
                }
                if (auctionResult.roundStatus == RoundStatus.Successful) {
                    (adSource as StatisticsCollector).markLoss()
                }
                logInfo(Tag, "Destroying loser: ${adSource.demandId}")
                adSource.destroy()
            }
    }

    private suspend fun conductRounds(
        rounds: List<Round>,
        sourcePriceFloor: Double,
        pricefloor: Double,
        resolver: AuctionResolver,
        demandAd: DemandAd,
        adTypeParamData: AdTypeParam,
    ) {
        val round = rounds.firstOrNull() ?: return
        val allRoundResults = executeRound(
            round = round,
            pricefloor = pricefloor,
            demandAd = demandAd,
            adTypeParamData = adTypeParamData,
        ).getOrNull() ?: emptyList()
        proceedRoundResults(
            resolver = resolver,
            allResults = allRoundResults,
            sourcePriceFloor = sourcePriceFloor,
            round = round,
            pricefloor = pricefloor,
        )
        val nextPriceFloor = auctionResults.value.firstOrNull()?.ecpm ?: pricefloor
        conductRounds(
            rounds = rounds.drop(1),
            sourcePriceFloor = sourcePriceFloor,
            pricefloor = nextPriceFloor,
            resolver = resolver,
            demandAd = demandAd,
            adTypeParamData = adTypeParamData,
        )
    }

    private suspend fun proceedRoundResults(
        resolver: AuctionResolver,
        allResults: List<AuctionResult>,
        sourcePriceFloor: Double,
        round: Round,
        pricefloor: Double,
    ) {
        val sortedResult = resolver.sortWinners(allResults)
        val successfulResults = sortedResult
            .filter { (it.adSource as StatisticsCollector).buildBidStatistic().roundStatus == RoundStatus.Successful }
            .filter {
                /**
                 * Received ecpm should not be less then initial one [sourcePriceFloor].
                 */
                val isAbovePricefloor = it.ecpm >= sourcePriceFloor
                if (!isAbovePricefloor) {
                    (it.adSource as StatisticsCollector).markBelowPricefloor()
                }
                isAbovePricefloor
            }

        /**
         * Save statistic data for /stats
         */
        saveStatistics(
            round = round,
            pricefloor = pricefloor,
            allRoundResults = allResults,
            sortedRoundResult = sortedResult,
            successfulRoundResults = successfulResults,
        )

        /**
         * Save auction results data
         */
        if (successfulResults.isNotEmpty()) {
            saveAuctionResults(
                resolver = resolver,
                roundResults = successfulResults
            )
        } else {
            logError(Tag, "Round '${round.id}' failed", BidonError.NoRoundResults)
        }
    }

    private fun saveStatistics(
        round: Round,
        pricefloor: Double,
        allRoundResults: List<AuctionResult>,
        sortedRoundResult: List<AuctionResult>,
        successfulRoundResults: List<AuctionResult>,
    ) {
        val winner = successfulRoundResults.firstOrNull()
        val unknownDemandId =
            (round.demandIds - allRoundResults.map { it.adSource.demandId.demandId }.toSet())
                .takeIf { it.isNotEmpty() }
                ?.map { demandId ->
                    DemandStat(
                        roundStatus = RoundStatus.UnknownAdapter,
                        demandId = DemandId(demandId),
                        bidStartTs = null,
                        bidFinishTs = null,
                        fillStartTs = null,
                        fillFinishTs = null,
                        ecpm = null,
                        adUnitId = null
                    )
                } ?: emptyList()

        allRoundResults.forEach {
            (it.adSource as StatisticsCollector).addAuctionConfigurationId(
                auctionConfigurationId = auctionDataResponse.auctionConfigurationId ?: 0
            )
        }

        val roundStat = RoundStat(
            auctionId = auctionDataResponse.auctionId ?: "",
            roundId = round.id,
            pricefloor = pricefloor,
            winnerDemandId = winner?.adSource?.demandId,
            winnerEcpm = winner?.ecpm,
            demands = unknownDemandId
        )
        statsAuctionResults.addAll(sortedRoundResult)
        statsRound.add(roundStat)
    }

    private suspend fun sendStatsAsync(
        demandAd: DemandAd,
        auctionStartTs: Long,
        auctionFinishTs: Long,
    ) {
        coroutineScope {
            launch(SdkDispatchers.Default) {
                val bidStats = statsAuctionResults.map {
                    (it.adSource as StatisticsCollector).buildBidStatistic()
                }
                statsRequest.invoke(
                    auctionId = auctionDataResponse.auctionId ?: "",
                    auctionConfigurationId = auctionDataResponse.auctionConfigurationId ?: -1,
                    results = statsRound.map { roundStat ->
                        val errorDemandStat = roundStat.demands
                        val succeedDemandStat = bidStats.filter { it.roundId == roundStat.roundId }
                            .map { bidStat ->
                                DemandStat(
                                    roundStatus = requireNotNull(bidStat.roundStatus),
                                    demandId = bidStat.demandId,
                                    bidStartTs = bidStat.bidStartTs,
                                    bidFinishTs = bidStat.bidFinishTs,
                                    fillStartTs = bidStat.fillStartTs,
                                    fillFinishTs = bidStat.fillFinishTs,
                                    ecpm = bidStat.ecpm.takeIf {
                                        bidStat.roundStatus !in arrayOf(
                                            RoundStatus.NoBid,
                                            RoundStatus.NoAppropriateAdUnitId
                                        )
                                    },
                                    adUnitId = bidStat.adUnitId
                                )
                            }
                        roundStat.copy(
                            demands = (succeedDemandStat + errorDemandStat).map { demandStat ->
                                if (demandStat.roundStatus == RoundStatus.Successful) {
                                    demandStat.copy(
                                        roundStatus = RoundStatus.Loss
                                    )
                                } else {
                                    demandStat
                                }
                            }
                        )
                    },
                    demandAd = demandAd,
                    auctionStartTs = auctionStartTs,
                    auctionFinishTs = auctionFinishTs
                )
                statsRound.clear()
            }
        }
    }

    private suspend fun executeRound(
        round: Round,
        pricefloor: Double,
        demandAd: DemandAd,
        adTypeParamData: AdTypeParam,
    ): Result<List<AuctionResult>> = coroutineScope {
        runCatching {
            val filteredAdapters = adaptersSource.adapters.filter {
                it.demandId.demandId in (round.demandIds + round.biddingIds)
            }
            (round.demandIds - filteredAdapters.map { it.demandId.demandId }.toSet())
                .takeIf { it.isNotEmpty() }
                ?.let { unknownDemandIds ->
                    logError(
                        tag = Tag,
                        message = "Adapters not found: $unknownDemandIds",
                        error = NoSuchElementException(unknownDemandIds.joinToString())
                    )
                }
            logInfo(
                Tag,
                "Round '${round.id}' started with adapters [${filteredAdapters.joinToString { it.demandId.demandId }}]"
            )
            logInfo(Tag, "Round '${round.id}' started with line items: $mutableLineItems")
            val adSources = when (demandAd.adType) {
                AdType.Interstitial -> {
                    filteredAdapters.filterIsInstance<AdProvider.Interstitial<AdAuctionParams>>()
                        .map {
                            it.interstitial(
                                demandAd = demandAd,
                                roundId = round.id,
                                auctionId = auctionDataResponse.auctionId ?: ""
                            )
                        }
                }

                AdType.Rewarded -> {
                    filteredAdapters.filterIsInstance<AdProvider.Rewarded<AdAuctionParams>>().map {
                        it.rewarded(
                            demandAd = demandAd,
                            roundId = round.id,
                            auctionId = auctionDataResponse.auctionId ?: ""
                        )
                    }
                }

                AdType.Banner -> {
                    filteredAdapters.filterIsInstance<AdProvider.Banner<AdAuctionParams>>().map {
                        it.banner(
                            demandAd = demandAd,
                            roundId = round.id,
                            auctionId = auctionDataResponse.auctionId ?: ""
                        )
                    }
                }
            }
            val roundDeferred = mutableListOf<Deferred<DeferredAdEvent?>>()
            if (round.biddingIds.isNotEmpty()) {
                val biddingResultDeferred = async {
                    conductBiddingAuction.invoke(
                        context = adTypeParamData.activity.applicationContext,
                        biddingSources = adSources.filterIsInstance<AdLoadingType.Bidding<AdAuctionParams>>(),
                        participantIds = round.biddingIds,
                        adTypeParam = adTypeParamData,
                        demandAd = demandAd,
                        bidfloor = pricefloor,
                        auctionId = auctionDataResponse.auctionId ?: "",
                        round = round,
                        auctionConfigurationId = auctionDataResponse.auctionConfigurationId
                    ) ?: DeferredAdEvent(
                        adEvent = AdEvent.LoadFailed(BidonError.NoBid(BiddingDemandId)),
                        adSource = null
                    )
                }
                roundDeferred.add(biddingResultDeferred)
            }
            if (round.demandIds.isNotEmpty()) {
                val networkResults = conductNetworkAuction.invoke(
                    context = adTypeParamData.activity,
                    networkSources = adSources.filterIsInstance<AdLoadingType.Network<AdAuctionParams>>(),
                    participantIds = round.demandIds,
                    adTypeParam = adTypeParamData,
                    demandAd = demandAd,
                    lineItems = mutableLineItems,
                    round = round,
                    pricefloor = pricefloor
                )
                mutableLineItems.clear()
                mutableLineItems.addAll(networkResults.remainingLineItems)
                roundDeferred.addAll(networkResults.results)
            }
            roundDeferred.mapIndexedNotNull { index, deferred ->
                val result = deferred.await() ?: return@mapIndexedNotNull null
                val adSource = result.adSource ?: return@mapIndexedNotNull null
                val adEvent = result.adEvent
                val logRoundTitle = "Round '${round.id}' result #$index(${adSource.demandId.demandId})"
                logInfo(Tag, "$logRoundTitle: $adEvent. Statistics: ${adSource.buildBidStatistic()}")
                AuctionResult(
                    roundStatus = when (adEvent) {
                        is AdEvent.Fill -> RoundStatus.Successful
                        is AdEvent.Expired -> RoundStatus.NoFill
                        is AdEvent.LoadFailed -> adEvent.cause.asRoundStatus()
                        else -> error("unexpected: $adEvent")
                    },
                    ecpm = (adEvent as? AdEvent.Fill)?.ad?.ecpm ?: 0.0,
                    adSource = adSource
                )
            }.also {
                logInfo(Tag, "Round '${round.id}' finished with ${it.size} results: $it")
            }
        }
    }

    private suspend fun saveAuctionResults(
        resolver: AuctionResolver,
        roundResults: List<AuctionResult>
    ) {
        auctionResults.value = resolver.sortWinners(auctionResults.value + roundResults)
    }
}

private const val Tag = "Auction"
