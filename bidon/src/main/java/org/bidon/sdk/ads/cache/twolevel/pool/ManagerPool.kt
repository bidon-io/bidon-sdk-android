package org.bidon.sdk.ads.cache.twolevel.pool

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bidon.sdk.adapter.DemandAd
import org.bidon.sdk.ads.AdType
import org.bidon.sdk.ads.cache.twolevel.TwoLevelAdManager
import org.bidon.sdk.ads.cache.twolevel.auction.SequentialAuctionPipeline
import org.bidon.sdk.ads.cache.twolevel.auction.TwoLevelAuctionController
import org.bidon.sdk.ads.cache.twolevel.config.TwoLevelCacheConfig
import org.bidon.sdk.ads.cache.twolevel.storage.TwoLevelCacheStores
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.di.get
import java.lang.ref.WeakReference

/**
 * Singleton pool of [TwoLevelAdManager] instances keyed by auctionKey.
 *
 * Mirrors iOS ZhenyaManagerPool:
 * - One manager per auctionKey — if a new InterstitialImpl is created with the same
 *   auctionKey it gets access to the existing manager.
 * - Stores a [WeakReference] to the manager; when no strong reference exists (the
 *   InterstitialImpl holding the AdCache was GC'd) the entry becomes eligible for cleanup.
 * - Backing [TwoLevelCacheStores] are static singletons per [AdType], shared across
 *   all managers of the same ad type (mirrors iOS Cacher.Main/Fallback static stores).
 * - Periodic cleanup every 60 s: removes entries where the manager is idle AND
 *   (older than 5 min OR the weak reference has been cleared).
 * - Thread-safe via [Mutex].
 */
internal object ManagerPool {

    private data class PoolEntry(
        val weakRef: WeakReference<TwoLevelAdManager>,
        val adType: AdType,
        val createdAt: Long, // SystemClock.elapsedRealtime()
    )

    private val mutex = Mutex()
    private val pool = mutableMapOf<String, PoolEntry>()
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private const val IDLE_TTL_MS = 5 * 60 * 1_000L // 5 minutes
    private const val CLEANUP_INTERVAL_MS = 60 * 1_000L // 60 seconds

    init {
        startPeriodicCleanup()
    }

    /**
     * Returns the existing live [TwoLevelAdManager] for [auctionKey], or creates a new one.
     *
     * The returned manager holds a strong reference. As long as the caller
     * (InterstitialImpl / BannerView) holds the AdCache reference, the manager stays alive.
     * Once the caller is GC'd the [WeakReference] in the pool is cleared and the entry
     * becomes eligible for cleanup on the next periodic sweep.
     */
    suspend fun getOrCreate(
        auctionKey: String,
        demandAd: DemandAd,
        config: TwoLevelCacheConfig,
    ): TwoLevelAdManager = mutex.withLock {
        // Check existing — reuse if WeakReference is still live
        val existing = pool[auctionKey]
        if (existing != null) {
            val live = existing.weakRef.get()
            if (live != null) {
                logInfo(TAG, "[Pool] reusing manager auctionKey=$auctionKey")
                return@withLock live
            } else {
                logInfo(TAG, "[Pool] weak ref dead for auctionKey=$auctionKey, creating new")
                pool.remove(auctionKey)
            }
        }

        // Get (or lazily init) static stores for this AdType — mirrors iOS Cacher static fields
        val stores = TwoLevelCacheStores.getOrCreate(demandAd.adType, config)

        val pipeline = SequentialAuctionPipeline(
            adaptersSource = get(),
            getTokens = get(),
            getAuctionRequest = get(),
            auctionStat = get(),
            biddingConfig = get(),
            adTypeLabel = demandAd.adType.code.uppercase(),
        )

        val controller = TwoLevelAuctionController(
            pipeline = pipeline,
            fallbackCache = stores.fallback,
            adTypeLabel = demandAd.adType.code.uppercase(),
        )

        val manager = TwoLevelAdManager(
            demandAd = demandAd,
            mainCache = stores.main,
            fallbackCache = stores.fallback,
            controller = controller,
            auctionKey = auctionKey,
        )

        pool[auctionKey] = PoolEntry(
            weakRef = WeakReference(manager),
            adType = demandAd.adType,
            createdAt = SystemClock.elapsedRealtime(),
        )
        logInfo(TAG, "[Pool] created new manager auctionKey=$auctionKey adType=${demandAd.adType}")
        manager
    }

    // ---

    private fun startPeriodicCleanup() {
        cleanupScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                runCleanup()
            }
        }
    }

    private suspend fun runCleanup() = mutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val toRemove = pool.entries.filter { (_, entry) ->
            val manager = entry.weakRef.get()
            val isWeakRefDead = manager == null
            val isIdle = manager?.isIdle() ?: true
            val isOldEnough = (now - entry.createdAt) > IDLE_TTL_MS
            // iOS cleanup condition: idle AND (old enough OR weak ref dead)
            isIdle && (isOldEnough || isWeakRefDead)
        }.map { it.key }

        toRemove.forEach { key -> pool.remove(key) }
        if (toRemove.isNotEmpty()) {
            logInfo(TAG, "[Pool] cleanup removed ${toRemove.size} entries: $toRemove, remaining=${pool.size}")
        }
    }
}

private const val TAG = "[TwoLevelCache]"
