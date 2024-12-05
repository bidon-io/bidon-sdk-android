package org.bidon.sdk.auction.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.bidon.sdk.adapter.WinLossNotifiable
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.DemandResult
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.models.AuctionResult
import org.bidon.sdk.auction.usecases.models.ServerBiddingResult
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.DemandStatus

internal class ResultsCollectorImpl(
    private val resolver: AuctionResolver
) : ResultsCollector {
    /**
     * Keeps all succeeded auction results
     */
    private val demandResults = MutableStateFlow(listOf<DemandResult>())
    private val auctionResult = MutableStateFlow<AuctionResult>(AuctionResult.Idle)

    override fun startAuction(pricefloor: Double) {
        require(auctionResult.value is AuctionResult.Idle)
        auctionResult.update {
            AuctionResult.Results(
                pricefloor = pricefloor,
                serverBiddingResult = ServerBiddingResult.Idle,
                demandResults = emptyList()
            )
        }
    }

    override fun serverBiddingStarted() {
        auctionResult.update { current ->
            require(current is AuctionResult.Results)
            AuctionResult.Results(
                pricefloor = current.pricefloor,
                serverBiddingResult = ServerBiddingResult.BiddingStarted,
                demandResults = current.demandResults
            )
        }
    }

    override fun serverBiddingFinished(tokens: Map<String, TokenInfo>, noBids: List<AdUnit>?) {
        auctionResult.update { current ->
            require(current is AuctionResult.Results)
            if (current.serverBiddingResult is ServerBiddingResult.BiddingStarted) {
                AuctionResult.Results(
                    pricefloor = current.pricefloor,
                    serverBiddingResult = ServerBiddingResult.BiddingFinished(
                        tokens = tokens,
                        noBids = noBids ?: emptyList()
                    ),
                    demandResults = current.demandResults
                )
            } else {
                logError(TAG, "Unexpected bidding result: ${current.serverBiddingResult}", null)
                current
            }
        }
    }

    override fun add(result: DemandResult) {
        auctionResult.update { current ->
            require(current is AuctionResult.Results)
            AuctionResult.Results(
                pricefloor = current.pricefloor,
                serverBiddingResult = current.serverBiddingResult,
                demandResults = current.demandResults + result,
            )
        }
    }

    override suspend fun finishAuction(pricefloor: Double) {
        val auctionResult = auctionResult.value
        require(auctionResult is AuctionResult.Results)

        val successfulResults = auctionResult.demandResults
            .filter { it.demandStatus == DemandStatus.Successful }
            .filter {
                /**
                 * Received ecpm should not be less then initial one [pricefloor].
                 */
                val isAbovePricefloor = it.adSource.getStats().ecpm >= pricefloor
                if (!isAbovePricefloor) {
                    it.adSource.markBelowPricefloor()
                }
                isAbovePricefloor
            }

        this.demandResults.update {
            resolver
                .sortWinners(it + successfulResults)
                .also { list ->
                    val winner = list.getOrNull(0) ?: return
                    list.drop(ResultsCollector.MaxAuctionResultsAmount)
                        .forEach { auctionResult ->
                            val adSource = auctionResult.adSource
                            /**
                             *  Bidding demands should not be notified (server notifies them).
                             */
                            if (auctionResult !is DemandResult.Bidding && adSource is WinLossNotifiable) {
                                logInfo(TAG, "Notified loss: ${adSource.demandId}")
                                adSource.notifyLoss(
                                    winner.adSource.demandId.demandId,
                                    winner.adSource.getStats().ecpm
                                )
                            }
                            if (auctionResult.demandStatus == DemandStatus.Successful) {
                                adSource.markLoss()
                            }
                            logInfo(TAG, "Destroying loser: ${adSource.demandId}")
                            auctionResult.adSource.destroy()
                        }
                }
                .take(ResultsCollector.MaxAuctionResultsAmount)
        }
    }

    override fun getAll(): List<DemandResult> {
        return demandResults.value
    }

    override fun clear() {
        demandResults.value = emptyList()
        auctionResult.value = AuctionResult.Idle
    }

    override fun getRoundResults(): AuctionResult = auctionResult.value
}

private const val TAG = "ResultsCollector"