@file:Suppress("DEPRECATION")

package org.bidon.sdk.ads.cache.impl.vladimir

import org.bidon.sdk.adapter.AdAuctionParams
import org.bidon.sdk.adapter.AdProvider
import org.bidon.sdk.adapter.AdSource
import org.bidon.sdk.adapter.Adapter
import org.bidon.sdk.adapter.AdaptersSource
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.applyRegulation
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.ResultsCollector
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResponse
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.models.BannerRequest
import org.bidon.sdk.auction.models.TokenInfo
import org.bidon.sdk.auction.usecases.AuctionStat
import org.bidon.sdk.auction.usecases.GetAuctionRequestUseCase
import org.bidon.sdk.auction.usecases.GetTokensUseCase
import org.bidon.sdk.auction.usecases.RequestAdUnitUseCase
import org.bidon.sdk.auction.usecases.models.RoundResult
import org.bidon.sdk.bidding.BiddingConfig
import org.bidon.sdk.logs.logging.impl.logError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.stats.StatisticsCollector
import org.bidon.sdk.stats.models.BidType
import org.bidon.sdk.stats.models.RoundStat
import org.bidon.sdk.stats.models.RoundStatus
import org.bidon.sdk.utils.di.get
import java.util.UUID

/**
 * Manages the auction round lifecycle: setup, per-unit loading, and stats collection.
 * Does NOT manage slot insertion — returns results for the caller to handle.
 */
internal class WaterfallLoader(private val demandAd: DemandAd) {

    data class AuctionRound(
        val adTypeParam: AdTypeParam,
        val response: AuctionResponse,
        val tokens: Map<String, TokenInfo>,
    ) {
        val adUnits: List<AdUnit> get() = response.adUnits ?: emptyList()
        val auctionTimeout: Long get() = response.auctionTimeout
    }

    // Lazy DI dependencies
    private val adaptersSource: AdaptersSource by lazy { get() }
    private val getTokens: GetTokensUseCase by lazy { get() }
    private val getAuctionRequest: GetAuctionRequestUseCase by lazy { get() }
    private val requestAdUnit: RequestAdUnitUseCase by lazy { get() }
    private val auctionStat: AuctionStat by lazy { get() }
    private val resultsCollector: ResultsCollector by lazy { get() }
    private val biddingConfig: BiddingConfig by lazy { get() }

    // === Public API ===

    /**
     * Fetches RTB tokens from all adapters. Used to refresh expired tokens
     * before walking remaining units from a previous waterfall.
     */
    suspend fun fetchTokens(adTypeParam: AdTypeParam): Map<String, TokenInfo> {
        logInfo(TAG, "fetchTokens(): fetching fresh RTB tokens...")
        val tokens = getTokens(
            adTypeParam = adTypeParam,
            adaptersSource = adaptersSource,
            tokenTimeout = biddingConfig.tokenTimeout,
        )
        logInfo(TAG, "fetchTokens(): got ${tokens.size} tokens: [${tokens.keys.joinToString()}]")
        return tokens
    }

    /**
     * Creates and starts a new auction round:
     * generates UUID, marks started, fetches tokens, sends server request, initializes collector.
     *
     * @param existingTokens RTB tokens from previous rounds to reuse. These networks will be
     *        excluded from token fetching but their stored tokens will be sent to the server.
     * @param excludedDemandIds Networks to fully exclude — no token fetch, no token sent to server.
     *        Used to exclude networks already cached in slots.
     */
    suspend fun startRound(
        adTypeParam: AdTypeParam,
        pricefloor: Double,
        collectPricefloor: Double = pricefloor,
        existingTokens: Map<String, TokenInfo> = emptyMap(),
        excludedDemandIds: Set<String> = emptySet(),
    ): AuctionRound {
        val auctionId = UUID.randomUUID().toString()
        logInfo(
            TAG,
            "startRound(): auctionId=$auctionId, pricefloor=$pricefloor, collectPricefloor=$collectPricefloor, " +
                "existingTokens=${existingTokens.keys}, excludedDemandIds=$excludedDemandIds"
        )

        auctionStat.markAuctionStarted(auctionId, adTypeParam)

        val effectiveParam = adTypeParam.withPricefloor(pricefloor)

        // Fetch new tokens, excluding both existing and fully excluded networks
        logInfo(TAG, "startRound(): fetching tokens (excluding ${existingTokens.size} existing, ${excludedDemandIds.size} excluded)...")
        val newTokens = getTokens(
            adTypeParam = effectiveParam,
            adaptersSource = adaptersSource,
            tokenTimeout = biddingConfig.tokenTimeout,
        ).filterKeys { it !in existingTokens && it !in excludedDemandIds }

        // Merge: existing tokens + new tokens (existing take precedence), exclude fully excluded
        val tokens = existingTokens.filterKeys { it !in excludedDemandIds } + newTokens
        logInfo(TAG, "startRound(): got ${tokens.size} tokens (${existingTokens.size} existing + ${newTokens.size} new): [${tokens.keys.joinToString()}]")

        logInfo(TAG, "startRound(): requesting auction from server...")
        val response = getAuctionRequest.request(
            adTypeParam = effectiveParam,
            auctionId = auctionId,
            demandAd = demandAd,
            adapters = adaptersSource.adapters.associate { it.demandId.demandId to it.adapterInfo },
            tokens = tokens,
        ).getOrThrow()

        val adUnits = response.adUnits ?: emptyList()
        val rtbUnits = adUnits.filter { it.bidType == BidType.RTB }
        val cpmUnits = adUnits.filter { it.bidType == BidType.CPM }
        logInfo(
            TAG,
            "startRound(): server responded: ${adUnits.size} adUnits (${rtbUnits.size} RTB, ${cpmUnits.size} CPM), " +
                "pricefloor=${response.pricefloor}, timeout=${response.auctionTimeout}ms"
        )
        logInfo(TAG, "startRound(): waterfall order: [${adUnits.joinToString { "${it.demandId}/${it.bidType}/${it.pricefloor}" }}]")

        resultsCollector.clear()
        resultsCollector.startRound(collectPricefloor)
        resultsCollector.serverBiddingStarted()
        resultsCollector.serverBiddingFinished(
            response.adUnits?.filter { it.bidType == BidType.RTB }
        )
        resultsCollector.setNoBidInfo(response.noBids)
        logInfo(TAG, "startRound(): resultsCollector initialized, noBids=${response.noBids?.size ?: 0}")

        return AuctionRound(adTypeParam = effectiveParam, response = response, tokens = tokens)
    }

