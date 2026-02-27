package org.bidon.sdk.ads.cache.andr.analytics

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
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.ext.SystemTimeNow

internal class AuctionStatistics(
    private val statsRequest: StatsRequestUseCase,
    private val resolver: AuctionResolver,
) : AuctionStat {

    private var auctionStartTs: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + SdkDispatchers.IO)

    private var auctionId: String = ""
    private var bannerRequestBody: BannerRequest? = null
    private var interstitialRequestBody: InterstitialRequest? = null
    private var rewardedRequestBody: RewardedRequest? = null

    private var winner: AuctionResult? = null

    override fun markAuctionStarted(
        auctionId: String,
        adTypeParam: AdTypeParam
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
            updateWinnerIfNeed(
                roundResults
                    .firstOrNull { it.roundStatus == RoundStatus.Successful }
            )

        logInfo(TAG, "Winner: ${roundWinner?.adSource?.demandId}:${roundWinner?.adSource?.getStats()?.price}")

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
            auctionId = auctionId,
            pricefloor = result.pricefloor,
            winnerDemandId = roundWinner?.adSource?.demandId,
            winnerPrice = roundWinner?.adSource?.getStats()?.price,
            noBids = result.noBidsInfo,
            demands = results,
        )
    }

    private fun AuctionResult.asStatsAdUnit(): StatsAdUnit =
        when (this) {
            is AuctionResult.Network -> {
                val stat = adSource.getStats()
                StatsAdUnit(
                    stat.demandId.demandId,
                    roundStatus.code,
                    stat.price,
                    null,
                    null,
                    BidType.CPM.code,
                    stat.fillStartTs,
                    stat.fillFinishTs,
                    stat.adUnit?.uid,
                    stat.adUnit?.label,
                    roundStatus.getStatusMessage(),
                    stat.adUnit?.timeout,
                    stat.adUnit?.extra
                )
            }

            is AuctionResult.Bidding -> {
                val stat = this.adSource.getStats()
                StatsAdUnit(
                    stat.demandId.demandId,
                    roundStatus.code,
                    stat.price,
                    stat.tokenInfo?.tokenStartTs,
                    stat.tokenInfo?.tokenFinishTs,
                    BidType.RTB.code,
                    stat.fillStartTs,
                    stat.fillFinishTs,
                    stat.adUnit?.uid,
                    stat.adUnit?.label,
                    roundStatus.getStatusMessage(),
                    stat.adUnit?.timeout,
                    stat.adUnit?.extra
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
        val roundResults =
            roundStat?.copy(
                auctionId = auctionId,
                pricefloor = auctionData.pricefloor,
                winnerDemandId = winner?.adSource?.demandId,
                winnerPrice = winner?.adSource?.getStats()?.price,
            )

        logInfo(TAG, "Sending stats: auctionId=$auctionId, winner=${winner?.adSource?.demandId}")
        val statsRequestBody =
            roundResults?.asStatsRequestBody(
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

    private fun updateWinnerIfNeed(roundWinner: AuctionResult?): AuctionResult? {
        if (roundWinner == null) return winner
        val currentPrice = winner?.adSource?.getStats()?.price ?: 0.0
        return if (currentPrice < roundWinner.adSource.getStats().price) {
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
    ): StatsRequestBody =
        StatsRequestBody(
            auctionId = auctionId,
            auctionConfigurationId = auctionConfigurationId,
            result = getResultBody(auctionStartTs, auctionFinishTs),
            adUnits = demands,
            auctionConfigurationUid = auctionConfigurationUid,
            auctionPricefloor = pricefloor
        )

    private fun getResultBody(
        auctionStartTs: Long,
        auctionFinishTs: Long
    ): ResultBody {
        val isSucceed = winner?.roundStatus == RoundStatus.Successful
        val stat = winner?.adSource?.getStats()
        return ResultBody(
            status =
                when {
                    winner?.roundStatus == RoundStatus.Successful -> "SUCCESS"
                    else -> "FAIL"
                },
            winnerDemandId = stat?.demandId?.demandId.takeIf { isSucceed },
            price = stat?.price.takeIf { isSucceed },
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

    private fun RoundStatus.getStatusMessage() =
        when (this) {
            is RoundStatus.UnspecifiedException -> errorMessage
            is RoundStatus.IncorrectAdUnit -> errorMessage
            else -> null
        }
}

private const val TAG = "AuctionStat"
