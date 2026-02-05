package org.bidon.sdk.ads.cache.impl.alex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdUnitInfo
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.ext.toAuctionNoBidInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.ext.printWaterfall
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import java.util.UUID

/**
 * Custom auction implementation for Ad Cache V5.
 * Executes RTB and CPM auctions in parallel, stores AuctionResults directly.
 */
internal class AlexAuction(
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val executeAuction: ExecuteAuctionUseCase,
    private val auctionStat: AuctionStat,
    private val biddingConfig: BiddingConfig,
) {
    private val scope: CoroutineScope by lazy { CoroutineScope(SdkDispatchers.Main) }
    private val state = MutableStateFlow(AuctionState.Initialized)

    private var _auctionDataResponse: AuctionResponse? = null
    private var job: Job? = null
    private val resultsCollector: ResultsCollector by lazy { get() }

    enum class AuctionState {
        Initialized,
        InProgress,
        Finished,
    }

    fun start(
        demandAd: DemandAd,
        existingResults: List<AuctionResult>,
        adTypeParam: AdTypeParam,
        onResult: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        if (state.compareAndSet(
                expect = AuctionState.Initialized,
                update = AuctionState.InProgress
            )
        ) {
            if (job?.isActive == true) {
                logInfo(TAG, "Auction in progress $this")
                return
            }
            val filteredAdapterSources = filterAdapters(existingResults)
            job = scope.launch {
                runCatching {
                    logInfo(TAG, "Auction started $this")
                    resultsCollector.startRound(adTypeParam.pricefloor)
                    resultsCollector.serverBiddingStarted()

                    // Generate auction ID
                    val auctionId = UUID.randomUUID().toString()
                    auctionStat.markAuctionStarted(auctionId, adTypeParam)

                    // Get tokens for RTB adapters
                    val tokens = getTokens(
                        adTypeParam = adTypeParam,
                        adaptersSource = adaptersSource,
                        tokenTimeout = biddingConfig.tokenTimeout
                    )
                    logInfo(TAG, "Tokens available: ${tokens.keys}")

                    // Request auction
                    getAuctionRequest.request(
                        adTypeParam = adTypeParam,
                        auctionId = auctionId,
                        demandAd = demandAd,
                        adapters = filteredAdapterSources.associate {
                            it.demandId.demandId to it.adapterInfo
                        },
                        tokens = tokens,
                    ).mapCatching { auctionData ->
                        _auctionDataResponse = auctionData
                        resultsCollector.serverBiddingFinished(
                            auctionData.adUnits?.filter { it.bidType == BidType.RTB }
                        )
                        resultsCollector.setNoBidInfo(auctionData.noBids)
                        auctionData.printWaterfall(demandAd.adType)

                        val adUnits = auctionData.adUnits ?: emptyList()

                        // Split by BidType
                        val (rtbAdUnits, cpmAdUnits) = adUnits.partition { it.bidType == BidType.RTB }
                        logInfo(TAG, "RTB ads: ${rtbAdUnits.size}, CPM ads: ${cpmAdUnits.size}")

                        /**
                         * Execute RTB auctions
                         */
                        val rtbResults = if (rtbAdUnits.isNotEmpty()) {
                            executeRtbAuction(
                                auctionData = auctionData,
                                demandAd = demandAd,
                                adTypeParam = adTypeParam,
                                rtbAdUnits = rtbAdUnits,
                                tokens = tokens
                            )
                        } else {
                            emptyList()
                        }
                        rtbResults
                            .filter { it.roundStatus == RoundStatus.Successful }
                            .sortedByDescending { it.adSource.getStats().price }
                            .forEach { auctionResult ->
                                logInfo(
                                    TAG,
                                    "Result: ${auctionResult.adSource.getStats().demandId}, price: ${auctionResult.adSource.getStats().price}"
                                )
                                resultsCollector.add(auctionResult)
                                val auctionInfo = getAuctionInfo(auctionData, proceedRoundResults())
                                onResult(auctionResult, auctionInfo)
                            }

                        /**
                         * Execute CPM auctions
                         */
                        val cpmResults =
                            if (cpmAdUnits.isNotEmpty()) {
                                executeCpmAuction(auctionData, demandAd, adTypeParam, cpmAdUnits)
                            } else {
                                emptyList()
                            }
                        // Combine and sort by price
                        cpmResults
                            .filter { it.roundStatus == RoundStatus.Successful }
                            .forEach { auctionResult ->
                                logInfo(
                                    TAG,
                                    "Result: ${auctionResult.adSource.getStats().demandId}, price: ${auctionResult.adSource.getStats().price}"
                                )
                                resultsCollector.add(auctionResult)
                                val auctionInfo = getAuctionInfo(auctionData, proceedRoundResults())
                                onResult(auctionResult, auctionInfo)
                            }

                        // Process stats
                        val statResult = proceedRoundResults()
                        val auctionInfo = getAuctionInfo(auctionData, statResult)

                        // Send auction statistics
                        auctionStat.sendAuctionStats(
                            auctionData = auctionData,
                            roundStat = statResult,
                            demandAd = demandAd,
                        )

                        // Notify for no results
                        if (cpmResults.isEmpty() && rtbResults.isEmpty()) {
                            adTypeParam.activity.runOnUiThread {
                                onFailure(auctionInfo, BidonError.NoAuctionResults)
                            }
                        }

                        // Finish auction
                        state.value = AuctionState.Finished
                        clearData()
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

    private fun filterAdapters(existingResults: List<AuctionResult>): List<Adapter> {
        val existingDemands = existingResults
            .filter { AdCacheStorage.isDemandIdSingleton(it.adSource.demandId) }
            .map { it.adSource.demandId }
        return adaptersSource.adapters.filter { adapter ->
            !existingDemands.contains(adapter.demandId)
        }.also {
            logInfo(
                TAG,
                "Filtered adapters. Existing results: ${existingResults.joinToString { it.adSource.demandId.demandId }}"
            )
            logInfo(
                TAG,
                "Filtered adapters. Adapters after filter: ${it.joinToString { it.demandId.demandId }}"
            )
        }
    }

    private suspend fun executeRtbAuction(
        auctionData: AuctionResponse,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        rtbAdUnits: List<AdUnit>,
        tokens: Map<String, TokenInfo>,
    ): List<AuctionResult> {
        val rtbResultsCollector: ResultsCollector = get()
        rtbResultsCollector.startRound(adTypeParam.pricefloor)
        rtbResultsCollector.serverBiddingStarted()
        rtbResultsCollector.serverBiddingFinished(rtbAdUnits)

        rtbAdUnits.map { adUnit ->
            executeAuction(
                auctionId = auctionData.auctionId,
                auctionConfigurationId = auctionData.auctionConfigurationId ?: 0L,
                auctionConfigurationUid = auctionData.auctionConfigurationUid ?: "",
                externalWinNotificationsEnabled = auctionData.externalWinNotificationsEnabled,
                auctionTimeout = auctionData.auctionTimeout,
                pricefloor = auctionData.pricefloor,
                demandAd = demandAd,
                adTypeParam = adTypeParam,
                adUnits = listOf(adUnit),
                resultsCollector = rtbResultsCollector,
                tokens = tokens,
            )
        }

        val roundResults = rtbResultsCollector.getRoundResults()
        val biddingResult = (roundResults as? RoundResult.Results)?.biddingResult
        return (biddingResult as? BiddingResult.FilledAd)?.results.orEmpty()
    }

    private suspend fun executeCpmAuction(
        auctionData: AuctionResponse,
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        cpmAdUnits: List<org.bidon.sdk.auction.models.AdUnit>,
    ): List<AuctionResult> {
        val cpmResultsCollector: ResultsCollector = get()
        cpmResultsCollector.startRound(adTypeParam.pricefloor)

        executeAuction(
            auctionId = auctionData.auctionId,
            auctionConfigurationId = auctionData.auctionConfigurationId ?: 0L,
            auctionConfigurationUid = auctionData.auctionConfigurationUid ?: "",
            externalWinNotificationsEnabled = auctionData.externalWinNotificationsEnabled,
            auctionTimeout = auctionData.auctionTimeout,
            pricefloor = auctionData.pricefloor,
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            adUnits = cpmAdUnits,
            resultsCollector = cpmResultsCollector,
            tokens = emptyMap(),
        )

        val roundResults = cpmResultsCollector.getRoundResults()
        return (roundResults as? RoundResult.Results)?.networkResults.orEmpty()
    }

    fun cancel() {
        logInfo(TAG, "Trying to cancel auction. Is active: ${job?.isActive}")
        if (job?.isActive == true) {
            job?.cancel()
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
            noBids = auctionData.noBids?.map { it.toAuctionNoBidInfo() },
            adUnits = statResult?.demands?.map { statsAdUnit ->
                AdUnitInfo(
                    demandId = statsAdUnit.demandId,
                    label = statsAdUnit.adUnitLabel,
                    price = statsAdUnit.price,
                    uid = statsAdUnit.adUnitUid,
                    bidType = statsAdUnit.bidType,
                    fillStartTs = statsAdUnit.fillStartTs,
                    fillFinishTs = statsAdUnit.fillFinishTs,
                    status = statsAdUnit.status,
                    ext = statsAdUnit.ext.toString(),
                )
            },
        )
    }

    private suspend fun processAuctionFailed(
        adTypeParam: AdTypeParam,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
        cause: Throwable
    ) {
        val auctionData = _auctionDataResponse
        if (auctionData == null) {
            logInfo(TAG, "No auction data response info.")
            adTypeParam.activity.runOnUiThread {
                onFailure(null, cause)
            }
        } else {
            val statResult = proceedRoundResults()
            val auctionInfo = getAuctionInfo(auctionData, statResult)
            adTypeParam.activity.runOnUiThread {
                onFailure(auctionInfo, cause)
            }
        }
        state.value = AuctionState.Finished
        clearData()
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

    companion object {
        private const val TAG = "AlexAuction"
    }
}
