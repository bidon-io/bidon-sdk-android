package org.bidon.sdk.auction.usecases.impl

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.bidon.sdk.BidonSdk
import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.Mode
import org.bidon.sdk.adapter.SupportsRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.SystemTimeNow
import org.bidon.sdk.utils.ext.TAG

internal class GetTokensUseCaseImpl : GetTokensUseCase {
    override suspend fun invoke(
        adType: AdType,
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource
    ): List<Pair<String, TokenInfo>> {
        /**
         * Bidding demands auction
         */
        val filteredBiddingAdapters =
            adaptersSource.adapters.onEach(::applyRegulation).toList()
        val biddingAdSources = filteredBiddingAdapters
            .getAdSources(adType)
            .onEach {
                applyParams(
                    adSource = it,
                    adTypeParam = adTypeParam,
                )
            }
            .filterIsInstance<Mode.Bidding>()

        /**
         * Tokens Obtaining
         */
        val tokens = biddingAdSources.getTokens(
            context = get(),
            adTypeParam = adTypeParam,
        )
        logInfo(TAG, "${tokens.size} token(s):")
        tokens.forEachIndexed { index, (demandId, token) ->
            logInfo(TAG, "#$index $demandId {$token}")
        }
        return tokens
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

    private fun applyParams(
        adSource: AdSource<AdAuctionParams>,
        adTypeParam: AdTypeParam,
    ) {
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
        adSource.addAuctionConfigurationId(0)
        adSource.addAuctionConfigurationUid("")
    }

    private fun applyRegulation(adapter: Adapter) {
        (adapter as? SupportsRegulation)?.let { supportsRegulation ->
            logInfo(
                TAG,
                "Applying regulation to ${adapter.demandId.demandId} <- " +
                        "GDPR=${BidonSdk.regulation.gdpr}, " +
                        "COPPA=${BidonSdk.regulation.coppa}, " +
                        "usPrivacyString=${BidonSdk.regulation.usPrivacyString}, " +
                        "gdprConsentString=${BidonSdk.regulation.gdprConsentString}"
            )
            supportsRegulation.updateRegulation(BidonSdk.regulation)
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

    private suspend fun List<Mode.Bidding>.getTokens(
        context: Context,
        adTypeParam: AdTypeParam,
    ): List<Pair<String, TokenInfo>> {
        val adSources = this
        val results = mutableListOf<Pair<String, TokenInfo>>()
        val tokensDeferred = adSources.mapNotNull { adSource ->
            if (adSource !is AdSource<*>) return@mapNotNull null
            adSource to withTimeoutOrNull(tokenTimeout) {
                async(SdkDispatchers.Default) {
                    runCatching {
                        adSource.markTokenStarted()
                        val token = adSource.getToken(
                            context = context,
                            adTypeParam = adTypeParam,
                            //todo check
                            adUnits = listOf(),
                        )
                        adSource.markTokenFinished(
                            status = TokenInfo.Status.SUCCESS.takeIf { token != null }
                                ?: TokenInfo.Status.NO_TOKEN,
                            token = token
                        )
                    }
                }
            }
        }
        tokensDeferred.forEach { (adSource, deferred) ->
            val result = deferred?.await()
            if (result == null) {
                results.add(
                    adSource.demandId.demandId to TokenInfo(
                        token = null,
                        tokenStartTs = adSource.getStats().tokenInfo?.tokenStartTs,
                        tokenFinishTs = SystemTimeNow,
                        status = TokenInfo.Status.TIMEOUT_REACHED.code
                    )
                )
            } else {
                val tokenInfo = adSource.getStats().tokenInfo?.also {
                    results.add(adSource.demandId.demandId to it)
                }
                if (tokenInfo == null) {
                    logError(
                        TAG,
                        "Unexpected result ${adSource.demandId}",
                        Throwable()
                    )
                }
            }
        }
        return results
    }

}

//TODO move out to token_timeout_ms in /config response
private val tokenTimeout: Long = 10000L
