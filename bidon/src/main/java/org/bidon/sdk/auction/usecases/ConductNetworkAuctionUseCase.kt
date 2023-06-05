package org.bidon.sdk.auction.usecases

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdLoadingType
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.LineItem
import org.bidon.sdk.auction.models.Round
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus

/**
 * Created by Aleksei Cherniaev on 31/05/2023.
 */
internal interface ConductNetworkAuctionUseCase {
    /**
     * @param participantIds Bidding Demand Ids
     */
    suspend fun invoke(
        context: Context,
        networkSources: List<AdLoadingType.Network<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        lineItems: List<LineItem>,
        round: Round,
        pricefloor: Double
    ): DeferredRoundResult
}

internal class ConductNetworkAuctionUseCaseImpl : ConductNetworkAuctionUseCase {
    override suspend fun invoke(
        context: Context,
        networkSources: List<AdLoadingType.Network<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        lineItems: List<LineItem>,
        round: Round,
        pricefloor: Double
    ): DeferredRoundResult = coroutineScope {
        val mutableLineItems = lineItems.toMutableList()
        runCatching {
            val participants = networkSources.filter {
                (it as AdSource<*>).demandId.demandId in participantIds
            }
            logInfo(Tag, "participants: $participants")
            val deferredList = participants.map { adSource ->
                adSource as AdSource<AdAuctionParams>
                val availableLineItemsForDemand = mutableLineItems.filter { it.demandId == adSource.demandId.demandId }
                logInfo(
                    tag = Tag,
                    message = "Round '${round.id}'. Adapter ${adSource.demandId.demandId} starts bidding. " +
                        "PriceFloor=$pricefloor. LineItems: $availableLineItemsForDemand."
                )
                async {
                    PollItem(
                        adEvent = startBidding(
                            adSource = adSource,
                            adTypeParam = adTypeParam,
                            pricefloor = pricefloor,
                            round = round,
                            availableLineItemsForDemand = availableLineItemsForDemand,
                            onLineItemConsumed = { lineItem ->
                                mutableLineItems.remove(lineItem)
                            }
                        ),
                        adSource = adSource,
                    )
                }
            }
            DeferredRoundResult(
                results = deferredList,
                remainingLineItems = mutableLineItems.toList()
            )
        }.getOrNull() ?: run {
            DeferredRoundResult(
                results = emptyList(),
                remainingLineItems = lineItems
            )
        }
    }

    private suspend fun startBidding(
        adSource: AdLoadingType.Network<AdAuctionParams>,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        round: Round,
        availableLineItemsForDemand: List<LineItem>,
        onLineItemConsumed: (LineItem) -> Unit
    ): AdEvent {
        adSource as AdSource<AdAuctionParams>
        return withTimeoutOrNull(round.timeoutMs) {
            val adParam = adSource.obtainAuctionParam(
                AdAuctionParamSource(
                    activity = adTypeParam.activity,
                    timeout = round.timeoutMs,
                    optBannerFormat = (adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                    pricefloor = pricefloor,
                    lineItems = availableLineItemsForDemand,
                    onLineItemConsumed = onLineItemConsumed
                )
            ).getOrNull() ?: run {
                return@withTimeoutOrNull AdEvent.LoadFailed(BidonError.NoAppropriateAdUnitId)
            }

            // BID todo Should we remove it?
            adSource.markBidStarted(adUnitId = adParam.adUnitId)
            adSource.markBidFinished(
                roundStatus = RoundStatus.Successful,
                ecpm = adParam.pricefloor
            )
            // FILL
            adSource.markFillStarted()
            adSource.fill(adParam)
            val fillAdEvent = adSource.adEvent.first {
                // wait for results
                it is AdEvent.Fill || it is AdEvent.LoadFailed || it is AdEvent.Expired
            }
            when (fillAdEvent) {
                is AdEvent.Fill -> {
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.Successful,
                        ecpm = fillAdEvent.ad.ecpm
                    )
                }

                is AdEvent.LoadFailed -> {
                    logError(Tag, "Failed to fill: ${adSource.demandId}", fillAdEvent.cause)
                    adSource.markFillFinished(
                        roundStatus = fillAdEvent.cause.asRoundStatus(),
                        ecpm = adParam.pricefloor
                    )
                }

                is AdEvent.Expired -> {
                    logError(Tag, "Failed to fill: ${adSource.demandId}", BidonError.Expired(adSource.demandId))
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.NoFill,
                        ecpm = fillAdEvent.ad.ecpm
                    )
                }

                else -> error("unexpected")
            }
            fillAdEvent
        } ?: AdEvent.LoadFailed(
            cause = when (adSource.buildBidStatistic().roundStatus) {
                RoundStatus.NoBid -> BidonError.FillTimedOut(adSource.demandId)
                else -> BidonError.BidTimedOut(adSource.demandId)
            }
        )
    }
}

private const val Tag = "ConductNetworkAuctionUseCase"