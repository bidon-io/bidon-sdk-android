package org.bidon.sdk.auction.usecases.impl

import kotlinx.coroutines.coroutineScope
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.usecases.ConductAuctionUseCase
import org.bidon.sdk.auction.usecases.ExecuteRoundUseCase
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStatus

internal class ExecuteRoundUseCaseImpl(
    private val adaptersSource: AdaptersSource,
    private val conductAuction: ConductAuctionUseCase,
    private val regulation: Regulation,
) : ExecuteRoundUseCase {
    override suspend fun invoke(
        demandAd: DemandAd,
        auctionResponse: AuctionResponse,
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        adUnits: List<AdUnit>,
        resultsCollector: ResultsCollector,
        onFinish: (remainingLineItems: List<AdUnit>) -> Unit,
    ): Result<List<AuctionResult>> = coroutineScope {
        runCatching {

            val adaptersForRequest = adaptersSource.adapters
                .filter { it.demandId.demandId in adUnits.map { it.demandId } }
                .onEach(::applyRegulation)

            resultsCollector.serverBiddingFinished(adUnits.filter { it.bidType == BidType.RTB })

            for (i in adUnits.indices) {
                val adUnit = adUnits[i]
                val adSource = adaptersForRequest
                    .filter { it.demandId.demandId == adUnit.demandId }
                    .getAdSources(demandAd.adType)
                    .firstOrNull()

                if (adSource != null) {
                    applyParams(
                        adSource = adSource,
                        adTypeParam = adTypeParam,
                        auctionResponse = auctionResponse,
                        demandAd = demandAd,
                        roundPricefloor = pricefloor,
                        auctionPricefloor = auctionResponse.pricefloor ?: 0.0,
                    )

                    val auctionResult = conductAuction.invoke(
                        adSource = adSource,
                        adTypeParam = adTypeParam,
                        adUnit = adUnit,
                        priceFloor = pricefloor,
                        timeoutMs = auctionResponse.auctionTimeout
                    )?.also {
                        resultsCollector.add(it)
                    }
                    if (auctionResult?.roundStatus == RoundStatus.Successful) {
                        if (!shouldRequestNext(auctionResult = auctionResult, adUnits = adUnits, currentPosition = i)) {
                            break
                        }
                    }
                } else {
                    logInfo(TAG, "AdAdapter ${adUnit.demandId} not found")
                }
            }

            /**
             * Collecting results
             */
            resultsCollector.getRoundResults()
                .let { roundResult ->
                    (roundResult as? RoundResult.Results)?.let {
                        it.networkResults + (it.biddingResult as? BiddingResult.FilledAd)?.results.orEmpty()
                    }.orEmpty()
                }.mapIndexed { index, result ->
                    // TODO: take adSource.demandId from LOSE
//                    val type = "Bidding".takeIf { result is AuctionResult.Bidding } ?: "DSP"
//                    val details =
//                        "$type ${result.adSource.demandId.demandId}, ${result.adSource.getStats()}"
//                    logInfo(TAG, "Round result #$index. $details")
                    result
                }.let {
                    logInfo(TAG, "Round finished with ${it.size} results: $it")
                    it
                }
        }.onFailure {
            logError(TAG, "Failed to execute round", it)
        }
    }

    private fun shouldRequestNext(
        auctionResult: AuctionResult,
        currentPosition: Int,
        adUnits: List<AdUnit>
    ): Boolean {
        //TODO doesn`t receive actual, cause stats did not updated
        val currentEcpm = auctionResult.adSource.getStats().ecpm
        val nextEcpm = if (currentPosition + 1 < adUnits.size) adUnits[currentPosition + 1].pricefloor else 0.0
        return currentEcpm < nextEcpm
    }

    private fun applyParams(
        adSource: AdSource<AdAuctionParams>,
        adTypeParam: AdTypeParam,
        auctionResponse: AuctionResponse,
        demandAd: DemandAd,
        roundPricefloor: Double,
        auctionPricefloor: Double,
    ) {
        adSource.addRoundInfo(
            auctionId = auctionResponse.auctionId,
            demandAd = demandAd,
            roundPricefloor = roundPricefloor,
            auctionPricefloor = auctionPricefloor,
        )
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
        adSource.addAuctionConfigurationId(auctionResponse.auctionConfigurationId ?: 0)
        adSource.addAuctionConfigurationUid(auctionResponse.auctionConfigurationUid ?: "")
        adSource.addExternalWinNotificationsEnabled(auctionResponse.externalWinNotificationsEnabled)
    }

    private fun applyRegulation(adapter: Adapter) {
        (adapter as? SupportsRegulation)?.let { supportsRegulation ->
            logInfo(
                TAG,
                "Applying regulation to ${adapter.demandId.demandId} <- " +
                        "GDPR=${regulation.gdpr}, " +
                        "COPPA=${regulation.coppa}, " +
                        "usPrivacyString=${regulation.usPrivacyString}, " +
                        "gdprConsentString=${regulation.gdprConsentString}"
            )
            supportsRegulation.updateRegulation(regulation)
        }
    }

    private fun List<Adapter>.getAdSources(adType: AdType): List<AdSource<AdAuctionParams>> =
        when (adType) {
            AdType.Interstitial -> {
                this.filterIsInstance<AdProvider.Interstitial<AdAuctionParams>>()
                    .mapNotNull { adapter ->
                        runCatching {
                            adapter.interstitial()
                                .apply { addDemandId((adapter as Adapter).demandId) }
                        }.onFailure {
                            logError(TAG, "Failed to create interstitial ad source", it)
                        }.getOrNull()
                    }
            }

            AdType.Rewarded -> {
                this.filterIsInstance<AdProvider.Rewarded<AdAuctionParams>>()
                    .mapNotNull { adapter ->
                        runCatching {
                            adapter.rewarded().apply { addDemandId((adapter as Adapter).demandId) }
                        }.onFailure {
                            logError(TAG, "Failed to create rewarded ad source", it)
                        }.getOrNull()
                    }
            }

            AdType.Banner -> {
                this.filterIsInstance<AdProvider.Banner<AdAuctionParams>>().mapNotNull { adapter ->
                    runCatching {
                        adapter.banner().apply { addDemandId((adapter as Adapter).demandId) }
                    }.onFailure {
                        logError(TAG, "Failed to create banner ad source", it)
                    }.getOrNull()
                }
            }
        }

    private fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType {
        return when (this) {
            is AdTypeParam.Banner -> {
                StatisticsCollector.AdType.Banner(
                    format = when (bannerFormat) {
                        BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                        BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                        BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                        BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                    }
                )
            }

            is AdTypeParam.Interstitial -> StatisticsCollector.AdType.Interstitial
            is AdTypeParam.Rewarded -> StatisticsCollector.AdType.Rewarded
        }
    }
}

private const val TAG = "ExecuteRoundUseCase"
