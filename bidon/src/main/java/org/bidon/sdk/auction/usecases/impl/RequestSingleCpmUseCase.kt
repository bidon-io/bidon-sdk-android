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
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.RequestSingleCpm
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus

internal class RequestSingleCpmUseCase : RequestSingleCpm {

    override suspend fun invoke(
        context: Context,
        adSource: Mode,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        adUnit: AdUnit,
        priceFloor: Double,
        timeoutMs: Long
    ): AuctionResult.Network? {
        return runCatching {
            logInfo(TAG, "participants: $adSource")
            adSource as AdSource<AdAuctionParams>
            logInfo(
                tag = TAG,
                message = "Adapter ${adSource.demandId.demandId} starts fill. " +
                        "PriceFloor=$priceFloor. LineItems: $adUnit."
            )
            val adEvent = loadAd(
                adSource = adSource,
                adTypeParam = adTypeParam,
                pricefloor = priceFloor,
                timeoutMs = timeoutMs,
                availableAdUnitsForDemand = adUnit,
            )
            AuctionResult.Network(
                adSource = adSource,
                roundStatus = when (adEvent) {
                    is AdEvent.Fill -> RoundStatus.Successful
                    is AdEvent.Expired -> RoundStatus.NoFill
                    is AdEvent.LoadFailed -> adEvent.cause.asRoundStatus()
                    else -> error("unexpected: $adEvent")
                }
            )
        }.getOrNull()
    }

    private suspend fun loadAd(
        adSource: Mode,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        timeoutMs: Long,
        availableAdUnitsForDemand: AdUnit,
    ): AdEvent {
        adSource as AdSource<AdAuctionParams>
        return withTimeoutOrNull(timeoutMs) {
            val adParam = adSource.getAuctionParam(
                AdAuctionParamSource(
                    activity = adTypeParam.activity,
                    timeout = timeoutMs,
                    optBannerFormat = (adTypeParam as? AdTypeParam.Banner)?.bannerFormat,
                    optContainerWidth = (adTypeParam as? AdTypeParam.Banner)?.containerWidth,
                    pricefloor = pricefloor,
                    adUnits = listOf(availableAdUnitsForDemand),
                    onAdUnitsConsumed = { }
                )
            ).getOrNull() ?: run {
                return@withTimeoutOrNull AdEvent.LoadFailed(BidonError.NoAppropriateAdUnitId)
            }

            /**
             * Start loading ad
             */
            /**
             * Start loading ad
             */
            val fillAdEvent = adSource.adEvent
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
                    adSource.markFillFinished(
                        roundStatus = fillAdEvent.cause.asRoundStatus(),
                        ecpm = adParam.price
                    )
                }

                is AdEvent.Expired -> {
                    adSource.markFillFinished(
                        roundStatus = RoundStatus.NoFill,
                        ecpm = fillAdEvent.ad.ecpm
                    )
                }

                else -> error("unexpected")
            }
            fillAdEvent
        } ?: AdEvent.LoadFailed(BidonError.FillTimedOut(adSource.demandId))
    }
}

private const val TAG = "ConductNetworkRoundUseCase"