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
import org.bidon.sdk.stats.models.DemandStat
import org.bidon.sdk.stats.models.ResultBody
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.StatsAdUnit
import org.bidon.sdk.stats.models.StatsRequestBody
import org.bidon.sdk.stats.usecases.StatsRequestUseCase
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.ext.SystemTimeNow

private typealias StatRound = org.bidon.sdk.stats.models.Round

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

    private var statsRound: RoundStat? = null
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

        val roundStat = RoundStat(
            auctionId = auctionId,
            pricefloor = result.pricefloor,
            winnerDemandId = roundWinner?.adSource?.demandId,
            winnerEcpm = roundWinner?.adSource?.getStats()?.ecpm,
            demands = result.asStatsAdUnits(),
        )
        statsRound = roundStat
        updateWinnerIfNeed(roundWinner)
        return roundStat
    }

    override fun sendAuctionStats(auctionData: AuctionResponse, demandAd: DemandAd): StatsRequestBody? {
        // prepare data
        //TODO what is this code?
//        val canceledRounds = getNotConductedRoundStats(
//            rounds = auctionData.rounds.orEmpty(),
//            completedRoundIds = statsRounds.map { it.roundId },
//        )
        val roundResults = statsRound?.let { roundStat ->
            roundStat.copy(
                demands = roundStat.demands.map { demandStat ->
                    demandStat.copy(
                        status = RoundStatus.values().first {
                            it.code == demandStat.status
                        }.getFinalStatus(
                            isWinner = demandStat.adUnitUid == (winner as? AuctionResult.Network)?.adSource?.getStats()?.adUnit?.uid &&
                                demandStat.price == (winner as? AuctionResult.Network)?.adSource?.getStats()?.ecpm
                        ).code
                    )
                },
            )
        }
//        + canceledRounds

        // send data
        val statsRequestBody = roundResults?.asStatsRequestBody(
            auctionId = auctionId,
            auctionStartTs = auctionStartTs,
            auctionFinishTs = SystemTimeNow,
            auctionConfigurationId = auctionData.auctionConfigurationId ?: 0L,
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

    private fun RoundResult.Results.asStatsAdUnits(): List<StatsAdUnit> {
        val result: RoundResult.Results = this
//        val cancelledAdUnits = getCancelledDemands(
//            networkResults = result.networkResults
//        )
        return result.networkResults.map { it.asStatsAdUnit() }
//        + cancelledAdUnits
    }

    private fun getCancelledDemands(
        networkResults: List<AuctionResult>
    ): List<DemandStat.Network> {
        if (!isAuctionCanceled) return emptyList()
        val cancelledDemandIds = networkResults.map {
            when (it) {
                is AuctionResult.Network -> it.adSource.demandId.demandId
                is AuctionResult.Bidding -> it.adSource.demandId.demandId
                is AuctionResult.UnknownAdapter -> it.adapterName
                is AuctionResult.BiddingLose -> it.adapterName
            }
        }.toSet()
        return cancelledDemandIds.map {
            DemandStat.Network(
                roundStatusCode = RoundStatus.AuctionCancelled.code,
                demandId = it,
                price = null,
                fillStartTs = null,
                fillFinishTs = null,
                adUnitUid = null,
                adUnitLabel = null,
            )
        }
    }

    private fun updateWinnerIfNeed(roundWinner: AuctionResult?) {
        if (roundWinner == null) return
        val currentEcpm = winner?.adSource?.getStats()?.ecpm ?: 0.0
        if (currentEcpm < roundWinner.adSource.getStats().ecpm) {
            this.winner = roundWinner
        }
    }

    private fun AuctionResult.asStatsAdUnit(): StatsAdUnit {
        return when (this) {
            is AuctionResult.Bidding,
            is AuctionResult.Network -> {
                val stat = this.adSource.getStats()
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = stat.roundStatus?.code,
                    price = stat.ecpm.takeEcpmIfPossible(this.roundStatus),
                    tokenStartTs = stat.tokenInfo?.tokenStartTs ?: 0L,
                    tokenFinishTs = stat.tokenInfo?.tokenFinishTs ?: 0L,
                    bidType = stat.bidType?.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                )
            }
            is AuctionResult.UnknownAdapter -> {
                StatsAdUnit(
                    status = RoundStatus.UnknownAdapter.code,
                    demandId = adapterName,
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
            else -> {
                StatsAdUnit(
                    status = RoundStatus.UnknownAdapter.code,
                    demandId = "NO DEMAND ID",
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

    private fun RoundResult.Results.asDemandStatBidding(): DemandStat.Bidding? {
        val demandError: (RoundStatus) -> DemandStat.Bidding.Bid = {
            DemandStat.Bidding.Bid(
                roundStatusCode = it.code,
                price = null,
                demandId = null,
                fillStartTs = null,
                fillFinishTs = null,
                adUnitUid = null,
                adUnitLabel = null,
                tokenStartTs = null,
                tokenFinishTs = null,
            )
        }

        return when (val br = this.biddingResult) {
            BiddingResult.Idle -> null

            is BiddingResult.NoBid -> {
                DemandStat.Bidding(
                    bidStartTs = br.serverBiddingStartTs,
                    bidFinishTs = br.serverBiddingFinishTs,
                    bids = listOf(demandError(RoundStatus.NoBid))
                )
            }

            is BiddingResult.FilledAd -> {
                DemandStat.Bidding(
                    bidStartTs = br.serverBiddingStartTs,
                    bidFinishTs = br.serverBiddingFinishTs,
                    bids = br.results.map { auctionResult ->

                        when (auctionResult) {
                            is AuctionResult.Bidding -> {
                                val bid = br.bids.first { it.adUnit.demandId == auctionResult.adSource.demandId.demandId }
                                val stat = auctionResult.adSource.getStats()
                                DemandStat.Bidding.Bid(
                                    roundStatusCode = auctionResult.roundStatus.code,
                                    price = bid.price,
                                    demandId = bid.adUnit.demandId,
                                    fillStartTs = stat.fillStartTs,
                                    fillFinishTs = stat.fillFinishTs,
                                    adUnitUid = stat.adUnit?.uid.orEmpty(),
                                    adUnitLabel = stat.adUnit?.label.orEmpty(),
                                    tokenStartTs = stat.tokenInfo?.tokenStartTs,
                                    tokenFinishTs = stat.tokenInfo?.tokenFinishTs,
                                )
                            }

                            is AuctionResult.UnknownAdapter -> {
                                DemandStat.Bidding.Bid(
                                    roundStatusCode = RoundStatus.UnknownAdapter.code,
                                    demandId = auctionResult.adapterName,
                                    fillStartTs = null,
                                    fillFinishTs = null,
                                    price = null,
                                    adUnitUid = null,
                                    adUnitLabel = null,
                                    tokenStartTs = null,
                                    tokenFinishTs = null,
                                )
                            }

                            is AuctionResult.BiddingLose -> {
                                DemandStat.Bidding.Bid(
                                    roundStatusCode = RoundStatus.Lose.code,
                                    demandId = auctionResult.adapterName,
                                    price = auctionResult.ecpm,
                                    fillStartTs = null,
                                    fillFinishTs = null,
                                    adUnitUid = null,
                                    adUnitLabel = null,
                                    tokenStartTs = null,
                                    tokenFinishTs = null,
                                )
                            }

                            is AuctionResult.Network -> error("unexpected")
                        }
                    }
                )
            }

            is BiddingResult.ServerBiddingStarted -> {
                DemandStat.Bidding(
                    bidStartTs = br.serverBiddingStartTs,
                    bidFinishTs = null,
                    bids = listOf(demandError(RoundStatus.AuctionCancelled))
                )
            }

            is BiddingResult.TimeoutReached -> {
                DemandStat.Bidding(
                    bidStartTs = br.serverBiddingStartTs,
                    bidFinishTs = br.serverBiddingFinishTs,
                    bids = listOf(demandError(RoundStatus.BidTimeoutReached))
                )
            }
        }
    }

    private fun RoundStatus.getFinalStatus(isWinner: Boolean): RoundStatus {
        return when {
            this == RoundStatus.Successful && isWinner -> RoundStatus.Win
            this == RoundStatus.Successful -> RoundStatus.Lose
            else -> this
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
            auctionConfigurationUid = auctionConfigurationUid,
            auctionPricefloor = pricefloor,
            result = getResultBody(auctionStartTs, auctionFinishTs),
            adUnits = demands
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
            winnerAdUnitLabel = stat?.adUnit?.label.takeIf { isSucceed },
            winnerAdUnitUid = stat?.adUnit?.uid.takeIf { isSucceed },
            price = stat?.ecpm.takeIf { isSucceed },
            auctionStartTs = auctionStartTs,
            auctionFinishTs = auctionFinishTs,
            bidType = stat?.bidType?.code,
            banner = bannerRequestBody,
            interstitial = interstitialRequestBody,
            rewarded = rewardedRequestBody,
        )
    }
}

private const val TAG = "AuctionStat"
