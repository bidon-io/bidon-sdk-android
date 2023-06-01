package org.bidon.sdk.auction.usecases

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.AdSourceType
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
internal interface ConductBiddingAuctionUseCase {
    /**
     * @param participantIds Bidding Demand Ids
     */
    suspend fun invoke(
        context: Context,
        biddingSources: List<AdSourceType.Bidding<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        bidfloor: Double,
        round: Round
    ): DeferredAdEvent?
}

internal class ConductBiddingAuctionUseCaseImpl(
    private val bidRequestUseCase: BidRequestUseCase
) : ConductBiddingAuctionUseCase {

    override suspend fun invoke(
        context: Context,
        biddingSources: List<AdSourceType.Bidding<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        bidfloor: Double,
        round: Round
    ): DeferredAdEvent? {
        return withTimeoutOrNull(round.timeoutMs) {
            logInfo(Tag, "biddingSources: $biddingSources")
            logInfo(Tag, "participantIds: $participantIds")
            val participants = biddingSources.filter {
                (it as AdSource<*>).demandId.demandId in participantIds
            }
            logInfo(Tag, "participants: $participants")
            val tokens = participants.mapNotNull { adSource ->
                adSource.getToken(context)?.let { token ->
                    (adSource as AdSource<*>).demandId to token
                }
            }
            logInfo(Tag, "tokens: $tokens")
            val bidResponse = bidRequestUseCase.invoke(
                adTypeParam = adTypeParam,
                tokens = tokens,
                extras = demandAd.getExtras(),
                bidfloor = bidfloor
            ).getOrThrow()

            val bid = bidResponse.seatBid?.bids?.firstOrNull()
            val adSource = biddingSources.first {
                (it as AdSource<*>).demandId.demandId == bidResponse.seatBid?.seat
            }
            val adParam = obtainAdParamByType(
                adSource = adSource as AdSource<AdAuctionParams>,
                adTypeParamData = adTypeParam,
                bidfloor = bidfloor,
                timeout = round.timeoutMs,
                availableLineItemsForDemand = emptyList(),
                payload = bid?.payload,
                onLineItemConsumed = {}
            ).getOrThrow()

            adSource.markBidStarted(adUnitId = adParam.adUnitId)
            adSource.bid(adParam)

            val bidAdEvent = adSource.adEvent.first {
                // wait for results
                it is AdEvent.Bid || it is AdEvent.LoadFailed || it is AdEvent.Expired
            }
            val adEvent = if (bidAdEvent is AdEvent.Bid) {
                adSource.markBidFinished(
                    roundStatus = RoundStatus.Successful,
                    ecpm = adParam.pricefloor
                )
                adSource.markFillStarted()
                adSource.fill()
                val fillAdEvent = adSource.adEvent.first {
                    // wait for results
                    it is AdEvent.Fill || it is AdEvent.LoadFailed || it is AdEvent.Expired
                }
                if (fillAdEvent is AdEvent.Fill) {
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.Successful,
                        ecpm = fillAdEvent.ad.ecpm
                    )
                } else {
                    val (roundStatus, cause) = when (fillAdEvent) {
                        is AdEvent.Expired -> RoundStatus.NoFill to BidonError.Expired(adSource.demandId)
                        is AdEvent.LoadFailed -> fillAdEvent.cause.asRoundStatus() to fillAdEvent.cause
                        else -> error("unexpected")
                    }
                    logError(Tag, "Failed to fill: ${adSource.demandId}", cause)
                    adSource.markFillFinished(
                        roundStatus = roundStatus,
                        ecpm = bidAdEvent.result.ecpm
                    )
                }
                fillAdEvent
            } else {
                adSource.markBidFinished(
                    roundStatus = RoundStatus.NoBid,
                    ecpm = bidfloor
                )
                bidAdEvent
            }
            DeferredAdEvent(adEvent, adSource)
        }
    }

    private fun obtainAdParamByType(
        adSource: AdSource<AdAuctionParams>,
        adTypeParamData: AdTypeParam,
        bidfloor: Double,
        timeout: Long,
        availableLineItemsForDemand: List<LineItem>,
        onLineItemConsumed: (LineItem) -> Unit,
        payload: String?
    ): Result<AdAuctionParams> = when (adSource) {
        is AdSource.Banner -> {
            check(adTypeParamData is AdTypeParam.Banner)
            adSource.getAuctionParams(
                activity = adTypeParamData.activity,
                pricefloor = bidfloor,
                timeout = timeout,
                lineItems = availableLineItemsForDemand,
                bannerFormat = adTypeParamData.bannerFormat,
                onLineItemConsumed = onLineItemConsumed,
                containerWidth = adTypeParamData.containerWidth,
                payload = payload,
            )
        }

        is AdSource.Interstitial -> {
            check(adTypeParamData is AdTypeParam.Interstitial)
            adSource.getAuctionParams(
                pricefloor = bidfloor,
                timeout = timeout,
                lineItems = availableLineItemsForDemand,
                activity = adTypeParamData.activity,
                onLineItemConsumed = onLineItemConsumed,
                payload = payload,
            )
        }

        is AdSource.Rewarded -> {
            check(adTypeParamData is AdTypeParam.Rewarded)
            adSource.getAuctionParams(
                pricefloor = bidfloor,
                timeout = timeout,
                lineItems = availableLineItemsForDemand,
                activity = adTypeParamData.activity,
                onLineItemConsumed = onLineItemConsumed,
                payload = payload,
            )
        }
    }
}

private const val Tag = "ConductBiddingAuctionUseCase"