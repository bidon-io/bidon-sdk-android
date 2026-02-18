package org.bidon.sdk.ads.cache.denis.stats

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

/**
 * Denis cache-specific AuctionStat that marks ALL successfully loaded cached ads as WIN.
 *
 * In Denis ad caching, all loaded ads go to cache and can be shown later.
 * Unlike the classic [AuctionStatImpl], this implementation converts ALL Successful → WIN
 * (not just the best one), because every cached ad is a "winner" in caching context.
 */
internal class CacheAuctionStat(
    private val statsRequest: StatsRequestUseCase,
    private val resolver: AuctionResolver,
) : AuctionStat {
    private var auctionStartTs: Long = 0L
    private val scope: CoroutineScope get() = CoroutineScope(SdkDispatchers.IO)

    private var auctionId: String = ""
    private var bannerRequestBody: BannerRequest? = null
    private var interstitialRequestBody: InterstitialRequest? = null
    private var rewardedRequestBody: RewardedRequest? = null

    private var winner: AuctionResult? = null
        get() {
            return if (isAuctionCanceled) {
                return null
            } else {
                field
            }
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

    override suspend fun addRoundResults(result: RoundResult.Results): RoundStat {
        val roundResults = resolver.sortWinners(result.getAuctionResults())

        val roundWinner = updateWinnerIfNeed(
            roundResults
                .firstOrNull { it.roundStatus == RoundStatus.Successful }
                .takeIf { !isAuctionCanceled }
        )

        logInfo(TAG, "Winner: $roundWinner")

        val results: List<StatsAdUnit> = roundResults
            .map { it.asStatsAdUnit() }
            .map { statsAdUnit ->
                if (statsAdUnit.status == RoundStatus.Successful.code) {
                    // Cache path: all successfully loaded ads are WIN (they all go to cache)
                    statsAdUnit.copy(status = RoundStatus.Win.code)
                } else {
                    statsAdUnit
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

    private fun AuctionResult.asStatsAdUnit(): StatsAdUnit {
        return when (this) {
            is AuctionResult.Network -> {
                val stat = adSource.getStats()
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = roundStatus.code,
                    price = stat.price,
                    tokenStartTs = null,
                    tokenFinishTs = null,
                    bidType = BidType.CPM.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                    errorMessage = roundStatus.getStatusMessage(),
                    timeout = stat.adUnit?.timeout,
                    ext = stat.adUnit?.extra
                )
            }

            is AuctionResult.Bidding -> {
                val stat = this.adSource.getStats()
                StatsAdUnit(
                    demandId = stat.demandId.demandId,
                    status = roundStatus.code,
                    price = stat.price,
                    tokenStartTs = stat.tokenInfo?.tokenStartTs,
                    tokenFinishTs = stat.tokenInfo?.tokenFinishTs,
                    bidType = BidType.RTB.code,
                    fillStartTs = stat.fillStartTs,
                    fillFinishTs = stat.fillFinishTs,
                    adUnitUid = stat.adUnit?.uid,
                    adUnitLabel = stat.adUnit?.label,
                    errorMessage = roundStatus.getStatusMessage(),
                    timeout = stat.adUnit?.timeout,
                    ext = stat.adUnit?.extra
                )
            }

            is AuctionResult.AuctionFailed ->
                StatsAdUnit(
                    demandId = adUnit.demandId,
                    status = roundStatus.code,
                    price = adUnit.pricefloor,
                    tokenStartTs = tokenInfo?.tokenStartTs,
                    tokenFinishTs = tokenInfo?.tokenFinishTs,
                    bidType = adUnit.bidType.code,
                    fillStartTs = null,
                    fillFinishTs = null,
                    adUnitUid = adUnit.uid,
                    adUnitLabel = adUnit.label,
                    errorMessage = roundStatus.getStatusMessage(),
                    timeout = adUnit.timeout,
                    ext = adUnit.extra
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
                demands = roundStat.demands.map { demandStat ->
                    demandStat.copy(
                        status = getCacheFinalStatus(currentStatus = demandStat.status)
                    )
                },
            )

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

    /**
     * Cache-specific final status: all Successful ads become WIN (they all go to cache).
     * Other statuses (NO_FILL, TIMEOUT, etc.) stay as-is.
     */
    private fun getCacheFinalStatus(currentStatus: String?): String {
        return when {
            currentStatus == RoundStatus.Successful.code -> RoundStatus.Win.code
            currentStatus == null -> "NO_EXPLANATION_AVAILABLE"
            else -> currentStatus
        }
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

private const val TAG = "CacheAuctionStat"
