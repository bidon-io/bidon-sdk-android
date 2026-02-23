package org.bidon.sdk.ads.cache.impl.andr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdapterInfo
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.ext.toAuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.Auction.AuctionState
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.ext.printWaterfall
import org.bidon.sdk.auction.models.AuctionCancellation
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.onAny
import java.util.UUID

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
internal class AuctionImpl(
    private val executionDispatcher: CoroutineDispatcher,
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val executeAuction: ExecuteAuctionUseCase,
    private val auctionStat: AuctionStat,
    private val biddingConfig: BiddingConfig,
) : Auction {
    private val scope: CoroutineScope by lazy { CoroutineScope(executionDispatcher) }
    private val state = MutableStateFlow(AuctionState.Initialized)

    private var job: Job? = null

    private val resultsCollector: ResultsCollector by lazy { get() }

    override fun start(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (results: List<AuctionResult>, auctionInfo: AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        if (!state.compareAndSet(AuctionState.Initialized, AuctionState.InProgress)) {
            return
        }

        if (job?.isActive == true) {
            logInfo(TAG, "Action in progress $adTypeParam")
            return
        }

        job =
            scope.launch {
                startAsync(demandAd, adTypeParam)
                    .fold({
                        adTypeParam.activity.runOnUiThread {
                            onSuccess(it.first, it.second)
                        }
                    }, {
                        val info = if (it is BidonError.AuctionFailed) it.info else null
                        adTypeParam.activity.runOnUiThread {
                            onFailure(info, it)
                        }
                    })
            }
    }

    private suspend fun startAsync(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
    ): Result<Pair<List<AuctionResult>, AuctionInfo>> {
        logInfo(TAG, "Auction started $adTypeParam")

        resultsCollector.startRound(adTypeParam.pricefloor)
        resultsCollector.serverBiddingStarted()

        // Request for Auction-data at /auction
        val auctionId = UUID.randomUUID().toString()
        auctionStat.markAuctionStarted(auctionId, adTypeParam)

        val adapters = adaptersSource.adapters.associate { it.demandId.demandId to it.adapterInfo }
        val tokens = getTokens(adTypeParam, adaptersSource, biddingConfig.tokenTimeout)

        val auctionResponseResult =
            requestAuction(adTypeParam, auctionId, demandAd, adapters, tokens)
                .mapCatching { auctionData ->
                    if (auctionId != auctionData.auctionId) {
                        logError(
                            TAG, "Auction ID has been changed", IllegalStateException()
                        )
                    }

                    resultsCollector.serverBiddingFinished(auctionData.adUnits?.filter { it.bidType == BidType.RTB })
                    resultsCollector.setNoBidInfo(auctionData.noBids)

                    auctionData.also { it.printWaterfall(demandAd.adType) }
                }
        return auctionResponseResult
            .conductAuction(demandAd, adTypeParam, tokens)
            .onAuctionFailure(auctionResponseResult.getOrNull())
            .processAuctionFinish()
    }

    private suspend fun requestAuction(
        adTypeParam: AdTypeParam,
        auctionId: String,
        demandAd: DemandAd,
        adapters: Map<String, AdapterInfo>,
        tokens: Map<String, TokenInfo>,
    ): Result<AuctionResponse> {
        val auctionResponseResult =
            runCatching {
                getAuctionRequest.request(adTypeParam, auctionId, demandAd, adapters, tokens)
            }
        return auctionResponseResult.fold({ it }, { Result.failure(it) })
    }

    private suspend fun Result<AuctionResponse>.conductAuction(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        tokens: Map<String, TokenInfo>,
    ) = mapCatching {
        val (results, info) = conductAuction(it, demandAd, adTypeParam, tokens)
        if (results.isEmpty()) {
            throw BidonError.AuctionFailed(info, BidonError.NoAuctionResults)
        }
        results to info
    }

    private fun Result<Pair<List<AuctionResult>, AuctionInfo>>.processAuctionFinish() =
        onAny {
            // Finish auction
            state.value = AuctionState.Finished
            clearData()
        }

    private suspend fun Result<Pair<List<AuctionResult>, AuctionInfo>>.onAuctionFailure(
        auctionResponse: AuctionResponse?,
    ) = recoverCatching {
        val statResult = proceedRoundResults()
        val info =
            auctionResponse?.let {
                getAuctionInfo(it, statResult)
                    .also { printStatsData(auctionResponse, statResult, it) }
            }
        throw BidonError.AuctionFailed(info, it)
    }

    override fun cancel() {
        logInfo(TAG, "Trying to cancel auction. Is active: ${job?.isActive}")
        if (job?.isActive == true) {
            job?.cancel(AuctionCancellation())
            auctionStat.markAuctionCanceled()
            logInfo(TAG, "Auction canceled")
        }
        job = null
    }

    private fun getAuctionInfo(
        auctionData: AuctionResponse,
        statResult: RoundStat?,
    ): AuctionInfo =
        AuctionInfo(
            auctionId = auctionData.auctionId,
            auctionConfigurationId = auctionData.auctionConfigurationId,
            auctionConfigurationUid = auctionData.auctionConfigurationUid,
            auctionPricefloor = auctionData.pricefloor,
            auctionTimeout = auctionData.auctionTimeout,
            noBids = statResult?.noBids?.map { it.toAuctionNoBidInfo() },
            adUnits = statResult?.demands?.map { it.toAuctionInfo() },
        )

    private suspend fun conductAuction(
        auctionData: AuctionResponse,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        tokens: Map<String, TokenInfo>,
    ): Pair<List<AuctionResult>, AuctionInfo> {
        val auctionPriceFloor = auctionData.pricefloor
        val externalWinNotificationsEnabled = auctionData.externalWinNotificationsEnabled

        // Start auction
        executeAuction(
            auctionId = auctionData.auctionId,
            auctionConfigurationId = auctionData.auctionConfigurationId ?: 0L,
            auctionConfigurationUid = auctionData.auctionConfigurationUid ?: "",
            externalWinNotificationsEnabled = externalWinNotificationsEnabled,
            auctionTimeout = auctionData.auctionTimeout,
            pricefloor = auctionPriceFloor,
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            adUnits = auctionData.adUnits ?: emptyList(),
            resultsCollector = resultsCollector,
            tokens = tokens,
        )

        resultsCollector.saveWinners(auctionPriceFloor)

        // Save round results
        val statResult = proceedRoundResults()
        val auctionInfo =
            getAuctionInfo(auctionData = auctionData, statResult = statResult)
                .also { printStatsData(auctionData, statResult, it) }

        logInfo(TAG, "Rounds completed")

        // Finding winner / notifying losers
        val finalResults = resultsCollector.getAll()
        logInfo(
            TAG,
            "Action finished with ${finalResults.size} results (keeps maximum: ${ResultsCollector.MaxAuctionResultsAmount})"
        )
        finalResults.forEachIndexed { index, auctionResult ->
            logInfo(TAG, "Action result #$index: $auctionResult")
        }

        // Sending auction statistics
        auctionStat.sendAuctionStats(
            auctionData = auctionData,
            roundStat = statResult,
            demandAd = demandAd,
        )

        notifyWinLoss(finalResults, externalWinNotificationsEnabled)

        // Finish auction
        state.value = AuctionState.Finished
        // Wait for auction is completed
        state.first { it == AuctionState.Finished }
        val results = resultsCollector.getAll()
        clearData()
        return Pair(results, auctionInfo)
    }

    private fun printStatsData(
        auctionData: AuctionResponse,
        statResult: RoundStat?,
        auctionInfo: AuctionInfo
    ) {
        logInfo(
            TAG,
            "Was received: \nAdUnits: ${auctionData.adUnits?.size} \nNoBids: ${auctionData.noBids?.size}" + "\nWas sent:\nStats: ${statResult?.demands?.size} \nAuctionInfo AdUnits: ${auctionInfo.adUnits?.size} \n" + "AuctionInfo NoBids: ${auctionInfo.noBids?.size}"
        )
    }

    private suspend fun proceedRoundResults(): RoundStat? {
        (resultsCollector.getRoundResults() as? RoundResult.Results)?.let {
            return auctionStat.addRoundResults(it)
        }
        return null
    }

    private fun clearData() {
        logInfo(TAG, "Clearing data")
        resultsCollector.clear()
    }

    private fun notifyWinLoss(
        finalResults: List<AuctionResult>,
        externalWinNotificationsEnabled: Boolean
    ) {
        val winner = finalResults.getOrNull(0) ?: return
        val winnerAdSource = winner.adSource

        /**
         *  For internal statistics
         */
        winnerAdSource.markWin()

        /**
         * For AdNetworks - notify winner only if external notifications are disabled
         * Bidding demands should not be notified (server notifies them)
         */
        if (!externalWinNotificationsEnabled) {
            if (winner !is AuctionResult.Bidding && winnerAdSource is WinLossNotifiable) {
                winnerAdSource.notifyWin()
                logInfo(
                    TAG,
                    "Notified win to adapter: ${winnerAdSource.demandId} (external_win_notifications=false)"
                )
            } else if (winner is AuctionResult.Bidding) {
                logInfo(
                    TAG, "Skipped win notification for bidding demand: ${winnerAdSource.demandId}"
                )
            }
        } else {
            logInfo(
                TAG,
                "Skipped win notification to adapter: ${winnerAdSource.demandId} (external_win_notifications=true, will be notified externally)"
            )
        }

        // Notify all losers regardless of external_win_notifications flag
        finalResults.drop(1).forEach { loser ->
            val loserAdSource = loser.adSource
            /**
             *  Bidding demands should not be notified.
             *  All losers should be notified immediately regardless of external_win_notifications
             */
            if (loser !is AuctionResult.Bidding && loserAdSource is WinLossNotifiable) {
                logInfo(TAG, "Notified loss: ${loserAdSource.demandId}")
                loserAdSource.notifyLoss(
                    winnerAdSource.demandId.demandId, winnerAdSource.getStats().price
                )
            }
            if (loser.roundStatus == RoundStatus.Successful) {
                loserAdSource.markLoss()
            }
            logInfo(TAG, "Destroying loser: ${loserAdSource.demandId}")
            loserAdSource.destroy()
        }
    }
}

private const val TAG = "Auction"