package org.bidon.sdk.stats.usecases

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.stats.DemandStat
import org.bidon.sdk.stats.RoundStat
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.SdkDispatchers

/**
 * Created by Aleksei Cherniaev on 05/06/2023.
 */
internal interface SendStatisticsUseCase {
    operator fun invoke(
        demandAd: DemandAd,
        auctionResponse: AuctionResponse,
        auctionStartTs: Long,
        auctionFinishTs: Long,
        statsAuctionResults: List<AuctionResult>,
        statsRound: List<RoundStat>,
    )
}

internal class SendStatisticsUseCaseImpl(
    private val statsRequest: StatsRequestUseCase,
) : SendStatisticsUseCase {

    private val scope: CoroutineScope by lazy {
        CoroutineScope(SdkDispatchers.IO)
    }

    override fun invoke(
        demandAd: DemandAd,
        auctionResponse: AuctionResponse,
        auctionStartTs: Long,
        auctionFinishTs: Long,
        statsAuctionResults: List<AuctionResult>,
        statsRound: List<RoundStat>,
    ) {
        scope.launch(SdkDispatchers.Default) {
            val bidStats = statsAuctionResults.map {
                (it.adSource as StatisticsCollector).buildBidStatistic()
            }
            // prepare data
            val soundResults = statsRound.map { roundStat ->
                val errorDemandStat = roundStat.demands
                val succeedDemandStat = bidStats.filter { it.roundId == roundStat.roundId }
                    .map { bidStat ->
                        DemandStat(
                            roundStatus = requireNotNull(bidStat.roundStatus),
                            demandId = bidStat.demandId,
                            bidStartTs = bidStat.bidStartTs,
                            bidFinishTs = bidStat.bidFinishTs,
                            fillStartTs = bidStat.fillStartTs,
                            fillFinishTs = bidStat.fillFinishTs,
                            ecpm = bidStat.ecpm.takeIf {
                                bidStat.roundStatus !in arrayOf(
                                    RoundStatus.NoBid,
                                    RoundStatus.NoAppropriateAdUnitId
                                )
                            },
                            adUnitId = bidStat.adUnitId
                        )
                    }
                roundStat.copy(
                    demands = (succeedDemandStat + errorDemandStat).map { demandStat ->
                        if (demandStat.roundStatus == RoundStatus.Successful) {
                            demandStat.copy(
                                roundStatus = RoundStatus.Loss
                            )
                        } else {
                            demandStat
                        }
                    }
                )
            }

            // send data
            statsRequest.invoke(
                auctionId = auctionResponse.auctionId ?: "",
                auctionConfigurationId = auctionResponse.auctionConfigurationId ?: -1,
                results = soundResults,
                demandAd = demandAd,
                auctionStartTs = auctionStartTs,
                auctionFinishTs = auctionFinishTs
            )
        }
    }
}