package org.bidon.sdk.auction.usecases.impl

import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.adapter.ext.getAdSources
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.ext.asStatisticAdType
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.ExecuteAuctionUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.impl.asBidonErrorOrUnspecified
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
import java.util.LinkedList

internal class ExecuteAuctionUseCaseImpl(
    private val adaptersSource: AdaptersSource,
    private val requestAdUnit: RequestAdUnitUseCase,
    private val regulation: Regulation,
) : ExecuteAuctionUseCase {

    private var adUnitQueue: LinkedList<AdUnit> = LinkedList()

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
        runCatching {
            val result = withTimeoutOrNull(auctionTimeout) {
                adUnitQueue = LinkedList(adUnits)
                logInfo(TAG, "AdUnits for request: ${adUnitQueue.size}")

                while (adUnitQueue.isNotEmpty()) {
                    val adUnit = adUnitQueue.peek()
                    if (adUnit == null) {
                        logInfo(TAG, "All adUnits were requested")
                        break
                    }

                    logInfo(TAG, "Perform load next: \n$adUnit")

                    val tokenInfo = tokens[adUnit.demandId]

                    if (adUnit.pricefloor < pricefloor) {
                        logInfo(
                            TAG,
                            "Request was skipped since the priceFloor: $pricefloor is less than " +
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

                    val adSource = adaptersSource.adapters
                        .find { it.demandId.demandId == adUnit.demandId }
                        ?.also { adapter ->
                            adapter.applyRegulation()
                        }?.getAdSources(demandAd.adType, TAG)
                        ?.also { adSource ->
                            adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
                        }

                    if (adUnit.bidType == BidType.RTB) {
                        tokenInfo?.let {
                            adSource?.setTokenInfo(it)
                        }
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
                            auctionPricefloor = pricefloor,
                        )

                        val auctionResult = requestAdUnit.invoke(
                            adSource = adSource,
                            adTypeParam = adTypeParam,
                            adUnit = adUnit,
                            priceFloor = pricefloor,
                        ).also {
                            resultsCollector.add(it)
                        }

                        val nextRequested = adUnitQueue.poll()
                        if (auctionResult.roundStatus == RoundStatus.Successful &&
                            !shouldRequestNext(auctionResult = auctionResult, next = nextRequested)
                        ) {
                            logInfo(TAG, "Request was skipped since the filled price larger than the next one")
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

                logInfo(TAG, "Auction was finished")
                /**
                 * Collecting results
                 */
                resultsCollector.getRoundResults().let { roundResult ->
                    (roundResult as? RoundResult.Results)?.let {
                        it.networkResults + (it.biddingResult as? BiddingResult.FilledAd)?.results.orEmpty()
                    }.orEmpty()
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

    private fun getBelowPriceFloorResult(adUnit: AdUnit, tokenInfo: TokenInfo?): AuctionResult {
        return when (adUnit.bidType) {
            BidType.RTB -> AuctionResult.AuctionFailed(
                adUnit = adUnit,
                roundStatus = RoundStatus.Lose,
                tokenInfo = tokenInfo,
            )

            BidType.CPM -> AuctionResult.AuctionFailed(
                adUnit = adUnit,
                roundStatus = RoundStatus.BelowPricefloor,
                tokenInfo = null
            )
        }
    }

    private fun shouldRequestNext(
        auctionResult: AuctionResult,
        next: AdUnit?
    ): Boolean {
        if (next == null) {
            return false
        }
        val currentPrice = auctionResult.adSource.getStats().price
        val nextPrice = next.pricefloor
        logInfo(TAG, "Loaded price: $currentPrice, next requested price: $nextPrice")
        return currentPrice < nextPrice
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
}

private const val TAG = "ExecuteAuctionUseCase"