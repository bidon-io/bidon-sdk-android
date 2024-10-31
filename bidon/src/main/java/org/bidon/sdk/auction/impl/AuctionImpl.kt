package org.bidon.sdk.auction.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.bidon.sdk.auction.models.AuctionCancellation
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.models.logAuctionWaterfall
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetDemandsTokensUseCase
import org.bidon.sdk.auction.usecases.models.AuctionResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.DemandStatus
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import java.util.UUID

/**
 * Created by Bidon Team on 06/02/2023.
 */
internal class AuctionImpl(
    private val adaptersSource: AdaptersSource,
    private val getDemandsTokens: GetDemandsTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val executeAuction: ExecuteAuctionUseCase,
    private val auctionStat: AuctionStat,
    private val biddingConfig: BiddingConfig,
) : Auction {
    private val scope: CoroutineScope by lazy { CoroutineScope(SdkDispatchers.Main) }
    private val state = MutableStateFlow(AuctionState.Initialized)

    private var _auctionDataResponse: AuctionResponse? = null
    private var _demandAd: DemandAd? = null
    private var job: Job? = null
    private val resultsCollector: ResultsCollector by lazy { get() }

    override fun start(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (results: List<DemandResult>, auctionInfo: AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        if (state.compareAndSet(
                expect = AuctionState.Initialized,
                update = AuctionState.InProgress
            )
        ) {
            if (job?.isActive == true) {
                logInfo(TAG, "Action in progress $this")
                return
            }
            job = scope.launch {
                runCatching {
                    logInfo(TAG, "Auction started $this")
                    resultsCollector.startAuction(adTypeParam.pricefloor)
                    resultsCollector.serverBiddingStarted()

                    val demandsTokens = getDemandsTokens(
                        adTypeParam = adTypeParam,
                        adaptersSource = adaptersSource,
                        tokenTimeout = biddingConfig.tokenTimeout
                    )

                    // Request for Auction-data at /auction
                    val auctionId = UUID.randomUUID().toString()
                    auctionStat.markAuctionStarted(auctionId, adTypeParam)
                    getAuctionRequest.request(
                        adTypeParam = adTypeParam,
                        auctionId = auctionId,
                        demandAd = demandAd,
                        adapters = adaptersSource.adapters.associate {
                            it.demandId.demandId to it.adapterInfo
                        },
                        demandsTokens = demandsTokens,
                    ).mapCatching { auctionData ->
                        if (auctionId != auctionData.auctionId) {
                            logError(TAG, "Auction ID has been changed", IllegalStateException())
                        }
                        resultsCollector.serverBiddingFinished(demandsTokens, auctionData.noBids)

                        auctionData.logAuctionWaterfall(demandAd)

                        val (results, auctionInfo) = conductAuction(
                            auctionData = auctionData,
                            demandAd = demandAd,
                            adTypeParamData = adTypeParam,
                            tokens = demandsTokens,
                        )
                        if (results.isEmpty()) {
                            onFailure(auctionInfo, BidonError.NoAuctionResults)
                        } else {
                            onSuccess(results, auctionInfo)
                        }
                    }.onFailure { cause ->
                        logError(TAG, "Auction failed during execution", cause)
                        processAuctionFailed(adTypeParam, onFailure, cause)
                    }
                }.onFailure { cause ->
                    logError(TAG, "Auction failed", cause)
                    processAuctionFailed(adTypeParam, onFailure, cause)
                }
            }
        }
    }

    private suspend fun processAuctionFailed(
        adTypeParam: AdTypeParam,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
        cause: Throwable
    ) {
        val statResult = proceedRoundResults()
        val auctionData = _auctionDataResponse
        if (auctionData == null) {
            logInfo(TAG, "No auction data response info.")
            onFailure(null, cause)
        } else {
            val auctionInfo = getAuctionInfo(auctionData, statResult)
            onFailure(auctionInfo, cause)
        }
        // Finish auction
        state.value = AuctionState.Finished
        clearData()
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

    private fun getAuctionInfo(auctionData: AuctionResponse, statResult: RoundStat?): AuctionInfo {
        return AuctionInfo(
            auctionId = auctionData.auctionId,
            auctionConfigurationId = auctionData.auctionConfigurationId,
            auctionConfigurationUid = auctionData.auctionConfigurationUid,
            auctionPricefloor = auctionData.pricefloor,
            auctionTimeout = auctionData.auctionTimeout,
            noBids = statResult?.noBids?.map { it.toAuctionNoBidInfo() },
            adUnits = statResult?.demands?.map { it.toAuctionInfo() },
        )
    }

    private suspend fun conductAuction(
        auctionData: AuctionResponse,
        demandAd: DemandAd,
        adTypeParamData: AdTypeParam,
        tokens: Map<String, TokenInfo>,
    ): Pair<List<DemandResult>, AuctionInfo> {
        _auctionDataResponse = auctionData
        _demandAd = demandAd
        val auctionPriceFloor = auctionData.pricefloor
        // Start auction
        executeAuction(
            auctionId = auctionData.auctionId,
            auctionConfigurationId = auctionData.auctionConfigurationId ?: 0L,
            auctionConfigurationUid = auctionData.auctionConfigurationUid ?: "",
            externalWinNotificationsEnabled = auctionData.externalWinNotificationsEnabled,
            auctionTimeout = auctionData.auctionTimeout,
            pricefloor = auctionPriceFloor,
            demandAd = demandAd,
            adTypeParam = adTypeParamData,
            adUnits = auctionData.adUnits ?: emptyList(),
            resultsCollector = resultsCollector,
            tokens = tokens,
        )

        resultsCollector.finishAuction(auctionPriceFloor)
        // Save round results
        val statResult = proceedRoundResults()

        val auctionInfo = getAuctionInfo(auctionData = auctionData, statResult = statResult)

        // Finding winner / notifying losers
        val finalResults = resultsCollector.getAll()
        logInfo(
            TAG,
            "Action finished with ${finalResults.size} results (keeps maximum: ${ResultsCollector.MaxAuctionResultsAmount})"
        )
        finalResults.forEachIndexed { index, auctionResult ->
            logInfo(TAG, "Action result #$index: ${auctionResult.adSource.demandId.demandId} -> ${auctionResult.demandStatus.code}")
        }

        // Sending auction statistics
        auctionStat.sendAuctionStats(
            auctionData = auctionData,
            roundStat = statResult,
            demandAd = demandAd,
        )

        notifyWinLoss(finalResults)

        // Finish auction
        state.value = AuctionState.Finished
        // Wait for auction is completed
        state.first { it == AuctionState.Finished }
        val results = resultsCollector.getAll()
        clearData()
        return Pair(results, auctionInfo)
    }

    private suspend fun proceedRoundResults(): RoundStat? {
        (resultsCollector.getRoundResults() as? AuctionResult.Results)?.let {
            return auctionStat.addRoundResults(it)
        }
        return null
    }

    private fun clearData() {
        logInfo(TAG, "Clearing data")
        resultsCollector.clear()
        _auctionDataResponse = null
    }

    private fun notifyWinLoss(finalResults: List<DemandResult>) {
        val winner = finalResults.getOrNull(0) ?: return

        /**
         *  For internal statistics
         */
        winner.adSource.markWin()

        /**
         * For AdNetworks
         */
        (winner.adSource as? WinLossNotifiable)?.notifyWin()

        finalResults.drop(1)
            .forEach { auctionResult ->
                val adSource = auctionResult.adSource
                /**
                 *  Bidding demands should not be notified.
                 */
                if (auctionResult !is DemandResult.Bidding && adSource is WinLossNotifiable) {
                    logInfo(TAG, "Notified loss: ${adSource.demandId}")
                    adSource.notifyLoss(
                        winner.adSource.demandId.demandId,
                        winner.adSource.getStats().ecpm
                    )
                }
                if (auctionResult.demandStatus == DemandStatus.Successful) {
                    adSource.markLoss()
                }
                logInfo(TAG, "Destroying loser: ${adSource.demandId}")
                adSource.destroy()
            }
    }
}

private const val TAG = "Auction"