    /**
     * Loads a single ad unit: requests the ad, adds to results collector, returns the result.
     * Does NOT insert into cache slots — that's the orchestrator's job.
     */
    suspend fun loadUnit(
        adUnit: AdUnit,
        round: AuctionRound,
        tokens: Map<String, TokenInfo> = round.tokens,
    ): AuctionResult? {
        logInfo(TAG, "loadUnit(): ${adUnit.demandId}/${adUnit.bidType} @ ${adUnit.pricefloor}, hasToken=${tokens.containsKey(adUnit.demandId)}")

        val auctionResult = requestSingleUnit(
            adUnit = adUnit,
            tokens = tokens,
            auctionResponse = round.response,
            adTypeParam = round.adTypeParam,
        )

        val status = auctionResult?.roundStatus
        val resultPrice = if (status == RoundStatus.Successful) {
            auctionResult?.adSource?.getStats()?.price
        } else {
            null
        }
        logInfo(TAG, "loadUnit(): ${adUnit.demandId} → status=$status, price=$resultPrice")

        if (auctionResult != null) {
            resultsCollector.add(auctionResult)
            logInfo(TAG, "loadUnit(): added ${adUnit.demandId} to resultsCollector")
        }
        return auctionResult
    }

    /**
     * Collects round results and sends stats to the server.
     */
    suspend fun collectStats(round: AuctionRound): RoundStat? {
        logInfo(TAG, "collectStats(): collecting round results...")
        val roundResult = resultsCollector.getRoundResults()
        logInfo(TAG, "collectStats(): roundResult type=${roundResult::class.simpleName}")

        val roundStat = (roundResult as? RoundResult.Results)?.let {
            auctionStat.addRoundResults(it)
        }
        logInfo(TAG, "collectStats(): roundStat demands=${roundStat?.demands?.size ?: 0}, noBids=${roundStat?.noBids?.size ?: 0}")

        logInfo(TAG, "collectStats(): sending auction stats to server...")
        auctionStat.sendAuctionStats(
            auctionData = round.response,
            roundStat = roundStat,
            demandAd = demandAd,
        )
        logInfo(TAG, "collectStats(): stats sent")
        return roundStat
    }

    // === Internal ===

    private suspend fun requestSingleUnit(
        adUnit: AdUnit,
        tokens: Map<String, TokenInfo>,
        auctionResponse: AuctionResponse,
        adTypeParam: AdTypeParam,
    ): AuctionResult? {
        val pricefloor = auctionResponse.pricefloor

        if (adUnit.pricefloor < pricefloor) {
            logInfo(TAG, "requestSingleUnit(): ${adUnit.demandId} below pricefloor: ${adUnit.pricefloor} < $pricefloor")
            return getBelowPriceFloorResult(adUnit, tokens[adUnit.demandId])
        }

        val adapter = adaptersSource.adapters.find { it.demandId.demandId == adUnit.demandId }
        if (adapter == null) {
            logInfo(TAG, "requestSingleUnit(): adapter ${adUnit.demandId} not found in registered adapters")
            return AuctionResult.AuctionFailed(
                adUnit = adUnit,
                roundStatus = RoundStatus.UnknownAdapter,
                tokenInfo = tokens[adUnit.demandId],
            )
        }

        adapter.applyRegulation()
        val adSource = adapter.getAdSource(demandAd.adType)
        if (adSource == null) {
            logInfo(TAG, "requestSingleUnit(): ${adUnit.demandId} cannot create adSource for ${demandAd.adType}")
            return AuctionResult.AuctionFailed(
                adUnit = adUnit,
                roundStatus = RoundStatus.UnknownAdapter,
                tokenInfo = tokens[adUnit.demandId],
            )
        }

        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())

