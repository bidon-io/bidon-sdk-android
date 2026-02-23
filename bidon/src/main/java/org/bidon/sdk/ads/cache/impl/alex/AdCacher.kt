package org.bidon.sdk.ads.cache.impl.alex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.adapter.ext.notifyExternalLoss
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.AuctionResolver
import org.bidon.sdk.auction.models.AuctionResult
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.SdkDispatchers
import org.bidon.sdk.utils.di.get
import org.bidon.sdk.utils.ext.TAG
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * AdCache implementation that stores AuctionResults directly.
 * Executes RTB and CPM auctions in parallel via AlexAuction.
 */
internal class AdCacher(
    override val demandAd: DemandAd,
    private val resolver: AuctionResolver,
) : AdCache {

    private val scope = CoroutineScope(SdkDispatchers.Main)

    // Sorted by price descending - highest price first
    private val _results = MutableStateFlow<List<AuctionResult>>(emptyList())
    private var auction: AlexAuction? = null

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        logInfo(
            TAG,
            "Cache started, cached results: ${
                _results.value.joinToString {
                    "[${it.adSource.demandId.demandId}, $${it.adSource.getAd()?.price}]"
                }
            }"
        )
        val succeeded = AtomicBoolean(false)

        scope.launch {
            // Check for existing ads before starting a new auction
            if ((peek()?.adSource?.getAd()?.price ?: 0.0) >= adTypeParam.pricefloor) {
                val bestResult = pop()
                if (bestResult != null) {
                    val auctionInfo = createEmptyAuctionInfo(bestResult)
                    succeeded.set(true)
                    logInfo(TAG, "Found existing result in storage: ${bestResult.adSource.getAd()}")
                    onSuccess(bestResult, auctionInfo)
                }
            }

            // Start a new auction
            auction = AlexAuction(
                adaptersSource = get(),
                getTokens = get(),
                getAuctionRequest = get(),
                executeAuction = get(),
                auctionStat = get(),
                biddingConfig = get(),
            )
            auction?.start(
                demandAd = demandAd,
                existingResults = _results.value,
                adTypeParam = when (adTypeParam) {
                    is AdTypeParam.Banner -> TODO("Not implemented yet")
                    is AdTypeParam.Interstitial -> {
                        /**
                         * Pricefloor for the auction is set to the max of:
                         * a - average price from UserFlow or from server if server > 1.0
                         * b - highest price from cached results (if any)
                         */
                        AdTypeParam.Interstitial(
                            activity = adTypeParam.activity,
                            pricefloor = max(
                                a = adTypeParam.pricefloor,
                                b = _results.value.firstOrNull()?.adSource?.getAd()?.price ?: 0.0
                            ),
                            auctionKey = adTypeParam.auctionKey,
                        )
                    }

                    is AdTypeParam.Rewarded -> TODO("Not implemented yet")
                },
                onResult = { auctionResult, auctionInfo ->
                    logInfo(TAG, "Result received: ${auctionResult.adSource.getStats().demandId}")
                    // Notify success for first result only
                    if (!succeeded.getAndSet(true)) {
                        adTypeParam.activity.runOnUiThread {
                            onSuccess(auctionResult, auctionInfo)
                        }
                    } else {
                        // Add to sorted storage
                        addResult(auctionResult)
                    }
                },
                onFailure = { auctionInfo, throwable ->
                    if (!succeeded.getAndSet(true)) {
                        logInfo(TAG, "Auction failed: $throwable")
                        adTypeParam.activity.runOnUiThread {
                            onFailure(auctionInfo, throwable)
                        }
                    }
                },
            )
        }
    }

    override fun peek(): AuctionResult? {
        return _results.value.firstOrNull()
    }

    override fun pop(): AuctionResult? {
        val result = peek() ?: return null
        _results.update { list ->
            list.filter { it != result }
        }
        logInfo(TAG, "Popped result: ${result.adSource.getStats().demandId}")
        return result
    }

    override suspend fun poll(): AuctionResult {
        error("No one is using this method yet, we can implement it when needed")
    }

    override fun clear() {
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // Currently no settings to apply
    }

    private fun addResult(result: AuctionResult) {
        _results.update { list ->
            val source = list + result
            val results = source
                .sortedByDescending { it.adSource.getStats().price }
                .distinctBy { it.adSource.demandId }
            (source - results.toSet()).onEach {
                logInfo(TAG, "Destroying duplicate ad from ${it.adSource.demandId.demandId}")
                it.adSource.notifyExternalLoss(
                    winnerDemandId = results.firstOrNull()?.adSource?.demandId?.demandId ?: "-",
                    winnerPrice = results.firstOrNull()?.adSource?.getAd()?.price ?: 0.0
                )
                it.adSource.destroy()
            }
            results
        }
        logInfo(
            TAG,
            "Added result, total: ${
                _results.value.joinToString {
                    "[${it.adSource.demandId.demandId}, $${it.adSource.getAd()?.price}]"
                }
            }"
        )
    }

    private fun createEmptyAuctionInfo(auctionResult: AuctionResult): AuctionInfo {
        val stats = auctionResult.adSource.getStats()
        return AuctionInfo(
            auctionId = stats.auctionId ?: "-",
            auctionConfigurationId = null,
            auctionConfigurationUid = null,
            auctionTimeout = 0,
            auctionPricefloor = stats.auctionPricefloor,
            noBids = null,
            adUnits = null,
        )
    }

    companion object {
        private const val TAG = "AdCacher"
    }
}
