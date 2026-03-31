package org.bidon.sdk.ads.cache.twolevel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AuctionInfo
import org.bidon.sdk.ads.cache.AdCache
import org.bidon.sdk.ads.cache.Cacheable
import org.bidon.sdk.ads.cache.twolevel.config.TwoLevelCacheConfig
import org.bidon.sdk.ads.cache.twolevel.pool.ManagerPool
import org.bidon.sdk.auction.AdTypeParam
import org.bidon.sdk.auction.models.AuctionResult

/**
 * Thin lazy-resolving proxy returned by [AdCacheTwoLevelFactory.create].
 *
 * The auctionKey is only available via [AdTypeParam] at [cache] call time, not at
 * factory creation time. This proxy stores the [DemandAd] and resolves the real
 * [TwoLevelAdManager] from [ManagerPool] on the first [cache] call.
 * All subsequent calls delegate directly to the resolved manager.
 *
 * Thread safety:
 *  - [delegate] is @Volatile for safe publication after assignment.
 *  - [resolveMutex] ensures exactly one manager is created even under concurrent calls.
 */
internal class TwoLevelAdManagerProxy(
    override val demandAd: DemandAd,
) : AdCache {

    @Volatile private var delegate: TwoLevelAdManager? = null
    private val resolveMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        scope.launch {
            val manager = resolveDelegate(adTypeParam)
            manager.cache(adTypeParam, onSuccess, onFailure)
        }
    }

    private suspend fun resolveDelegate(adTypeParam: AdTypeParam): TwoLevelAdManager {
        delegate?.let { return it }
        return resolveMutex.withLock {
            delegate ?: run {
                val config = TwoLevelCacheConfig.fromExtras(demandAd.adType)
                val key = adTypeParam.auctionKey ?: "default_${demandAd.adType.code}"
                ManagerPool.getOrCreate(key, demandAd, config)
                    .also { delegate = it }
            }
        }
    }

    override fun peek(): AuctionResult? = delegate?.peek()

    override fun pop(): AuctionResult? = delegate?.pop()

    override suspend fun poll(): AuctionResult {
        while (true) {
            val d = delegate
            if (d != null) return d.poll()
            delay(100)
        }
    }

    override fun clear() {
        delegate?.clear()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: Two-Level Cache uses TwoLevelCacheConfig sourced from server extras.
    }
}
