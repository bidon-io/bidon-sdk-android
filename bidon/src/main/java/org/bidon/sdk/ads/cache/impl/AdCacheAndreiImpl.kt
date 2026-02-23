package org.bidon.sdk.ads.cache.impl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.impl.andr.AdBuffer
import org.bidon.sdk.ads.cache.impl.andr.AdUnitBuffer
import org.bidon.sdk.ads.cache.impl.andr.AdUnitListMerger
import org.bidon.sdk.ads.cache.impl.andr.AuctionImpl
import org.bidon.sdk.ads.cache.impl.andr.AuctionResultBuffer
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.Auction
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AdUnit
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.auction.usecases.AuctionStopCondition
import org.bidon.sdk.ads.cache.impl.andr.ExecuteAuctionAndreiUseCaseImpl
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 implementation of AdCache with configurable behavior.
 */
internal class AdCacheAndreiImpl(
    override val demandAd: DemandAd,
    private val executionDispatcher: CoroutineDispatcher,
    private val callbackDispatcher: CoroutineDispatcher,
    private val resolver: AuctionResolver,
) : AdCache {
    private val tag = "${TAG}_${demandAd.adType.code}"

    private val scope: CoroutineScope by lazy {
        CoroutineScope(callbackDispatcher + SupervisorJob())
    }

    private val isLoading = AtomicBoolean(false)

    private var settings: Cacheable.Settings = Cacheable.DefaultSettings

    private var auction: Auction? = null

    private val adUnitBuffer: AdBuffer<AdUnit, *> by lazy {
        AdUnitBuffer()
    }

    private val auctionResultBuffer: AdBuffer<AuctionResult, *> by lazy {
        AuctionResultBuffer()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        this.settings = settings
    }

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(tag, "Cache started: ${auctionResultBuffer.peekAll().asString()}")

        if (isLoading.getAndSet(true)) {
            logInfo(tag, "Ad is already loading")
            return
        }

        logInfo(tag, "Cache ad: $adTypeParam")

        val executeAuction =
            ExecuteAuctionAndreiUseCaseImpl(
                adaptersSource = get(),
                requestAdUnit = get(),
                statsRepository = get(),
                adUnitBuffer = adUnitBuffer,
                adUnitListMerger = AdUnitListMerger(),
                stopCondition =
                    object : AuctionStopCondition {
                        override fun shouldStop(
                            successCount: Int,
                            lastResult: AuctionResult,
                            next: AdUnit?
                        ): Boolean = successCount >= auctionResultBuffer.capacity
                    },
            )
        auction =
            AuctionImpl(
                executionDispatcher = executionDispatcher,
                adaptersSource = get(),
                getTokens = get(),
                getAuctionRequest = get(),
                executeAuction = executeAuction,
                auctionStat = get(),
                biddingConfig = get(),
            )
        auction?.start(
            demandAd = demandAd,
            adTypeParam = adTypeParam,
            onSuccess = { results, info -> processAuctionSuccess(results, info, onSuccess) },
            onFailure = { info, cause -> processAuctionFailure(info, cause, onFailure) },
        )
    }

    override fun peek(): AuctionResult? = auctionResultBuffer.peek()

    override fun pop(): AuctionResult? = auctionResultBuffer.pop()

    override suspend fun poll(): AuctionResult = auctionResultBuffer.poll()

    override fun clear() {
        adUnitBuffer.clear()
        auctionResultBuffer.clear()

        if (!isLoading.getAndSet(false)) {
            return
        }

        logInfo(tag, "Ad is loading, cancel auction")

        auction?.cancel()
        auction = null
    }

    private fun processAuctionSuccess(
        auctionResults: List<AuctionResult>,
        auctionInfo: AuctionInfo,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    ) {
        scope.launch {
            auctionResultBuffer
                .insert(*auctionResults.toTypedArray())
                .also {
                    logInfo(
                        tag, "Auction completed: ${auctionResultBuffer.peekAll().asString()}"
                    )
                }.also { isLoading.set(false) }
                .let { onSuccess.invoke(auctionResultBuffer.poll(), auctionInfo) }
        }
    }

    private fun processAuctionFailure(
        auctionInfo: AuctionInfo?,
        cause: Throwable,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        scope.launch {
            logInfo(tag, "Auction failed: ${auctionResultBuffer.peekAll().asString()}")
            isLoading.set(false)
            onFailure.invoke(auctionInfo, cause)
        }
    }

    private fun Collection<AuctionResult>.asString(): String =
        "(${this.size}) " +
            joinToString { auctionResult ->
                auctionResult.adSource.getStats().let { "${it.demandId.demandId}:${it.price}" }
            }
}
