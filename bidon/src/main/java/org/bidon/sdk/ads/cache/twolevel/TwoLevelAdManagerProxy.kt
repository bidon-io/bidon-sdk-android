package org.bidon.sdk.ads.cache.twolevel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
 *  - [_delegate] is a [MutableStateFlow] for safe publication and reactive observation.
 *  - [resolveMutex] ensures exactly one manager is created even under concurrent calls.
 */
internal class TwoLevelAdManagerProxy(
    override val demandAd: DemandAd,
) : AdCache {

    private val _delegate = MutableStateFlow<TwoLevelAdManager?>(null)
    private val resolveMutex = Mutex()
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        ensureActiveScope().launch {
            val manager = resolveDelegate(adTypeParam)
            manager.cache(adTypeParam, onSuccess, onFailure)
        }
    }

    private suspend fun resolveDelegate(adTypeParam: AdTypeParam): TwoLevelAdManager {
        _delegate.value?.let { return it }
        return resolveMutex.withLock {
            _delegate.value ?: run {
                val config = TwoLevelCacheConfig.fromExtras(demandAd.adType)
                val key = adTypeParam.auctionKey ?: "default_${demandAd.adType.code}"
                ManagerPool.getOrCreate(key, demandAd, config)
                    .also { _delegate.value = it }
            }
        }
    }

    override fun peek(): AuctionResult? = _delegate.value?.peek()

    override fun pop(): AuctionResult? = _delegate.value?.pop()

    override suspend fun poll(): AuctionResult =
        _delegate.filterNotNull().first().poll()

    override fun clear() {
        // Cancel running auction (prevents callbacks to dead listener)
        // but keep the manager alive in ManagerPool with its cached ads.
        _delegate.value?.detach()
        _delegate.value = null
        scope.coroutineContext[Job]?.cancel()
    }

    override fun withSettings(settings: Cacheable.Settings) {
        // NO-OP: Two-Level Cache uses TwoLevelCacheConfig sourced from server extras.
    }

    @Synchronized
    private fun ensureActiveScope(): CoroutineScope {
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        return scope
    }
}
