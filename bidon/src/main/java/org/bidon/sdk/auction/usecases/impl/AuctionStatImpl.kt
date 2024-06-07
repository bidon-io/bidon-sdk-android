package org.bidon.sdk.auction.usecases.impl

import kotlinx.coroutines.CoroutineScope
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
import org.bidon.sdk.auction.usecases.models.BiddingResult
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

    private var winner: AuctionResult? = null
        get() {
            return if (isAuctionCanceled) return null
            else field
        }

    private var roundStat: RoundStat? = null
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

    override suspend fun addRoundResults(result: RoundResult.Results): RoundStat {
        // get, sort results + update winner
        // save stats
        val biddingResults = (result.biddingResult as? BiddingResult.FilledAd)?.results.orEmpty()
        val networkResults = result.networkResults

        val roundResults = resolver.sortWinners(networkResults + biddingResults)

        val roundWinner = roundResults
            .firstOrNull { it.roundStatus == RoundStatus.Successful }
            .takeIf { !isAuctionCanceled }

        val networks: List<StatsAdUnit> = networkResults.mapNotNull { it.asStatsAdUnit() }
        val bidding: List<StatsAdUnit> = biddingResults.mapNotNull { it.asStatsAdUnit() }
        val results: List<StatsAdUnit> = networks + bidding

        val roundStat = RoundStat(
            auctionId = auctionId,
            pricefloor = result.pricefloor,
            winnerDemandId = roundWinner?.adSource?.demandId,
            winnerEcpm = roundWinner?.adSource?.getStats()?.ecpm,
            demands = results,
        )
        this.roundStat = roundStat
        updateWinnerIfNeed(roundWinner)
        return roundStat
    }

    private fun AuctionResult.asStatsAdUnit(): StatsAdUnit? {
        return when (this) {
            is AuctionResult.Network -> {
                val stat = this.adSource.getStats()
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = this.roundStatus.code,
                    price = stat.ecpm.takeEcpmIfPossible(this.roundStatus),
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                )
            }

            is AuctionResult.Bidding -> {
                val stat = this.adSource.getStats()
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = this.roundStatus.code,
                    price = stat.ecpm.takeEcpmIfPossible(this.roundStatus),
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.RTB.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                )
            }

            is AuctionResult.BiddingLose -> {
                StatsAdUnit(
                    demandId = this.adapterName,
                    status = this.roundStatus.code,
                    price = this.ecpm,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.RTB.code,
                    fillStartTs = null,
                    fillFinishTs = null,
                    adUnitUid = null,
                    adUnitLabel = null,
                )
            }

            is AuctionResult.UnknownAdapter -> {
                StatsAdUnit(
                    demandId = this.adapterName,
                    status = this.roundStatus.code,
                    price = null,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = null,
                    fillStartTs = null,
                    fillFinishTs = null,
                    adUnitUid = null,
                    adUnitLabel = null,
                )
            }
        }
    }

    override fun sendAuctionStats(auctionData: AuctionResponse, demandAd: DemandAd): StatsRequestBody? {
        val roundResults =
            roundStat?.copy(
                auctionId = auctionId,
                pricefloor = auctionData.pricefloor,
                winnerDemandId = winner?.adSource?.demandId,
                winnerEcpm = winner?.adSource?.getStats()?.ecpm,
                demands = roundStat?.demands?.map { demandStat ->
                    demandStat?.copy(
                        status = RoundStatus.values().first {
                            it.code == demandStat.status
                        }.getFinalStatus(
                            isWinner = demandStat.demandId == (winner as? AuctionResult.Network)?.adSource?.demandId?.demandId &&
                                    demandStat.adUnitUid == (winner as? AuctionResult.Network)?.adSource?.getStats()?.adUnit?.uid &&
                                    demandStat.price == (winner as? AuctionResult.Network)?.adSource?.getStats()?.ecpm
                        ).code
                    )
                } ?: listOf(),
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

    private fun RoundStatus.getFinalStatus(isWinner: Boolean): RoundStatus {
        return when {
            isWinner -> RoundStatus.Win
            this == RoundStatus.Successful -> RoundStatus.Lose
            else -> this
        }
    }

    private fun updateWinnerIfNeed(roundWinner: AuctionResult?) {
        if (roundWinner == null) return
        val currentEcpm = winner?.adSource?.getStats()?.ecpm ?: 0.0
        if (currentEcpm < roundWinner.adSource.getStats().ecpm) {
            this.winner = roundWinner
        }
    }

    private fun Double?.takeEcpmIfPossible(status: RoundStatus): Double? {
        return this?.takeIf {
            status !in arrayOf(
                RoundStatus.NoBid,
                RoundStatus.NoAppropriateAdUnitId
            )
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
    ): ResultBody {
        val isSucceed = winner?.roundStatus == RoundStatus.Successful
        val stat = winner?.adSource?.getStats()
        logInfo(TAG, "isSucceed=$isSucceed, stat: $stat")
        return ResultBody(
            status = when {
                isAuctionCanceled -> RoundStatus.AuctionCancelled.code
                winner?.roundStatus == RoundStatus.Successful -> "SUCCESS"
                else -> "FAIL"
            },
            winnerDemandId = stat?.demandId?.demandId.takeIf { isSucceed },
            price = stat?.ecpm.takeIf { isSucceed },
            auctionStartTs = auctionStartTs,
            auctionFinishTs = auctionFinishTs,
            bidType = stat?.bidType?.code,
            winnerAdUnitUid = stat?.adUnit?.label,
            winnerAdUnitLabel = stat?.adUnit?.label,
            banner = bannerRequestBody,
            interstitial = interstitialRequestBody,
            rewarded = rewardedRequestBody,
        )
    }
}

private const val TAG = "AuctionStat"
