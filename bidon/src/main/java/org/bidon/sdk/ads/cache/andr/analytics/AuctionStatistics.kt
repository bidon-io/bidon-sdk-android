package org.bidon.sdk.ads.cache.andr.analytics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.ext.asAdRequestBody
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.InterstitialRequest
import org.bidon.sdk.auction.models.RewardedRequest
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.ResultBody
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.StatsAdUnit
import org.bidon.sdk.stats.models.StatsRequestBody
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class AuctionStatistics(
    private val tag: String,
    private val ioDispatcher: CoroutineDispatcher,
    private val statsRequest: StatsRequestUseCase,
    private val resolver: AuctionResolver,
) : AuctionStat {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var auctionId: String = ""

    private var auctionStartTs: Long = 0L

    private var bannerRequestBody: BannerRequest? = null

    private var interstitialRequestBody: InterstitialRequest? = null

    private var rewardedRequestBody: RewardedRequest? = null

    private var winner: AuctionResult? = null

    override fun markAuctionStarted(
        auctionId: String,
        adTypeParam: AdTypeParam,
    ) {
        this.auctionId = auctionId
        this.auctionStartTs = SystemTimeNow

        val (banner, interstitial, rewarded) = adTypeParam.asAdRequestBody()

        this.bannerRequestBody = banner
        this.interstitialRequestBody = interstitial
        this.rewardedRequestBody = rewarded
    }

    override fun markAuctionCanceled() {
    }

    override suspend fun addRoundResults(result: RoundResult.Results): RoundStat {
        val roundResults = resolver.sortWinners(result.getAuctionResults())

        val roundWinner =
            updateWinnerIfNeed(roundResults.firstOrNull { it.roundStatus == RoundStatus.Successful })
        val roundWinnerAdSource = roundWinner?.adSource
        val roundWinnerDemandId = roundWinnerAdSource?.demandId
        val roundWinnerPrice = roundWinnerAdSource?.getStats()?.price

        logInfo(tag, "Winner: $roundWinnerDemandId:$roundWinnerPrice")

        val results: List<StatsAdUnit> =
            roundResults
                .map { it.asStatsAdUnit() }
                .map {
                    if (it.status == RoundStatus.Successful.code) {
                        // Cache path: all successfully loaded ads are WIN (they all go to cache)
                        it.copy(status = RoundStatus.Win.code)
                    } else {
                        it
                    }
                }

        return RoundStat(
            auctionId,
            result.pricefloor,
            results,
            result.noBidsInfo,
            roundWinnerDemandId,
            roundWinnerPrice
        )
    }

    private fun AuctionResult.asStatsAdUnit(): StatsAdUnit =
        when (this) {
            is AuctionResult.Network -> {
                val bidStat = adSource.getStats()
                StatsAdUnit(
                    bidStat.demandId.demandId,
                    roundStatus.code,
                    bidStat.price,
                    null,
                    null,
                    BidType.CPM.code,
                    bidStat.fillStartTs,
                    bidStat.fillFinishTs,
                    bidStat.adUnit?.uid,
                    bidStat.adUnit?.label,
                    roundStatus.getStatusMessage(),
                    bidStat.adUnit?.timeout,
                    bidStat.adUnit?.extra
                )
            }

            is AuctionResult.Bidding -> {
                val bidStat = adSource.getStats()
                StatsAdUnit(
                    bidStat.demandId.demandId,
                    roundStatus.code,
                    bidStat.price,
                    bidStat.tokenInfo?.tokenStartTs,
                    bidStat.tokenInfo?.tokenFinishTs,
                    BidType.RTB.code,
                    bidStat.fillStartTs,
                    bidStat.fillFinishTs,
                    bidStat.adUnit?.uid,
                    bidStat.adUnit?.label,
                    roundStatus.getStatusMessage(),
                    bidStat.adUnit?.timeout,
                    bidStat.adUnit?.extra
                )
            }

            is AuctionResult.AuctionFailed -> {
                StatsAdUnit(
                    adUnit.demandId,
                    roundStatus.code,
                    adUnit.pricefloor,
                    tokenInfo?.tokenStartTs,
                    tokenInfo?.tokenFinishTs,
                    adUnit.bidType.code,
                    null,
                    null,
                    adUnit.uid,
                    adUnit.label,
                    roundStatus.getStatusMessage(),
                    adUnit.timeout,
                    adUnit.extra
                )
            }
        }

    override fun sendAuctionStats(
        auctionData: AuctionResponse,
        roundStat: RoundStat?,
        demandAd: DemandAd
    ): StatsRequestBody? {
        val winnerAdSource = winner?.adSource
        val roundResults =
            roundStat?.copy(
                auctionId = auctionId,
                pricefloor = auctionData.pricefloor,
                winnerDemandId = winnerAdSource?.demandId,
                winnerPrice = winnerAdSource?.getStats()?.price,
            )

        logInfo(tag, "Sending stats: auctionId=$auctionId, winner=${winnerAdSource?.demandId}")

        val statsRequestBody =
            roundResults?.asStatsRequestBody(
                auctionId,
                auctionData.auctionConfigurationId ?: -1,
                auctionData.auctionConfigurationUid ?: "",
                auctionStartTs,
                SystemTimeNow
            )

        scope.launch(ioDispatcher) {
            statsRequest(statsRequestBody, demandAd)
        }

        return statsRequestBody
    }

    private fun updateWinnerIfNeed(roundWinner: AuctionResult?): AuctionResult? {
        if (roundWinner == null) {
            return winner
        }

        val currentPrice = winner?.adSource?.getStats()?.price ?: 0.0
        return if (currentPrice < roundWinner.adSource.getStats().price) {
            this.winner = roundWinner
            roundWinner
        } else {
            winner
        }
    }

    private fun getResultBody(
        auctionStartTs: Long,
        auctionFinishTs: Long
    ): ResultBody {
        val isSucceed = winner?.roundStatus == RoundStatus.Successful
        val stat = winner?.adSource?.getStats()
        return ResultBody(
            if (isSucceed) "SUCCESS" else "FAIL",
            stat?.demandId?.demandId.takeIf { isSucceed },
            stat?.bidType?.code,
            stat?.price.takeIf { isSucceed },
            stat?.adUnit?.uid,
            stat?.adUnit?.label,
            auctionStartTs,
            auctionFinishTs,
            bannerRequestBody,
            interstitialRequestBody,
            rewardedRequestBody
        )
    }

    private fun RoundStat.asStatsRequestBody(
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        auctionStartTs: Long,
        auctionFinishTs: Long,
    ): StatsRequestBody =
        StatsRequestBody(
            auctionId,
            auctionConfigurationId,
            auctionConfigurationUid,
            pricefloor,
            demands,
            getResultBody(auctionStartTs, auctionFinishTs)
        )

    private fun RoundStatus.getStatusMessage() =
        when (this) {
            is RoundStatus.UnspecifiedException -> errorMessage
            is RoundStatus.IncorrectAdUnit -> errorMessage
            else -> null
        }
}
