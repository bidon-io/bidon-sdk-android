package org.bidon.sdk.auction.usecases.impl

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
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
import org.bidon.sdk.auction.usecases.ConductNetworkRoundUseCase
import org.bidon.sdk.auction.usecases.models.NetworksResult
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.stats.models.asRoundStatus
//TODO need to request CPM and BID adUnits here
@Suppress("UNCHECKED_CAST")
internal class ConductNetworkRoundUseCaseImpl : ConductNetworkRoundUseCase {
    override fun invoke(
        context: Context,
        networkSources: List<Mode.Network>,
        adTypeParam: AdTypeParam,
        demandAd: DemandAd,
        adUnits: List<AdUnit>,
        pricefloor: Double,
        scope: CoroutineScope,
        timeoutMs: Long,
        resultsCollector: ResultsCollector,
    ): NetworksResult {
        val mutableLineItems = adUnits.toMutableList()
        runCatching {
            logInfo(TAG, "participants: $networkSources")
            val results = mutableListOf<AuctionResult.Network>()
            networkSources.onEach { adSource ->
                scope.launch {
                    adSource as AdSource<AdAuctionParams>
                    val availableAdUnitsForDemand = mutableLineItems.filter { it.demandId == adSource.demandId.demandId }

                    logInfo(
                        tag = TAG,
                        message = "Adapter ${adSource.demandId.demandId} starts fill. " +
                            "PriceFloor=$pricefloor. LineItems: $availableAdUnitsForDemand."
                    )
                    val adEvent = loadAd(
                        adSource = adSource,
                        adTypeParam = adTypeParam,
                        pricefloor = pricefloor,
                        timeoutMs = timeoutMs,
                        availableAdUnitsForDemand = availableAdUnitsForDemand,
                        onAdUnitsConsumed = { lineItem ->
                            mutableLineItems.remove(lineItem)
                        }
                    )
                    results.add(AuctionResult.Network(
                        adSource = adSource,
                        roundStatus = when (adEvent) {
                            is AdEvent.Fill -> RoundStatus.Successful
                            is AdEvent.Expired -> RoundStatus.NoFill
                            is AdEvent.LoadFailed -> adEvent.cause.asRoundStatus()
                            else -> error("unexpected: $adEvent")
                        }
                    ).also {
                        resultsCollector.add(it)
                    })
                }
            }
            return NetworksResult(
                results = results,
                remainingAdUnits = mutableLineItems.toList()
            )
        }.getOrNull() ?: run {
            return NetworksResult(
                results = emptyList(),
                remainingAdUnits = adUnits
            )
        }
    }

    private suspend fun loadAd(
        adSource: Mode,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        timeoutMs: Long,
        availableAdUnitsForDemand: List<AdUnit>,
        onAdUnitsConsumed: (AdUnit) -> Unit
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
                    adUnits = availableAdUnitsForDemand,
                    onAdUnitsConsumed = onAdUnitsConsumed
                )
            ).getOrNull() ?: run {
                return@withTimeoutOrNull AdEvent.LoadFailed(BidonError.NoAppropriateAdUnitId)
            }

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
