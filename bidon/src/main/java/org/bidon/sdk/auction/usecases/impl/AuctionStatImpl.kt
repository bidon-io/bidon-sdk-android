package org.bidon.sdk.auction.usecases.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.ext.asAdRequestBody
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.models.InterstitialRequest
import org.bidon.sdk.auction.models.RewardedRequest
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.models.AuctionResult
import org.bidon.sdk.auction.usecases.models.ServerBiddingResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.DemandStatus
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.StatsAdUnit
import org.bidon.sdk.stats.models.StatsRequestBody
import org.bidon.sdk.stats.models.StatsResult
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class AuctionStatImpl(
    private val statsRequest: StatsRequestUseCase,
    private val resolver: AuctionResolver
) : AuctionStat {
    private var auctionStartTs: Long = 0L
    private val scope: CoroutineScope get() = CoroutineScope(SdkDispatchers.IO)

    private var auctionId: String = ""
    private var bannerRequestBody: BannerRequest? = null
    private var interstitialRequestBody: InterstitialRequest? = null
    private var rewardedRequestBody: RewardedRequest? = null

    private var winner: DemandResult? = null
        get() {
            return if (isAuctionCanceled) return null
            else field
        }

    private var isAuctionCanceled = false

    override fun markAuctionStarted(auctionId: String, adTypeParam: AdTypeParam) {
        this.auctionId = auctionId
        this.auctionStartTs = SystemTimeNow
        val (banner, interstitial, rewarded) = adTypeParam.asAdRequestBody()
        this.bannerRequestBody = banner
        this.interstitialRequestBody = interstitial
        this.rewardedRequestBody = rewarded
    }

    override fun markAuctionCanceled() {
        isAuctionCanceled = true
    }

    override suspend fun addRoundResults(result: AuctionResult.Results): RoundStat {
        // get, sort results + update winner
        // save stats
        val demandResults = resolver.sortWinners(result.demandResults)
        val biddingResults = result.serverBiddingResult as? ServerBiddingResult.BiddingFinished

        val roundWinner = updateWinnerIfNeed(
            demandResults
                .firstOrNull { it.demandStatus == DemandStatus.Successful }
                .takeIf { !isAuctionCanceled }
        )

        logInfo(TAG, "Winner: $roundWinner")

        val winnerUuid = roundWinner?.adSource?.getStats()?.adUnit?.uid

        val results: List<StatsAdUnit> = demandResults
            .map { it.asStatsAdUnit(biddingResults?.tokens) }
            // TODO try to find more useful solution, cause after auction ends, for filled ad we
            // receive Successful("INTERNAL_STATUS")
            .map { statsAdUnit ->
                val currentUuid = statsAdUnit.adUnitUid
                if (winnerUuid == currentUuid) {
                    statsAdUnit.copy(
                        demandId = statsAdUnit.demandId,
                        status = DemandStatus.Win.code,
                        price = statsAdUnit.price,
                        tokenStartTs = statsAdUnit.tokenStartTs,
                        tokenFinishTs = statsAdUnit.tokenFinishTs,
                        bidType = statsAdUnit.bidType,
                        fillStartTs = statsAdUnit.fillStartTs,
                        fillFinishTs = statsAdUnit.fillFinishTs,
                        adUnitUid = statsAdUnit.adUnitUid,
                        adUnitLabel = statsAdUnit.adUnitLabel,
                    )
                } else if (statsAdUnit.bidType == BidType.RTB.code &&
                    statsAdUnit.status == DemandStatus.Successful.code
                ) {
                    statsAdUnit.copy(
                        demandId = statsAdUnit.demandId,
                        status = DemandStatus.Lose.code,
                        price = statsAdUnit.price,
                        tokenStartTs = statsAdUnit.tokenStartTs,
                        tokenFinishTs = statsAdUnit.tokenFinishTs,
                        bidType = statsAdUnit.bidType,
                        fillStartTs = statsAdUnit.fillStartTs,
                        fillFinishTs = statsAdUnit.fillFinishTs,
                        adUnitUid = statsAdUnit.adUnitUid,
                        adUnitLabel = statsAdUnit.adUnitLabel,
                    )
                } else {
                    statsAdUnit
                }
            }

        return RoundStat(
            auctionId = auctionId,
            pricefloor = result.pricefloor,
            winnerDemandId = roundWinner?.adSource?.demandId,
            winnerEcpm = roundWinner?.adSource?.getStats()?.ecpm,
            noBids = (result.serverBiddingResult as? ServerBiddingResult.BiddingFinished)?.noBids,
            demands = results,
        )
    }

    private fun DemandResult.asStatsAdUnit(tokens: Map<String, TokenInfo>?): StatsAdUnit {
        return when (this) {
            is DemandResult.Network -> {
                val stat = adSource.getStats()
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = demandStatus.code,
                    price = stat.ecpm,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                    errorMessage = demandStatus.getStatusMessage(),
                    ext = stat.adUnit?.extra
                )
            }

            is DemandResult.Bidding -> {
                val stat = this.adSource.getStats()
                val tokenInfo = tokens?.get(stat.demandId.demandId)
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = demandStatus.code,
                    price = stat.ecpm,
                    tokenStartTs = tokenInfo?.tokenStartTs,
                    tokenFinishTs = tokenInfo?.tokenFinishTs,
                    bidType = BidType.RTB.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                    errorMessage = demandStatus.getStatusMessage(),
                    ext = stat.adUnit?.extra
                )
            }

            is DemandResult.DemandFailed -> {
                val tokenInfo = tokens?.get(adUnit.demandId)
                StatsAdUnit(
                    demandId = adUnit.demandId,
                    status = demandStatus.code,
                    price = adUnit.pricefloor,
                    tokenStartTs = tokenInfo?.tokenStartTs,
                    tokenFinishTs = tokenInfo?.tokenFinishTs,
                    bidType = adUnit.bidType.code,
                    fillStartTs = null,
                    fillFinishTs = null,
                    adUnitUid = adUnit.uid,
                    adUnitLabel = adUnit.label,
                    errorMessage = demandStatus.getStatusMessage(),
                    ext = adUnit.extra
                )
            }
        }
    }

    override fun sendAuctionStats(
        auctionData: AuctionResponse,
        roundStat: RoundStat?,
        demandAd: DemandAd
    ): StatsRequestBody? {
        val roundResults =
            roundStat?.copy(
                auctionId = auctionId,
                pricefloor = auctionData.pricefloor,
                winnerDemandId = winner?.adSource?.demandId,
                winnerEcpm = winner?.adSource?.getStats()?.ecpm,
                demands = roundStat.demands.map { demandStat ->
                    demandStat.copy(
                        status = getFinalStatus(
                            currentStatus = demandStat.status,

                            isWinner = demandStat.demandId == (winner as? DemandResult.Network)?.adSource?.demandId?.demandId &&
                                demandStat.adUnitUid == (winner as? DemandResult.Network)?.adSource?.getStats()?.adUnit?.uid &&
                                demandStat.price == (winner as? DemandResult.Network)?.adSource?.getStats()?.ecpm
                        )
                    )
                },
            )

        // send data
        val statsRequestBody = roundResults?.asStatsRequestBody(
            auctionId = auctionId,
            auctionConfigurationId = auctionData.auctionConfigurationId ?: -1,
            auctionStartTs = auctionStartTs,
            auctionFinishTs = SystemTimeNow,
            auctionConfigurationUid = auctionData.auctionConfigurationUid ?: ""
        )
        scope.launch(SdkDispatchers.Default) {
            statsRequest.invoke(
                statsRequestBody = statsRequestBody,
                demandAd = demandAd,
            )
        }
        return statsRequestBody
    }

    private fun getFinalStatus(currentStatus: String?, isWinner: Boolean): String {
        return when {
            isWinner -> DemandStatus.Win.code
            currentStatus == DemandStatus.Successful.code -> DemandStatus.Lose.code
            currentStatus == null -> DemandStatus.UnspecifiedException("").code
            else -> currentStatus
        }
    }

    private fun updateWinnerIfNeed(roundWinner: DemandResult?): DemandResult? {
        if (roundWinner == null) return winner
        val currentEcpm = winner?.adSource?.getStats()?.ecpm ?: 0.0
        return if (currentEcpm < roundWinner.adSource.getStats().ecpm) {
            this.winner = roundWinner
            roundWinner
        } else {
            winner
        }
    }

    private fun RoundStat.asStatsRequestBody(
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        auctionStartTs: Long,
        auctionFinishTs: Long,
    ): StatsRequestBody {
        return StatsRequestBody(
            auctionId = auctionId,
            auctionConfigurationId = auctionConfigurationId,
            result = getResultBody(auctionStartTs, auctionFinishTs),
            adUnits = demands,
            auctionConfigurationUid = auctionConfigurationUid,
            auctionPricefloor = pricefloor
        )
    }

    private fun getResultBody(
        auctionStartTs: Long,
        auctionFinishTs: Long
    ): StatsResult {
        val isSucceed = winner?.demandStatus == DemandStatus.Successful
        val stat = winner?.adSource?.getStats()
        logInfo(TAG, "isSucceed=$isSucceed, stat: $stat")
        return StatsResult(
            status = when {
                isAuctionCanceled -> StatsResult.Status.AUCTION_CANCELLED.code
                winner?.demandStatus == DemandStatus.Successful -> StatsResult.Status.SUCCESS.code
                else -> StatsResult.Status.FAIL.code
            },
            winnerDemandId = stat?.demandId?.demandId.takeIf { isSucceed },
            price = stat?.ecpm.takeIf { isSucceed },
            auctionStartTs = auctionStartTs,
            auctionFinishTs = auctionFinishTs,
            bidType = stat?.bidType?.code,
            winnerAdUnitUid = stat?.adUnit?.uid,
            winnerAdUnitLabel = stat?.adUnit?.label,
            banner = bannerRequestBody,
            interstitial = interstitialRequestBody,
            rewarded = rewardedRequestBody,
        )
    }

    private fun DemandStatus.getStatusMessage() =
        when (this) {
            is DemandStatus.UnspecifiedException -> errorMessage
            is DemandStatus.IncorrectAdUnit -> errorMessage
            else -> null
        }
}

private const val TAG = "AuctionStat"
