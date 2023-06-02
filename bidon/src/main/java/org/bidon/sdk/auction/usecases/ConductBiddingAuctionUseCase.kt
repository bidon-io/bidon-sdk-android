package org.bidon.sdk.auction.usecases

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdLoadingType
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.BiddingDemandId
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
        biddingSources: List<AdLoadingType.Bidding<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        bidfloor: Double,
        auctionId: String,
        round: Round,
        auctionConfigurationId: Int?,
    ): DeferredAdEvent?
}

internal class ConductBiddingAuctionUseCaseImpl(
    private val bidRequestUseCase: BidRequestUseCase
) : ConductBiddingAuctionUseCase {

    override suspend fun invoke(
        context: Context,
        biddingSources: List<AdLoadingType.Bidding<AdAuctionParams>>,
        participantIds: List<String>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        bidfloor: Double,
        auctionId: String,
        round: Round,
        auctionConfigurationId: Int?,
    ): DeferredAdEvent? {
        return withTimeoutOrNull(round.timeoutMs) {
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
                bidfloor = bidfloor,
                auctionId = auctionId,
                roundId = round.id,
                auctionConfigurationId = auctionConfigurationId,
            ).getOrElse {
                return@withTimeoutOrNull DeferredAdEvent(
                    adEvent = AdEvent.LoadFailed(BidonError.NoBid(BiddingDemandId)),
                    adSource = null
                )
            }

            val bid = bidResponse.bid
            val adSource = biddingSources.first {
                (it as AdSource<*>).demandId.demandId == bid?.demandId
            }
            val adParam = (adSource as AdSource<AdAuctionParams>).getAuctionParam(
                AdAuctionParamSource(
                    activity = adTypeParam.activity,
                    pricefloor = bidfloor,
                    timeout = round.timeoutMs,
                    payload = bid?.payload,
                    optBannerFormat = (adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                )
            ).getOrElse {
                return@withTimeoutOrNull DeferredAdEvent(
                    adEvent = AdEvent.LoadFailed(BidonError.NoRoundResults),
                    adSource = null
                )
            }

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
}

private const val Tag = "ConductBiddingAuctionUseCase"