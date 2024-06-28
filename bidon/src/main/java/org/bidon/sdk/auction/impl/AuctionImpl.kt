package org.bidon.sdk.auction.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AdUnitInfo
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.Auction.AuctionState
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.ext.printWaterfall
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.models.toBidsInfo
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
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.SystemTimeNow
import java.util.UUID

/**
 * Created by Aleksei Cherniaev on 06/02/2023.
 */
internal class AuctionImpl(
    private val adaptersSource: AdaptersSource,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val executeAuction: ExecuteAuctionUseCase,
    private val auctionStat: AuctionStat,
    private val tokenGetter: GetTokensUseCase,
    private val biddingConfig: BiddingConfig,
) : Auction {
    private val scope: CoroutineScope by lazy { CoroutineScope(SdkDispatchers.Main) }
    private val state = MutableStateFlow(AuctionState.Initialized)

    private var _auctionDataResponse: AuctionResponse? = null
    private var _demandAd: DemandAd? = null
    private var job: Job? = null
    private var adTypeParam: AdTypeParam? = null
    private val resultsCollector: ResultsCollector by lazy { get() }

    override fun start(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        onSuccess: (results: List<AuctionResult>, auctionInfo: AuctionInfo) -> Unit,
        onFailure: (Throwable) -> Unit
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
            this.adTypeParam = adTypeParam
            job = scope.launch {
                runCatching {
                    logInfo(TAG, "Auction started $this")
                    resultsCollector.startRound(adTypeParam.pricefloor)
                    val biddingStartTs = SystemTimeNow
                    resultsCollector.serverBiddingStarted()

                    val tokens = tokenGetter.invoke(
                        adType = demandAd.adType,
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
                        tokens = tokens,
                    ).mapCatching { auctionData ->
                        if (auctionId != auctionData.auctionId) {
                            logError(TAG, "Auction ID has been changed", IllegalStateException())
                        }
                        resultsCollector.serverBiddingFinished(auctionData.adUnits?.filter {
                            it.bidType == BidType.RTB }
                        )
                        resultsCollector.addNoBidData(auctionData.noBids.map {
                            it.toBidsInfo(biddingStartTs, SystemTimeNow)
                        })
                        auctionData.printWaterfall(demandAd.adType)
                        val result = conductAuction(
                            auctionData = auctionData,
                            demandAd = demandAd,
                            adTypeParamData = adTypeParam,
                            tokens = tokens,
                        )
                        result.first.ifEmpty {
                            throw BidonError.NoAuctionResults
                        }
                        adTypeParam.activity.runOnUiThread {
                            onSuccess(result.first, result.second)
                        }
                    }.onFailure {
                        logError(TAG, "Auction failed", it)
                        adTypeParam.activity.runOnUiThread {
                            onFailure(it)
                        }
                    }
                }.onFailure {
                    logError(TAG, "Auction failed", it)
                    adTypeParam.activity.runOnUiThread {
                        onFailure(it)
                    }
                }
            }
        }
    }

    override fun cancel() {
        if (job?.isActive == true) {
            job?.cancel()
            scope.launch {
                auctionStat.markAuctionCanceled()
                proceedRoundResults()
                val auctionData = _auctionDataResponse
                if (auctionData == null) {
                    logInfo(TAG, "No AuctionResponse info. There is nothing to send.")
                } else {
                    auctionStat.sendAuctionStats(
                        auctionData = auctionData,
                        demandAd = requireNotNull(_demandAd),
                    )
                }
                logInfo(TAG, "Auction canceled")
                clearData()
            }
        }
        job = null
    }

    private suspend fun conductAuction(
        auctionData: AuctionResponse,
        demandAd: DemandAd,
        adTypeParamData: AdTypeParam,
        tokens: Map<String, TokenInfo>,
    ): Pair<List<AuctionResult>, AuctionInfo> {
        _auctionDataResponse = auctionData
        _demandAd = demandAd
        val auctionId = auctionData.auctionId
        val auctionPriceFloor = auctionData.pricefloor
        val auctionConfigurationId = auctionData.auctionConfigurationId ?: 0L
        val auctionConfigurationUid = auctionData.auctionConfigurationUid ?: ""
        // Start auction
        executeAuction(
            auctionId = auctionId,
            auctionConfigurationId = auctionConfigurationId,
            auctionConfigurationUid = auctionConfigurationUid,
            externalWinNotificationsEnabled = auctionData.externalWinNotificationsEnabled,
            auctionTimeout = auctionData.auctionTimeout,
            pricefloor = auctionPriceFloor,
            demandAd = demandAd,
            adTypeParam = adTypeParamData,
            adUnits = auctionData.adUnits ?: emptyList(),
            resultsCollector = resultsCollector,
            tokens = tokens,
        )

        (resultsCollector.getRoundResults() as? RoundResult.Results)?.let { result ->
            val auctionResults =  result.getAuctionResults()
            resultsCollector.addAdUnitInfoData(
                auctionResults.map { it.asAdUnitInfo(auctionData.adUnits) })
        }
        val auctionInfo = resultsCollector.getAuctionInfo(
            auctionId = auctionId,
            auctionConfigurationId = auctionConfigurationId,
            auctionConfigurationUid = auctionConfigurationUid,
            auctionPriceFloor = auctionPriceFloor
        )

        // Save round results
        resultsCollector.saveWinners(auctionPriceFloor)
        proceedRoundResults()

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

    private fun AuctionResult.asAdUnitInfo(adUnit: List<AdUnit>?): AdUnitInfo {
        val currentAdUnit = adUnit?.find { it.uid == adSource.getAd()?.adUnit?.uid }
        return when (this) {
            is AuctionResult.Bidding,
            is AuctionResult.Network,
            -> {
                val stat = adSource.getStats()
                AdUnitInfo(
                    demandId = adSource.demandId.demandId,
                    status = roundStatus.code.takeIf { job?.isCancelled == false }
                        ?: RoundStatus.AuctionCancelled.code,
                    price = currentAdUnit?.pricefloor,
                    bidType = currentAdUnit?.bidType?.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    uid = currentAdUnit?.uid,
                    label = currentAdUnit?.label,
                    ext = currentAdUnit?.extra
                )
            }

            else ->
                AdUnitInfo(
                    demandId = adSource.demandId.demandId,
                    status = roundStatus.code.takeIf { job?.isCancelled == false }
                        ?: RoundStatus.AuctionCancelled.code,
                    price = currentAdUnit?.pricefloor,
                    bidType = currentAdUnit?.bidType?.code,
                    fillStartTs = null,
                    fillFinishTs = null,
                    uid = currentAdUnit?.uid,
                    label = currentAdUnit?.label,
                    ext = currentAdUnit?.extra
                )
        }
    }

    private suspend fun proceedRoundResults() {
        (resultsCollector.getRoundResults() as? RoundResult.Results)?.let {
            auctionStat.addRoundResults(it)
        }
        resultsCollector.clearRoundResults()
    }

    private fun clearData() {
        resultsCollector.clear()
        _auctionDataResponse = null
    }

    private fun notifyWinLoss(finalResults: List<AuctionResult>) {
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
                if (auctionResult !is AuctionResult.Bidding && adSource is WinLossNotifiable) {
                    logInfo(TAG, "Notified loss: ${adSource.demandId}")
                    adSource.notifyLoss(
                        winner.adSource.demandId.demandId,
                        winner.adSource.getStats().ecpm
                    )
                }
                if (auctionResult.roundStatus == RoundStatus.Successful) {
                    adSource.markLoss()
                }
                logInfo(TAG, "Destroying loser: ${adSource.demandId}")
                adSource.destroy()
            }
    }
}

private const val TAG = "Auction"
