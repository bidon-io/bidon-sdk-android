package org.bidon.sdk.ads.cache.andr.orchestration

import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.andr.execution.AuctionExecutor
import org.bidon.sdk.ads.cache.andr.ext.rtbAdUnits
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.ext.onAny
import java.util.UUID

internal class AuctionRunner(
    private val tag: String,
    private val auctionConfigurator: AuctionConfigurator,
    private val executor: AuctionExecutor,
    private val infoFactory: AuctionInfoFactory,
    private val resultsCollector: ResultsCollector,
    private val statistics: AuctionStat,
) {
    suspend fun run(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
    ): Result<Pair<AuctionInfo, List<AuctionResult>>> {
        logInfo(tag, "Auction started $adTypeParam")

        // Initialize ResultsCollector lifecycle
        initCollector(adTypeParam)

        val auctionId =
            UUID.randomUUID().toString().also {
                statistics.markAuctionStarted(it, adTypeParam)
            }

        return auctionConfigurator
            .configure(auctionId, demandAd, adTypeParam)
            .onSuccess { (response, _) -> startCollector(response) }
            .mapCatching { (response, tokens) -> execute(demandAd, adTypeParam, response, tokens) }
            .onAny { clearCollector() }
    }

    private suspend fun execute(
        demandAd: DemandAd,
        adTypeParam: AdTypeParam,
        response: AuctionResponse,
        tokens: Map<String, TokenInfo>,
    ): Pair<AuctionInfo, List<AuctionResult>> {
        val rawResults = executor.execute(demandAd, adTypeParam, response, tokens)
        val finalResults = finalizeCollector(rawResults)
        val roundStat =
            proceedRoundResults()
                .also { statistics.sendAuctionStats(response, it, demandAd) }
        val info =
            infoFactory
                .create(response, roundStat)
                ?.also { printStatsData(response, roundStat, it) }

        logInfo(tag, "Rounds completed")

        return if (info != null && finalResults.isNotEmpty()) {
            info to finalResults
        } else {
            throw BidonError.NoAuctionResults
        }
    }

    private fun initCollector(adTypeParam: AdTypeParam) {
        clearCollector()
        resultsCollector.startRound(adTypeParam.pricefloor)
        resultsCollector.serverBiddingStarted()
    }

    private fun startCollector(
        response: AuctionResponse,
    ) {
        resultsCollector.serverBiddingFinished(response.rtbAdUnits())
        resultsCollector.setNoBidInfo(response.noBids)
    }

    private fun finalizeCollector(
        rawResults: List<AuctionResult>,
    ): List<AuctionResult> {
        rawResults.forEach(resultsCollector::add)

        val roundResults = resultsCollector.getRoundResults()
        val finalResults =
            if (roundResults is RoundResult.Results) {
                roundResults
                    .getAuctionResults()
                    .filter { it.roundStatus == RoundStatus.Successful }
            } else {
                emptyList()
            }

        logInfo(tag, "Action finished with ${finalResults.size} results")
        finalResults.forEachIndexed { index, auctionResult ->
            logInfo(tag, "Action result #$index: $auctionResult")
        }

        return finalResults
    }

    private fun clearCollector() {
        resultsCollector.clear()
    }

    private suspend fun proceedRoundResults(): RoundStat? =
        (resultsCollector.getRoundResults() as? RoundResult.Results)
            ?.let { statistics.addRoundResults(it) }

    private fun printStatsData(
        auctionData: AuctionResponse,
        statResult: RoundStat?,
        auctionInfo: AuctionInfo,
    ) {
        logInfo(
            tag,
            "Was received: \nAdUnits: ${auctionData.adUnits?.size} \nNoBids: ${auctionData.noBids?.size}" +
                "\nWas sent:\nStats: ${statResult?.demands?.size} \nAuctionInfo AdUnits: ${auctionInfo.adUnits?.size} \n" +
                "AuctionInfo NoBids: ${auctionInfo.noBids?.size}"
        )
    }
}
