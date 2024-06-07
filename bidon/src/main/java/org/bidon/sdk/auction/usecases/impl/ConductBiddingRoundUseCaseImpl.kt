package org.bidon.sdk.auction.usecases.impl

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.adapter.AdAuctionParamSource
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdEvent
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.ConductBiddingRoundUseCase
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus

@Suppress("UNCHECKED_CAST")
internal class ConductBiddingRoundUseCaseImpl : ConductBiddingRoundUseCase {

    override suspend fun invoke(
        context: Context,
        biddingSources: List<Mode.Bidding>,
        bids: List<AdUnit>?,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        bidfloor: Double,
        auctionId: String,
        auctionConfigurationId: Long?,
        auctionConfigurationUid: String?,
        timeoutMs: Long,
        resultsCollector: ResultsCollector
    ) {
        runCatching {
            withTimeoutOrNull(timeoutMs) {
                logInfo(TAG, "participants: $biddingSources")
                bids?.let { bids ->
                    fillBids(
                        resultsCollector = resultsCollector,
                        bids = bids,
                        biddingSources = biddingSources,
                        adTypeParam = adTypeParam,
                        roundPricefloor = bidfloor,
                        timeoutMs = timeoutMs
                    )
                } ?: logInfo(TAG, "BidResponse is null. Result: NO BIDS")
            }
        }
    }

    private suspend fun fillBids(
        resultsCollector: ResultsCollector,
        bids: List<AdUnit>,
        biddingSources: List<Mode>,
        adTypeParam: AdTypeParam,
        roundPricefloor: Double,
        timeoutMs: Long,
    ) {
        var filled = false
        bids.forEach { bid ->
            val adSource = biddingSources.first {
                (it as AdSource<*>).demandId.demandId == bid.demandId
            } as AdSource<*>
            if (!filled) {
                adSource.markFillStarted(
                    adUnit = bid,
                    pricefloor = bid.pricefloor
                )
                val fillResult = loadAd(
                    biddingSources = biddingSources,
                    bid = bid,
                    adTypeParam = adTypeParam,
                    roundPricefloor = roundPricefloor,
                    timeoutMs = timeoutMs
                ).also {
                    logInfo(
                        TAG,
                        "fillResult: ${it.roundStatus}, ${(it as? AuctionResult.Bidding)?.adSource}"
                    )
                    if (it.roundStatus == RoundStatus.Successful) {
                        logInfo(TAG, "fillResult: ${it.roundStatus}")
                        filled = true
                    }
                }
                adSource.markFillFinished(
                    roundStatus = fillResult.roundStatus,
                    ecpm = bid.pricefloor
                )
                resultsCollector.add(fillResult)
            } else {
                val lose = AuctionResult.BiddingLose(
                    adapterName = adSource.demandId.demandId,
                    ecpm = bid.pricefloor
                )
                logInfo(TAG, "$lose")
                resultsCollector.add(lose)
            }
        }
    }

    private suspend fun loadAd(
        biddingSources: List<Mode>,
        bid: AdUnit,
        adTypeParam: AdTypeParam,
        roundPricefloor: Double,
        timeoutMs: Long
    ): AuctionResult.Bidding {
        val adSource = biddingSources.first {
            (it as AdSource<*>).demandId.demandId == bid.demandId
        }
        val adParamsSource = AdAuctionParamSource(
            activity = adTypeParam.activity,
            pricefloor = roundPricefloor,
            timeout = timeoutMs,
            optBannerFormat = (adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
            optContainerWidth = (adTypeParam as? AdTypeParam.Banner)?.containerWidth,
            bidResponse = bid
        )
        val adParamResult = (adSource as AdSource<AdAuctionParams>).getAuctionParam(adParamsSource)
        val adParam: AdAuctionParams = adParamResult.getOrNull() ?: run {
            logError(TAG, "No appropriate AdUnit found for ${adSource.demandId}", BidonError.NoAppropriateAdUnitId)
            return AuctionResult.Bidding(
                roundStatus = RoundStatus.NoAppropriateAdUnitId,
                adSource = adSource,
            )
        }

        //TODO UID?
        adSource.addImpressionId(bid.uid)

        /**
         * Start loading ad
         */
        val bidAdEvent = adSource.adEvent
            .onSubscription {
                runCatching {
                    adSource.markFillStarted(adParam.adUnit, adParam.price)
                    adSource.load(adParam)
                }.onFailure {
                    logError(TAG, "Loading failed($adParam): $it", it)
                    adSource.emitEvent(
                        event = AdEvent.LoadFailed(
                            cause = BidonError.NoFill(adSource.demandId)
                        )
                    )
                }
            }.first {
                // Wait for ad-request result
                it is AdEvent.Fill || it is AdEvent.LoadFailed || it is AdEvent.Expired
            }
        return when (bidAdEvent) {
            is AdEvent.LoadFailed,
            is AdEvent.Expired -> {
                AuctionResult.Bidding(
                    roundStatus = RoundStatus.NoFill,
                    adSource = adSource,
                )
            }

            is AdEvent.Fill -> {
                AuctionResult.Bidding(
                    adSource = adSource,
                    roundStatus = RoundStatus.Successful
                )
            }

            else -> {
                error("unexpected")
            }
        }
    }
}

private const val TAG = "ConductBiddingRoundUseCase"