        if (adUnit.bidType == BidType.RTB) {
            val tokenInfo = tokens[adUnit.demandId]
            logInfo(TAG, "requestSingleUnit(): ${adUnit.demandId} RTB, setting token (hasToken=${tokenInfo != null})")
            tokenInfo?.let { adSource.setTokenInfo(it) }
        }

        applyParams(
            auctionId = auctionResponse.auctionId,
            auctionConfigurationId = auctionResponse.auctionConfigurationId ?: 0L,
            auctionConfigurationUid = auctionResponse.auctionConfigurationUid ?: "",
            externalWinNotificationsEnabled = auctionResponse.externalWinNotificationsEnabled,
            adSource = adSource,
            adTypeParam = adTypeParam,
            auctionPricefloor = pricefloor,
        )

        logInfo(TAG, "requestSingleUnit(): requesting ad from ${adUnit.demandId}/${adUnit.bidType}...")
        return requestAdUnit.invoke(
            adSource = adSource,
            adTypeParam = adTypeParam,
            adUnit = adUnit,
            priceFloor = pricefloor,
        )
    }

    private fun applyParams(
        auctionId: String,
        auctionConfigurationId: Long,
        auctionConfigurationUid: String,
        externalWinNotificationsEnabled: Boolean,
        adSource: AdSource<AdAuctionParams>,
        adTypeParam: AdTypeParam,
        auctionPricefloor: Double,
    ) {
        adSource.addRoundInfo(
            auctionId = auctionId,
            demandAd = demandAd,
            auctionPricefloor = auctionPricefloor,
        )
        adSource.setStatisticAdType(adTypeParam.asStatisticAdType())
        adSource.addAuctionConfigurationId(auctionConfigurationId)
        adSource.addAuctionConfigurationUid(auctionConfigurationUid)
        adSource.addExternalWinNotificationsEnabled(externalWinNotificationsEnabled)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Adapter.getAdSource(adType: AdType): AdSource<AdAuctionParams>? {
        val adapterDemandId = demandId
        return when (adType) {
            AdType.Interstitial -> safeCreateAdSource<AdProvider.Interstitial<AdAuctionParams>>("interstitial") { interstitial().apply { addDemandId(adapterDemandId) } }
            AdType.Rewarded -> safeCreateAdSource<AdProvider.Rewarded<AdAuctionParams>>("rewarded") { rewarded().apply { addDemandId(adapterDemandId) } }
            AdType.Banner -> safeCreateAdSource<AdProvider.Banner<AdAuctionParams>>("banner") { banner().apply { addDemandId(adapterDemandId) } }
        }
    }

    private inline fun <reified T> Adapter.safeCreateAdSource(
        adType: String,
        create: T.() -> AdSource<AdAuctionParams>,
    ): AdSource<AdAuctionParams>? {
        return (this as? T)?.let {
            runCatching { it.create() }
                .onFailure { logError(TAG, "Failed to create $adType ad source", it) }
                .getOrNull()
        }
    }

    private fun getBelowPriceFloorResult(adUnit: AdUnit, tokenInfo: TokenInfo?): AuctionResult {
        return when (adUnit.bidType) {
            BidType.RTB -> AuctionResult.AuctionFailed(
                adUnit = adUnit,
                roundStatus = RoundStatus.Lose,
                tokenInfo = tokenInfo,
            )
            BidType.CPM -> AuctionResult.AuctionFailed(
                adUnit = adUnit,
                roundStatus = RoundStatus.BelowPricefloor,
                tokenInfo = null,
            )
        }
    }

    private fun AdTypeParam.withPricefloor(newPricefloor: Double): AdTypeParam = when (this) {
        is AdTypeParam.Interstitial -> AdTypeParam.Interstitial(activity, newPricefloor, auctionKey)
        is AdTypeParam.Rewarded -> AdTypeParam.Rewarded(activity, newPricefloor, auctionKey)
        is AdTypeParam.Banner -> AdTypeParam.Banner(activity, newPricefloor, auctionKey, bannerFormat, containerWidth)
    }

    private fun AdTypeParam.asStatisticAdType(): StatisticsCollector.AdType {
        return when (this) {
            is AdTypeParam.Banner -> StatisticsCollector.AdType.Banner(
                format = when (bannerFormat) {
                    BannerFormat.Banner -> BannerRequest.StatFormat.BANNER_320x50
                    BannerFormat.LeaderBoard -> BannerRequest.StatFormat.LEADERBOARD_728x90
                    BannerFormat.MRec -> BannerRequest.StatFormat.MREC_300x250
                    BannerFormat.Adaptive -> BannerRequest.StatFormat.ADAPTIVE_BANNER
                }
            )
            is AdTypeParam.Interstitial -> StatisticsCollector.AdType.Interstitial
            is AdTypeParam.Rewarded -> StatisticsCollector.AdType.Rewarded
        }
    }
}

private const val TAG = "AdCacheVladimir.WaterfallLoader"
