package org.bidon.sdk.auction.usecases.impl

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.usecases.ConductBiddingRoundUseCase
import org.bidon.sdk.auction.usecases.ConductNetworkRoundUseCase
import org.bidon.sdk.auction.usecases.ExecuteRoundUseCase
import org.bidon.sdk.auction.usecases.models.BiddingResult
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation
import org.bidon.sdk.stats.StatisticsCollector

internal class ExecuteRoundUseCaseImpl(
    private val adaptersSource: AdaptersSource,
    private val conductBiddingAuction: ConductBiddingRoundUseCase,
    private val conductNetworkAuction: ConductNetworkRoundUseCase,
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
        val mutableAdUnits = adUnits.toMutableList()
        runCatching {
            logInfo(TAG, "Round started with adUnits: $mutableAdUnits")
            val roundDeferred = mutableListOf<Deferred<AuctionResult>>()

//            /**
//             * Bidding demands auction
//             */
//            val filteredBiddingAdapters = adaptersSource.adapters.filter {
//                it.demandId.demandId in round.biddingIds
//            }.onEach(::applyRegulation)
//            logInfo(TAG, "$logText bidding adapters [${filteredBiddingAdapters.joinToString { it.demandId.demandId }}]")
//            val biddingAdSources = filteredBiddingAdapters
//                .getAdSources(demandAd.adType)
//                .onEach {
//                    applyParams(
//                        adSource = it,
//                        adTypeParam = adTypeParam,
//                        auctionResponse = auctionResponse,
//                        demandAd = demandAd,
//                        roundPricefloor = pricefloor,
//                        auctionPricefloor = auctionResponse.auctionPricefloor ?: 0.0,
//                    )
//                }
//                .filterIsInstance<Mode.Bidding>()
//            // Start Bidding demands auction
//            val biddingDemands = biddingAdSources.map {
//                (it as AdSource<*>).demandId.demandId
//            }
//            val biddingResultDeferred = if (biddingDemands.intersect(round.biddingIds.toSet()).isNotEmpty()) {
//                async {
//                    conductBiddingAuction.invoke(
//                        context = adTypeParam.activity.applicationContext,
//                        biddingSources = biddingAdSources,
//                        adTypeParam = adTypeParam,
//                        demandAd = demandAd,
//                        bidfloor = pricefloor,
//                        auctionId = auctionResponse.auctionId,
//                        auctionConfigurationId = auctionResponse.auctionConfigurationId,
//                        auctionConfigurationUid = auctionResponse.auctionConfigurationUid,
//                        resultsCollector = resultsCollector,
//                        adUnits = adUnits
//                    )
//                }
//            } else {
//                null
//            }

            /**
             * Regular AdNetwork demands auction
             */
            val adapters = adaptersSource
                .adapters
                .onEach(::applyRegulation)
            logInfo(TAG, "Network adapters [${adapters.joinToString { it.demandId.demandId }}]")
            val networkAdSources = adapters.getAdSources(demandAd.adType)
                .onEach {
                    applyParams(
                        adSource = it,
                        adTypeParam = adTypeParam,
                        auctionResponse = auctionResponse,
                        demandAd = demandAd,
                        roundPricefloor = pricefloor,
                        auctionPricefloor = auctionResponse.auctionPricefloor ?: 0.0,
                    )
                }
                .filterIsInstance<Mode.Network>()

            val networkResults = conductNetworkAuction.invoke(
                context = adTypeParam.activity,
                networkSources = networkAdSources,
                adTypeParam = adTypeParam,
                demandAd = demandAd,
                adUnits = mutableAdUnits,
                pricefloor = pricefloor,
                scope = this@coroutineScope,
                resultsCollector = resultsCollector,
                timeoutMs = auctionResponse.auctionTimeout,
            )
            mutableAdUnits.clear()
            mutableAdUnits.addAll(networkResults.remainingAdUnits)
//            roundDeferred.addAll(networkResults.results) // TODO: 06/06/2024 [glavatskikh]

            /**
             * Wait for results
             */
//            biddingResultDeferred?.await()
            roundDeferred.map { deferred -> deferred.await() }

            /**
             * Collecting results
             */
            resultsCollector.getRoundResults()
                .let { roundResult ->
                    (roundResult as? RoundResult.Results)?.let {
                        it.networkResults + (it.biddingResult as? BiddingResult.FilledAd)?.results.orEmpty()
                    }.orEmpty()
                }.mapIndexed { index, result ->
                    val type = "Bidding".takeIf { result is AuctionResult.Bidding } ?: "DSP"
                    val details = "$type ${result.adSource.demandId.demandId}, ${result.adSource.getStats()}"
                    logInfo(TAG, "Round result #$index. $details")
                    result
                }.let {
                    onFinish.invoke(mutableAdUnits)
                    logInfo(TAG, "Round finished with ${it.size} results: $it")
                    it
                }
        }.onFailure {
            logError(TAG, "Failed to execute round", it)
        }
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

    private fun Set<Adapter>.getAdSources(adType: AdType): List<AdSource<AdAuctionParams>> = when (adType) {
        AdType.Interstitial -> {
            this.filterIsInstance<AdProvider.Interstitial<AdAuctionParams>>().mapNotNull { adapter ->
                runCatching {
                    adapter.interstitial().apply { addDemandId((adapter as Adapter).demandId) }
                }.onFailure {
                    logError(TAG, "Failed to create interstitial ad source", it)
                }.getOrNull()
            }
        }

        AdType.Rewarded -> {
            this.filterIsInstance<AdProvider.Rewarded<AdAuctionParams>>().mapNotNull { adapter ->
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
