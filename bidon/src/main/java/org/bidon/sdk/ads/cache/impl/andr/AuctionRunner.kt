package org.bidon.sdk.ads.cache.impl.andr

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.ext.mapFailure
import org.bidon.sdk.utils.ext.onAny
import java.util.UUID

internal class AuctionRunner(
    private val tag: String,
    private val configurator: AuctionConfigurator,
    private val executor: AuctionExecutor,
    private val resultsCollector: ResultsCollector,
    private val auctionStat: AuctionStat,
    private val auctionInfoFactory: AuctionInfoFactory,
) {
    suspend fun run(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
    ): Result<Pair<AuctionInfo, List<AuctionResult>>> {
        logInfo(tag, "Auction started $adTypeParam")

        val auctionId = UUID.randomUUID().toString()

        auctionStat.markAuctionStarted(auctionId, adTypeParam)

        return configurator
            .configure(auctionId, demandAd, adTypeParam)
            .mapCatching { (response, tokens) -> execute(demandAd, adTypeParam, response, tokens) }
            .mapFailure { BidonError.AuctionFailed(auctionInfoFactory.createFailure(null), it) }
            .onAny { clearCollector() }
    }

    private suspend fun execute(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): Pair<AuctionInfo, List<AuctionResult>> {
        val priceFloor = response.pricefloor

        startCollector(priceFloor, response)

        val context =
            AuctionContext(
                response.auctionId,
                response.auctionConfigurationId ?: 0L,
                response.auctionConfigurationUid ?: "",
                response.externalWinNotificationsEnabled
            )
        val rawResults =
            executor.execute(
                context,
                demandAd,
                adTypeParam,
                response.pricefloor,
                response.auctionTimeout,
                response.adUnits ?: emptyList(),
                tokens
            )

        val results =
            finalizeCollector(rawResults, priceFloor)
                .also { notifyWinLoss(it, response.externalWinNotificationsEnabled) }

        val roundStat =
            proceedRoundResults()
                .also { auctionStat.sendAuctionStats(response, it, demandAd) }

        return if (results.isNotEmpty()) {
            auctionInfoFactory.createSuccess(response, roundStat) to results
        } else {
            throw BidonError.AuctionFailed(
                auctionInfoFactory.createFailure(response, roundStat),
                BidonError.NoAuctionResults
            )
        }
    }

    private fun startCollector(
        priceFloor: Double,
        response: AuctionResponse
    ) {
        resultsCollector.startRound(priceFloor)
        resultsCollector.serverBiddingStarted()
        resultsCollector.serverBiddingFinished(response.adUnits?.filter { it.bidType == BidType.RTB })
        resultsCollector.setNoBidInfo(response.noBids)
    }

    private suspend fun finalizeCollector(
        rawResults: List<AuctionResult>,
        priceFloor: Double,
    ): List<AuctionResult> {
        rawResults.forEach(resultsCollector::add)
        resultsCollector.saveWinners(priceFloor)

        val results =
            resultsCollector
                .getAll()
                .also { logInfo(tag, "Action finished with ${it.size} results") }
        results.forEachIndexed { index, auctionResult ->
            logInfo(tag, "Action result #$index: $auctionResult")
        }
        return results
    }

    private fun clearCollector() {
        resultsCollector.clear()
    }

    private suspend fun proceedRoundResults(): RoundStat? =
        (resultsCollector.getRoundResults() as? RoundResult.Results)
            ?.let { auctionStat.addRoundResults(it) }

    private fun notifyWinLoss(
        finalResults: List<AuctionResult>,
        externalWinNotificationsEnabled: Boolean,
    ) {
        val winner = finalResults.getOrNull(0) ?: return
        val winnerAdSource = winner.adSource

        // For internal statistics
        winnerAdSource.markWin()

        // For AdNetworks - notify winner only if external notifications are disabled
        // Bidding demands should not be notified (server notifies them)
        if (!externalWinNotificationsEnabled) {
            if (winner !is AuctionResult.Bidding && winnerAdSource is WinLossNotifiable) {
                winnerAdSource.notifyWin()
                logInfo(
                    tag,
                    "Notified win to adapter: ${winnerAdSource.demandId} (external_win_notifications=false)"
                )
            } else if (winner is AuctionResult.Bidding) {
                logInfo(
                    tag, "Skipped win notification for bidding demand: ${winnerAdSource.demandId}"
                )
            }
        } else {
            logInfo(
                tag,
                "Skipped win notification to adapter: ${winnerAdSource.demandId} (external_win_notifications=true, will be notified externally)"
            )
        }

        // Notify all losers regardless of external_win_notifications flag
        finalResults
            .drop(1)
            .forEach { loser ->
                val loserAdSource = loser.adSource
                // Bidding demands should not be notified.
                // All losers should be notified immediately regardless of external_win_notifications
                if (loser !is AuctionResult.Bidding && loserAdSource is WinLossNotifiable) {
                    logInfo(tag, "Notified loss: ${loserAdSource.demandId}")
                    loserAdSource.notifyLoss(
                        winnerAdSource.demandId.demandId, winnerAdSource.getStats().price
                    )
                }
                if (loser.roundStatus == RoundStatus.Successful) {
                    loserAdSource.markLoss()
                }
                logInfo(tag, "Loser notified: ${loserAdSource.demandId}")
            }
    }
}
