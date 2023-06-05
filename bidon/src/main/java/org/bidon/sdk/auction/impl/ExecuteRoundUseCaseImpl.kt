package org.bidon.sdk.auction.impl

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdLoadingType
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResult
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.BiddingDemandId
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.auction.usecases.ConductBiddingAuctionUseCase
import org.bidon.sdk.auction.usecases.ConductNetworkAuctionUseCase
import org.bidon.sdk.auction.usecases.PollItem
import org.bidon.sdk.auction.usecases.models.ExecuteRoundUseCase
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus

internal class ExecuteRoundUseCaseImpl(
    private val adaptersSource: AdaptersSource,
    private val conductBiddingAuction: ConductBiddingAuctionUseCase,
    private val conductNetworkAuction: ConductNetworkAuctionUseCase,
) : ExecuteRoundUseCase {
    override suspend fun invoke(
        demandAd: DemandAd,
        auctionResponse: AuctionResponse,
        adTypeParam: AdTypeParam,
        round: Round,
        pricefloor: Double,
        lineItems: List<LineItem>,
        onFinish: (remainingLineItems: List<LineItem>) -> Unit,
    ): Result<List<AuctionResult>> = coroutineScope {
        val mutableLineItems = lineItems.toMutableList()
        runCatching {
            val filteredAdapters = adaptersSource.adapters.filter {
                it.demandId.demandId in (round.demandIds + round.biddingIds)
            }
            (round.demandIds - filteredAdapters.map { it.demandId.demandId }.toSet())
                .takeIf { it.isNotEmpty() }
                ?.let { unknownDemandIds ->
                    logError(
                        tag = Tag,
                        message = "Adapters not found: $unknownDemandIds",
                        error = NoSuchElementException(unknownDemandIds.joinToString())
                    )
                }
            val logText = "Round '${round.id}' started with"
            logInfo(Tag, "$logText adapters [${filteredAdapters.joinToString { it.demandId.demandId }}]")
            logInfo(Tag, "$logText line items: $mutableLineItems")
            val adSources = filteredAdapters.getAdSources(demandAd, round, auctionResponse)
            val roundDeferred = mutableListOf<Deferred<PollItem?>>()

            // Start Bidding demands auction
            if (round.biddingIds.isNotEmpty()) {
                val biddingResultDeferred = async {
                    conductBiddingAuction.invoke(
                        context = adTypeParam.activity.applicationContext,
                        biddingSources = adSources.filterIsInstance<AdLoadingType.Bidding<AdAuctionParams>>(),
                        participantIds = round.biddingIds,
                        adTypeParam = adTypeParam,
                        demandAd = demandAd,
                        bidfloor = pricefloor,
                        auctionId = auctionResponse.auctionId ?: "",
                        round = round,
                        auctionConfigurationId = auctionResponse.auctionConfigurationId
                    ) ?: PollItem(
                        adEvent = AdEvent.LoadFailed(BidonError.NoBid(BiddingDemandId)),
                        adSource = null
                    )
                }
                roundDeferred.add(biddingResultDeferred)
            }

            // Start Regular AdNetwork demands auction
            if (round.demandIds.isNotEmpty()) {
                val networkResults = conductNetworkAuction.invoke(
                    context = adTypeParam.activity,
                    networkSources = adSources.filterIsInstance<AdLoadingType.Network<AdAuctionParams>>(),
                    participantIds = round.demandIds,
                    adTypeParam = adTypeParam,
                    demandAd = demandAd,
                    lineItems = mutableLineItems,
                    round = round,
                    pricefloor = pricefloor
                )
                mutableLineItems.clear()
                mutableLineItems.addAll(networkResults.remainingLineItems)
                roundDeferred.addAll(networkResults.results)
            }

            // Collecting results
            roundDeferred.mapIndexedNotNull { index, deferred ->
                val result = deferred.await() ?: return@mapIndexedNotNull null
                val adSource = result.adSource ?: return@mapIndexedNotNull null
                val adEvent = result.adEvent
                val logRoundTitle = "Round '${round.id}' result #$index(${adSource.demandId.demandId})"
                logInfo(Tag, "$logRoundTitle: $adEvent. Statistics: ${adSource.buildBidStatistic()}")
                AuctionResult(
                    roundStatus = when (adEvent) {
                        is AdEvent.Fill -> RoundStatus.Successful
                        is AdEvent.Expired -> RoundStatus.NoFill
                        is AdEvent.LoadFailed -> adEvent.cause.asRoundStatus()
                        else -> error("unexpected: $adEvent")
                    },
                    ecpm = (adEvent as? AdEvent.Fill)?.ad?.ecpm ?: 0.0,
                    adSource = adSource
                )
            }.also {
                onFinish.invoke(mutableLineItems)
                logInfo(Tag, "Round '${round.id}' finished with ${it.size} results: $it")
            }
        }
    }

    private fun List<Adapter>.getAdSources(
        demandAd: DemandAd,
        round: Round,
        auctionResponse: AuctionResponse
    ) = when (demandAd.adType) {
        AdType.Interstitial -> {
            this.filterIsInstance<AdProvider.Interstitial<AdAuctionParams>>()
                .map {
                    it.interstitial(
                        demandAd = demandAd,
                        roundId = round.id,
                        auctionId = auctionResponse.auctionId ?: ""
                    )
                }
        }

        AdType.Rewarded -> {
            this.filterIsInstance<AdProvider.Rewarded<AdAuctionParams>>().map {
                it.rewarded(
                    demandAd = demandAd,
                    roundId = round.id,
                    auctionId = auctionResponse.auctionId ?: ""
                )
            }
        }

        AdType.Banner -> {
            this.filterIsInstance<AdProvider.Banner<AdAuctionParams>>().map {
                it.banner(
                    demandAd = demandAd,
                    roundId = round.id,
                    auctionId = auctionResponse.auctionId ?: ""
                )
            }
        }
    }
}

private const val Tag = "ExecuteRoundUseCase"
