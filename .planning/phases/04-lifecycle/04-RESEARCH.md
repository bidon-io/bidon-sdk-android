# Phase 4: Lifecycle Management - Research

**Researched:** 2026-02-05
**Domain:** Android lifecycle management with Kotlin Coroutines, memory leak prevention, and resource cleanup
**Confidence:** HIGH

## Summary

Phase 4 implements periodic cache sweeps and showAd-triggered cancellation for the ad caching system. This requires three critical lifecycle concerns: (1) periodic background jobs scoped to ad instance lifecycle, (2) proper coroutine cancellation with guaranteed cleanup using NonCancellable context, and (3) WeakReference patterns to prevent Activity context leaks in singleton caches.

The research reveals that Kotlin Coroutines provides built-in patterns for all three concerns: `CoroutineScope.launch` with `while(isActive)` + `delay()` for periodic jobs, `withContext(NonCancellable)` in finally blocks for guaranteed cleanup during cancellation, and `WeakReference<Activity>` combined with periodic null checks to prevent memory leaks. The Android ecosystem strongly recommends lifecycle-aware scopes (lifecycleScope, viewModelScope) but the SDK's singleton cache design requires custom scope management tied to ad instance destruction.

**Primary recommendation:** Create instance-scoped CoroutineScope with SupervisorJob for periodic sweep jobs (5-minute intervals), use withContext(NonCancellable) in all finally blocks containing suspending cleanup calls (AdSource.destroy(), statistics reporting), and implement WeakReference pattern for Activity contexts in AdSource implementations with periodic sweep validation.

## Standard Stack

The established libraries/tools for lifecycle management in Android/Kotlin:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx.coroutines | 1.7+ | Coroutine lifecycle, cancellation, structured concurrency | Official Kotlin async framework, built-in cancellation support |
| kotlinx.coroutines.Job | 1.7+ | Coroutine job cancellation and lifecycle tracking | Core primitive for coroutine lifecycle management |
| kotlinx.coroutines.NonCancellable | 1.7+ | Guaranteed execution context for cleanup code | Official solution for cleanup that must complete even when cancelled |
| android.os.SystemClock | Android API 1+ | Monotonic time for periodic intervals | Platform-provided, reliable timing source |
| java.lang.ref.WeakReference | Java 1.2+ | Weak references for memory leak prevention | JVM built-in, garbage collector integration |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx.coroutines.SupervisorJob | 1.7+ | Job that doesn't propagate failures to parent | Periodic sweep failures shouldn't crash ad instance |
| kotlinx.coroutines.CoroutineScope | 1.7+ | Scope for launching coroutines with lifecycle | Custom scopes for ad instance lifecycle |
| kotlinx.coroutines.isActive | 1.7+ | Check if coroutine is still active | Exit condition for periodic loops |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Custom CoroutineScope | GlobalScope | GlobalScope never cancels, causes memory leaks, anti-pattern in modern Android |
| Custom CoroutineScope | lifecycleScope | lifecycleScope tied to Activity/Fragment, but SDK needs application-wide scope with ad instance lifecycle |
| Job.cancel() | Manual cancellation flags | Job.cancel() provides structured concurrency, automatic child cancellation, built-in completion tracking |
| WeakReference | Application context | Application context doesn't leak but some ad networks REQUIRE Activity for functionality |
| while(isActive) + delay() | Timer/Handler | Coroutine-based approach integrates with cancellation, testable, no thread management overhead |

**Installation:**
```kotlin
// Already included in project dependencies
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7+")
}
```

## Architecture Patterns

### Recommended Project Structure
```
org/bidon/sdk/ads/cache/denis/
├── lifecycle/
│   ├── AdInstanceScope.kt       # CoroutineScope lifecycle management
│   ├── PeriodicSweepJob.kt      # 5-minute periodic cache sweep
│   └── CancellationManager.kt   # showAd() cancellation coordination
├── cleanup/
│   ├── CleanupCoordinator.kt    # NonCancellable cleanup orchestration
│   └── ResourceReleaser.kt      # AdSource.destroy() + stats reporting
└── memory/
    ├── WeakActivityRef.kt       # WeakReference wrapper utilities
    └── ContextValidator.kt      # Periodic WeakReference validation
```

### Pattern 1: Ad Instance-Scoped CoroutineScope
**What:** Create CoroutineScope tied to ad instance lifecycle with SupervisorJob
**When to use:** Launching periodic jobs that must stop when ad instance is destroyed
**Example:**
```kotlin
// Source: Kotlin Coroutines best practices, verified from official docs
class AdCacheImpl(
    private val demandAd: DemandAd
) : AdCache {
    // Instance-scoped coroutine scope with SupervisorJob
    // SupervisorJob: sweep failures don't crash entire ad instance
    private val adInstanceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private var periodicSweepJob: Job? = null

    init {
        startPeriodicSweep()
    }

    private fun startPeriodicSweep() {
        periodicSweepJob = adInstanceScope.launch {
            while (isActive) {
                delay(TtlConfig.SWEEP_INTERVAL_MILLIS) // 5 minutes
                performSweep()
            }
        }
    }

    private suspend fun performSweep() {
        try {
            ReadyToShowCache.evictExpired()
            RtbPayloadCache.evictExpired()
            validateWeakReferences()
        } catch (e: Exception) {
            logError(TAG, "Sweep failed", e)
            // SupervisorJob prevents propagation to parent
        }
    }

    override fun clear() {
        // Destroy ad instance: cancel all coroutines
        adInstanceScope.cancel()
        periodicSweepJob?.cancel()
    }
}
```

### Pattern 2: NonCancellable Cleanup in Finally Blocks
**What:** Use withContext(NonCancellable) for suspending cleanup operations
**When to use:** AdSource.destroy(), stats reporting, any cleanup that must complete even when cancelled
**Example:**
```kotlin
// Source: Official Kotlin Coroutines documentation on cancellation
// https://kotlinlang.org/docs/cancellation-and-timeouts.html
// https://medium.com/androiddevelopers/coroutines-patterns-for-work-that-shouldnt-be-cancelled-e26c40f142ad
suspend fun processCpmWaterfall(adUnits: List<AdUnit>) {
    val loadedSources = mutableListOf<AdSource>()

    try {
        for (adUnit in adUnits) {
            // Check cancellation before each load
            ensureActive()

            val adSource = createAdSource(adUnit)
            adSource.load(adTypeParam)
            loadedSources.add(adSource)
        }
    } finally {
        // CRITICAL: NonCancellable ensures cleanup completes even if cancelled
        withContext(NonCancellable) {
            // Cleanup: AdSource destruction
            loadedSources.forEach { adSource ->
                try {
                    adSource.destroy() // May be suspending
                } catch (e: Exception) {
                    logError(TAG, "AdSource.destroy() failed", e)
                    // Log but continue cleanup
                }
            }

            // Cleanup: Statistics reporting
            try {
                statsReporter.reportCancellation(
                    auctionId = auctionId,
                    cancelledCount = loadedSources.size
                )
            } catch (e: Exception) {
                logError(TAG, "Stats reporting failed", e)
            }

            // Cleanup: Cache consistency (if needed)
            try {
                ReadyToShowCache.removeCancelledEntries(auctionId)
            } catch (e: Exception) {
                logError(TAG, "Cache cleanup failed", e)
            }
        }
    }
}
```

### Pattern 3: Parallel Cleanup with NonCancellable
**What:** Launch multiple cleanup operations concurrently in NonCancellable context
**When to use:** Multiple AdSource instances need destruction, speed up cleanup
**Example:**
```kotlin
// Source: Kotlin Coroutines parallel patterns
suspend fun cleanupMultipleAdSources(adSources: List<AdSource>) {
    withContext(NonCancellable) {
        // Launch parallel cleanup for speed
        coroutineScope {
            adSources.forEach { adSource ->
                launch {
                    try {
                        adSource.destroy()
                    } catch (e: Exception) {
                        logError(TAG, "AdSource cleanup failed: ${adSource.demandId}", e)
                    }
                }
            }
        }
    }
}
```

### Pattern 4: WeakReference Pattern for Activity Context
**What:** Store Activity references in WeakReference, check null during periodic sweep
**When to use:** AdSource implementations that must use Activity but are stored in singleton cache
**Example:**
```kotlin
// Source: Android memory leak prevention best practices
// https://developer.android.com/topic/libraries/architecture/coroutines
// https://medium.com/swlh/context-and-memory-leaks-in-android-82a39ed33002
class AdSourceImpl(
    adUnit: AdUnit
) : AdSource.Interstitial {
    // Weak reference to Activity - can be garbage collected
    private var activityRef: WeakReference<Activity>? = null
    private var isDestroyed = false

    override fun load(adParams: AdAuctionParams) {
        // Store weak reference during load
        val activity = adParams.activity
        activityRef = WeakReference(activity)

        // Use Application context where possible
        val context = activity.applicationContext
        nativeAdNetwork.loadAd(context, adUnit)
    }

    override fun show(activity: Activity) {
        // Validate weak reference before use
        val cachedActivity = activityRef?.get()
        if (cachedActivity == null || cachedActivity.isDestroyed) {
            logWarning(TAG, "Activity reference lost, using provided activity")
            activityRef = WeakReference(activity)
        }

        nativeAdNetwork.showAd(activity, loadedAd)
    }

    override fun destroy() {
        isDestroyed = true
        activityRef?.clear()
        activityRef = null
        nativeAdNetwork.destroyAd()
    }

    // Called during periodic sweep
    fun validateContext(): Boolean {
        val activity = activityRef?.get()
        if (activity == null || activity.isDestroyed) {
            logWarning(TAG, "Activity context lost, destroying AdSource")
            destroy()
            return false
        }
        return true
    }
}
```

### Pattern 5: showAd() Cancellation with Auction ID Matching
**What:** Cancel ongoing auction coroutines when showAd() is called, but only for same auction
**When to use:** User calls showAd() while loadAd() is still in progress
**Example:**
```kotlin
// Source: Project-specific pattern from ParallelAuctionOrchestrator
class AdCacheImpl : AdCache {
    private var currentAuctionJob: Job? = null
    private var currentAuctionId: String? = null

    override suspend fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo, BidonError) -> Unit
    ) {
        val auctionId = UUID.randomUUID().toString()

        // Cancel previous auction if same auction (prevent duplicate processing)
        cancelIfSameAuction(auctionId)

        currentAuctionId = auctionId
        currentAuctionJob = adInstanceScope.launch {
            try {
                val result = runAuction(adTypeParam, auctionId)
                withContext(Dispatchers.Main) {
                    onSuccess(result, auctionInfo)
                }
            } catch (e: CancellationException) {
                // Auction cancelled - cleanup already done in finally
                logInfo(TAG, "Auction cancelled: $auctionId")
                throw e // Re-throw to signal cancellation
            } finally {
                // Cleanup even if cancelled
                withContext(NonCancellable) {
                    cleanupAuctionResources(auctionId)
                }
            }
        }
    }

    private fun cancelIfSameAuction(newAuctionId: String) {
        if (currentAuctionId == newAuctionId) {
            logInfo(TAG, "Cancelling duplicate auction: $newAuctionId")
            currentAuctionJob?.cancel()
            currentAuctionJob = null
        }
    }
}
```

### Pattern 6: Periodic WeakReference Validation
**What:** During periodic sweep, validate all WeakReferences in cache and remove invalid entries
**When to use:** Singleton caches holding AdSource instances with Activity references
**Example:**
```kotlin
// Source: Combined pattern from WeakReference + periodic sweep
private suspend fun validateWeakReferences() {
    withContext(Dispatchers.Default) {
        val invalidEntries = mutableListOf<String>()

        ReadyToShowCache.getAll().forEach { entry ->
            val adSource = entry.value.adSource

            // Check if AdSource has lost its Activity reference
            if (adSource is ContextAware) {
                if (!adSource.validateContext()) {
                    invalidEntries.add(entry.demandId)

                    // Critical cleanup in NonCancellable
                    withContext(NonCancellable) {
                        try {
                            adSource.destroy()
                        } catch (e: Exception) {
                            logError(TAG, "Failed to destroy AdSource: ${entry.demandId}", e)
                        }
                    }
                }
            }
        }

        // Remove invalid entries from cache
        invalidEntries.forEach { demandId ->
            ReadyToShowCache.remove(demandId)
            logInfo(TAG, "Removed AdSource with lost Activity reference: $demandId")
        }
    }
}
```

### Anti-Patterns to Avoid

- **GlobalScope for periodic jobs:** Never use GlobalScope - it never cancels, causing memory leaks
  ```kotlin
  // WRONG: Job runs forever, even after ad instance destroyed
  GlobalScope.launch {
      while (true) { /* sweep */ }
  }

  // CORRECT: Instance-scoped, cancels with ad instance
  adInstanceScope.launch {
      while (isActive) { /* sweep */ }
  }
  ```

- **Suspending cleanup without NonCancellable:** Cleanup aborts if coroutine cancelled
  ```kotlin
  // WRONG: destroy() won't execute if coroutine cancelled
  try {
      loadAd()
  } finally {
      adSource.destroy() // Aborted!
  }

  // CORRECT: Guaranteed execution
  try {
      loadAd()
  } finally {
      withContext(NonCancellable) {
          adSource.destroy()
      }
  }
  ```

- **Strong Activity references in singleton caches:** Causes massive memory leaks
  ```kotlin
  // WRONG: Activity leaked for 30 minutes
  class AdSourceImpl(private val activity: Activity)

  // CORRECT: WeakReference allows GC
  class AdSourceImpl {
      private var activityRef: WeakReference<Activity>? = null
  }
  ```

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Periodic background job | Timer, Handler, ScheduledExecutorService | `CoroutineScope.launch { while(isActive) { delay() } }` | Coroutine-based integrates with cancellation, testable, no thread overhead |
| Guaranteed cleanup during cancellation | Manual try-catch + flags | `withContext(NonCancellable)` | Official Kotlin solution, handles edge cases, well-tested |
| Lifecycle-aware scope | Custom scope + manual cancel tracking | SupervisorJob + CoroutineScope | Structured concurrency, automatic child cancellation |
| Memory leak detection | Manual WeakReference tracking | WeakReference + periodic validation in sweep | JVM GC integration, proven pattern |
| Parallel cleanup | Sequential destroy() calls | coroutineScope + parallel launch | Faster cleanup, non-blocking |

**Key insight:** Kotlin Coroutines provides complete lifecycle management primitives. Don't reinvent cancellation, cleanup, or scoping - use built-in patterns that are battle-tested and handle edge cases you'll miss.

## Common Pitfalls

### Pitfall 1: Suspending Cleanup Without NonCancellable Context
**What goes wrong:** Cleanup code in finally blocks is aborted if coroutine is cancelled before cleanup completes
**Why it happens:** Kotlin checks for cancellation at EVERY suspension point, including inside finally blocks. Developers assume "finally always runs" but miss that suspend functions throw CancellationException.
**How to avoid:**
- Wrap ALL suspending cleanup in `withContext(NonCancellable)`
- Document team guideline: "Never suspend in finally without NonCancellable"
- Code review checklist: Verify finally blocks with suspend calls use NonCancellable
**Warning signs:**
- Memory profiler shows AdSource instances accumulating after cancellation
- Logs show "cleanup started" but never "cleanup completed"
- Native crashes: "Ad view already attached" (AdSource not properly destroyed)

**Source:** [Kotlin Official Docs - Cancellation and Timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html), [Android Developers - Coroutines Patterns for Work That Shouldn't Be Cancelled](https://medium.com/androiddevelopers/coroutines-patterns-for-work-that-shouldnt-be-cancelled-e26c40f142ad)

### Pitfall 2: Activity Context Retained by Singleton Cache
**What goes wrong:** Singleton caches store AdSource instances with strong Activity references. Activity destroyed but AdSource cached for 30 minutes = Activity leaked = massive memory leak (entire view hierarchy retained).
**Why it happens:** Ad network SDKs require Activity context for loading/showing. Developers pass Activity and ad networks store it internally. Singleton cache creates lifecycle mismatch: Activity (seconds) vs cache TTL (30 minutes).
**How to avoid:**
- Use WeakReference<Activity> in AdSource implementations
- Validate WeakReference during periodic sweep (every 5 minutes)
- Destroy AdSource immediately if WeakReference becomes null
- Use Application context wherever functionally possible
**Warning signs:**
- LeakCanary reports: "Activity leaked: retained by AdSource"
- Multiple Activity instances in heap dump when only one should exist
- OOM crashes with repeated loadAd() → back navigation
- Memory profiler shows Activities surviving beyond expected lifecycle

**Source:** [Android Developers - Use Coroutines with Lifecycle-Aware Components](https://developer.android.com/topic/libraries/architecture/coroutines), [Context and Memory Leaks in Android](https://medium.com/swlh/context-and-memory-leaks-in-android-82a39ed33002)

### Pitfall 3: GlobalScope for Periodic Jobs
**What goes wrong:** Using GlobalScope.launch for periodic sweep means job runs forever, never cancels even when ad instance destroyed, causing memory leaks and wasted CPU.
**Why it happens:** GlobalScope is convenient (no scope management) but it's an anti-pattern in modern Android. Developers don't realize GlobalScope = application lifetime, not ad instance lifetime.
**How to avoid:**
- Always use instance-scoped CoroutineScope tied to ad lifecycle
- Call `scope.cancel()` in destroyAd() or clear()
- Use SupervisorJob to prevent sweep failures from crashing ad instance
- Verify with leak detection: no coroutines running after destroyAd()
**Warning signs:**
- Thread dumps show sweep coroutines running after ad instance destroyed
- CPU profiler shows periodic activity when no ads should be active
- Memory profiler shows CoroutineScope instances accumulating

**Source:** [Android Best Practices for Coroutines](https://developer.android.com/kotlin/coroutines/coroutines-best-practices), [CoroutineScope Best Practices in Android](https://medium.com/@jecky999/coroutinescope-best-practices-in-android-lifecyclescope-viewmodelscope-and-globalscope-93cf2f145eaf)

### Pitfall 4: Race Between Cancellation and Cleanup
**What goes wrong:** showAd() cancels auction, but cleanup hasn't finished yet. Next loadAd() starts while previous AdSource instances are still being destroyed, causing resource conflicts (e.g., "Ad already loaded" errors).
**Why it happens:** Job.cancel() is asynchronous - it signals cancellation but doesn't wait for cleanup to complete. Developers call cancel() then immediately start new operation.
**How to avoid:**
- Use `job?.cancelAndJoin()` to wait for cleanup completion
- Or use mutex to prevent concurrent operations
- Or use atomic state tracking: `enum class AuctionState { IDLE, LOADING, DESTROYING }`
**Warning signs:**
- Ad network errors: "Ad already loaded" or "Adapter busy"
- Flaky tests: sometimes works, sometimes fails with "concurrent modification"
- Logs show: "Started new auction" immediately after "Cancelled auction"

**Source:** [Kotlin Coroutine Cancellation: An Advanced Guide](https://omaroid.medium.com/kotlin-coroutine-cancellation-an-advanced-guide-867cb43b5a48), [Cancellation in Kotlin Coroutines](https://kt.academy/article/cc-cancellation)

### Pitfall 5: Ignoring Periodic Sweep Failures
**What goes wrong:** Sweep job throws exception (e.g., AdSource.destroy() fails), entire sweep job crashes and stops, no more periodic cleanup happens.
**Why it happens:** Exception in coroutine propagates to parent unless using SupervisorJob. One bad AdSource crashes entire cleanup mechanism.
**How to avoid:**
- Use SupervisorJob for sweep coroutine scope
- Wrap each AdSource operation in try-catch
- Log failures but continue with remaining items
- Add monitoring: track sweep success rate
**Warning signs:**
- Logs show "Sweep started" but no "Sweep completed"
- Cache size grows unbounded over time
- Memory usage increases gradually
- Expired entries never removed

**Source:** [Coroutines: Structured Concurrency and Cancellation](https://victorbrandalise.com/coroutines-part-iii-structured-concurrency-and-cancellation/)

## Code Examples

Verified patterns from official sources:

### Example 1: Complete Ad Instance Lifecycle Management
```kotlin
// Source: Combined patterns from Kotlin Coroutines best practices
internal class AdCacheDenisImpl(
    private val demandAd: DemandAd
) : AdCache {

    // Instance-scoped coroutine scope with SupervisorJob
    private val adInstanceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    private var periodicSweepJob: Job? = null
    private var currentAuctionJob: Job? = null
    private var currentAuctionId: String? = null

    init {
        startPeriodicSweep()
    }

    private fun startPeriodicSweep() {
        periodicSweepJob = adInstanceScope.launch {
            while (isActive) {
                delay(TtlConfig.SWEEP_INTERVAL_MILLIS) // 5 minutes
                try {
                    performSweep()
                } catch (e: Exception) {
                    logError(TAG, "Sweep failed", e)
                    // SupervisorJob: sweep failure doesn't crash ad instance
                }
            }
        }
    }

    private suspend fun performSweep() {
        logInfo(TAG, "Starting periodic sweep")

        // Remove expired entries
        ReadyToShowCache.evictExpired()
        RtbPayloadCache.evictExpired()

        // Validate WeakReferences
        validateWeakReferences()

        logInfo(TAG, "Sweep completed: ReadyToShow=${ReadyToShowCache.size()}, RtbPayload=${RtbPayloadCache.size()}")
    }

    private suspend fun validateWeakReferences() {
        val invalidEntries = mutableListOf<String>()

        ReadyToShowCache.getAll().forEach { entry ->
            val adSource = entry.value.adSource
            if (adSource is ContextAware && !adSource.isContextValid()) {
                invalidEntries.add(entry.demandId)

                // Destroy AdSource with NonCancellable
                withContext(NonCancellable) {
                    try {
                        adSource.destroy()
                    } catch (e: Exception) {
                        logError(TAG, "Failed to destroy AdSource", e)
                    }
                }
            }
        }

        // Remove invalid entries
        invalidEntries.forEach { demandId ->
            ReadyToShowCache.remove(demandId)
        }

        if (invalidEntries.isNotEmpty()) {
            logInfo(TAG, "Removed ${invalidEntries.size} entries with invalid Activity references")
        }
    }

    override suspend fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo, BidonError) -> Unit
    ) {
        val auctionId = generateAuctionId()
        currentAuctionId = auctionId

        currentAuctionJob = adInstanceScope.launch {
            try {
                val result = orchestrator.executeAuction(adTypeParam, auctionId)
                withContext(Dispatchers.Main) {
                    onSuccess(result.auctionResult, result.auctionInfo)
                }
            } catch (e: CancellationException) {
                logInfo(TAG, "Auction cancelled: $auctionId")
                throw e
            } finally {
                // Guaranteed cleanup even if cancelled
                withContext(NonCancellable) {
                    cleanupAuctionResources(auctionId)
                }
            }
        }
    }

    override fun clear() {
        logInfo(TAG, "Destroying ad instance")

        // Cancel all coroutines (sweep + auction)
        adInstanceScope.cancel()
        periodicSweepJob?.cancel()
        currentAuctionJob?.cancel()

        // Note: AdCache.clear() does NOT clear singleton caches
        // (LIFE-03: destroyAd() doesn't clear application-wide caches)
    }

    companion object {
        private const val TAG = "AdCacheDenis"
    }
}
```

### Example 2: Guaranteed Cleanup During Cancellation
```kotlin
// Source: Kotlin official cancellation patterns
suspend fun processCpmWaterfall(
    adUnits: List<AdUnit>,
    adTypeParam: AdTypeParam,
    demandAd: DemandAd,
    auctionId: String
): CpmResult {
    val loadedSources = mutableListOf<AdSource>()
    val successCount = AtomicInteger(0)
    val failureCount = AtomicInteger(0)

    try {
        for (adUnit in adUnits) {
            // Check cancellation before each load
            ensureActive()

            val adSource = adSourceFactory.create(adUnit, adTypeParam)

            try {
                adSource.load(adTypeParam)
                loadedSources.add(adSource)
                successCount.incrementAndGet()

                // Cache successfully loaded ad
                ReadyToShowCache.put(CacheEntry.create(
                    value = AuctionResult(adSource, adUnit),
                    ecpm = adUnit.pricefloor,
                    demandId = adUnit.demandId,
                    auctionId = auctionId
                ))
            } catch (e: Exception) {
                failureCount.incrementAndGet()
                logInfo(TAG, "CPM load failed: ${adUnit.demandId}")
            }
        }

        return CpmResult(successCount.get(), failureCount.get())

    } finally {
        // CRITICAL: Cleanup must complete even if cancelled
        withContext(NonCancellable) {
            // 1. AdSource destruction (parallel for speed)
            coroutineScope {
                loadedSources.forEach { adSource ->
                    launch {
                        try {
                            adSource.destroy()
                        } catch (e: Exception) {
                            logError(TAG, "AdSource.destroy() failed: ${adSource.demandId}", e)
                        }
                    }
                }
            }

            // 2. Statistics reporting
            try {
                statsReporter.reportCancellation(
                    auctionId = auctionId,
                    adType = demandAd.adType,
                    cancelledCount = loadedSources.size,
                    reason = "showAd_called"
                )
            } catch (e: Exception) {
                logError(TAG, "Stats reporting failed", e)
            }

            // 3. Cache consistency (if needed)
            try {
                ReadyToShowCache.removeByAuctionId(auctionId)
            } catch (e: Exception) {
                logError(TAG, "Cache cleanup failed", e)
            }
        }
    }
}
```

### Example 3: WeakReference Pattern in AdSource Implementation
```kotlin
// Source: Android memory leak prevention patterns
abstract class BaseAdSource(
    protected val adUnit: AdUnit
) : AdSource.Interstitial {

    // WeakReference to Activity - allows garbage collection
    private var activityRef: WeakReference<Activity>? = null
    private var isDestroyed = false

    override fun load(adParams: AdAuctionParams) {
        val activity = adParams.activity

        // Store weak reference
        activityRef = WeakReference(activity)

        // Prefer Application context where possible
        val context = activity.applicationContext

        // Load ad with Application context
        loadAdInternal(context, adUnit)
    }

    override fun show(activity: Activity) {
        if (isDestroyed) {
            logError(TAG, "Cannot show: AdSource already destroyed")
            emitShowFailed(BidonError.AdDestroyed)
            return
        }

        // Validate weak reference before use
        val cachedActivity = activityRef?.get()
        if (cachedActivity == null || cachedActivity.isFinishing || cachedActivity.isDestroyed) {
            logWarning(TAG, "Cached Activity reference lost, using provided Activity")
            activityRef = WeakReference(activity)
        }

        // Show ad (most ad networks require Activity for show)
        showAdInternal(activity)
    }

    override fun destroy() {
        if (isDestroyed) {
            logWarning(TAG, "AdSource already destroyed")
            return
        }

        isDestroyed = true

        // Clear weak reference
        activityRef?.clear()
        activityRef = null

        // Destroy native ad
        destroyAdInternal()
    }

    // Called during periodic sweep to validate context
    fun isContextValid(): Boolean {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            logWarning(TAG, "Activity context lost or destroyed")
            return false
        }
        return true
    }

    protected abstract fun loadAdInternal(context: Context, adUnit: AdUnit)
    protected abstract fun showAdInternal(activity: Activity)
    protected abstract fun destroyAdInternal()
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Timer/Handler for periodic jobs | Coroutine with while(isActive) + delay() | Kotlin 1.3 (2018) | Better cancellation, testability, no thread overhead |
| Manual cleanup flags | withContext(NonCancellable) | Kotlin 1.2 (2017) | Guaranteed cleanup, handles edge cases |
| Strong Activity references | WeakReference + periodic validation | Android best practices (2015+) | Prevents memory leaks in singleton caches |
| GlobalScope everywhere | Lifecycle-aware scopes (custom/lifecycleScope) | Kotlin 1.3+ (2018) | Automatic cancellation, structured concurrency |
| Try-catch for cancellation | Job.cancel() + CancellationException | Kotlin Coroutines 1.0 (2018) | Cooperative cancellation, automatic propagation |

**Deprecated/outdated:**
- Timer/ScheduledExecutorService for periodic jobs: Replaced by coroutine-based scheduling (better cancellation, testability)
- GlobalScope: Anti-pattern in modern Android, causes leaks, use custom scopes instead
- Thread.sleep() in loops: Use delay() for cooperative cancellation
- Manual cancellation flags (AtomicBoolean): Use Job.isActive and structured concellation

## Open Questions

Things that couldn't be fully resolved:

1. **Adapter destroy() suspending nature**
   - What we know: Some adapters may have async destroy operations (e.g., reporting to ad network)
   - What's unclear: Whether all adapters should have suspend fun destroy() or callback-based destroy
   - Recommendation: Phase 4 assumes destroy() may be suspending and always wraps in NonCancellable. Verify with adapter implementations.

2. **Optimal sweep interval for different ad formats**
   - What we know: Spec specifies 5 minutes for all formats
   - What's unclear: Whether banner ads (shorter session) should have different sweep interval than interstitial/rewarded
   - Recommendation: Start with 5 minutes for all formats, add telemetry to measure impact, optimize later if needed

3. **Statistics reporting during cancellation**
   - What we know: Spec says "send cancellation stats to /v2/stats"
   - What's unclear: Exact payload format, whether it's synchronous or fire-and-forget
   - Recommendation: Implement as fire-and-forget in NonCancellable context, log failures but don't block cleanup

4. **WeakReference validation frequency**
   - What we know: Periodic sweep runs every 5 minutes
   - What's unclear: Whether WeakReference validation should happen on every sweep or less frequently
   - Recommendation: Validate on every sweep initially, add telemetry to measure performance impact

## Sources

### Primary (HIGH confidence)
- [Kotlin Official Documentation - Cancellation and Timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
- [Android Developers - Use Coroutines with Lifecycle-Aware Components](https://developer.android.com/topic/libraries/architecture/coroutines)
- [Android Developers - Best Practices for Coroutines](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Android Developers - Coroutines Patterns for Work That Shouldn't Be Cancelled](https://medium.com/androiddevelopers/coroutines-patterns-for-work-that-shouldnt-be-cancelled-e26c40f142ad)
- [Android Developers - Cancellation in Coroutines](https://medium.com/androiddevelopers/cancellation-in-coroutines-aa6b90163629)

### Secondary (MEDIUM confidence)
- [Kt.Academy - Cancellation in Kotlin Coroutines](https://kt.academy/article/cc-cancellation)
- [Medium - Mastering NonCancellable in Kotlin Coroutines](https://medium.com/@shushanttiwari.ashu/mastering-noncancellable-in-kotlin-coroutines-ensuring-safe-cleanup-and-transactions-ea680053f09b)
- [Medium - NonCancellable in Kotlin Coroutines: When You Need the Job Done](https://medium.com/@sivavishnu0705/noncancellable-in-kotlin-coroutines-when-you-need-the-job-done-no-matter-what-12aeacf229b5)
- [Medium - Context and Memory Leaks in Android](https://medium.com/swlh/context-and-memory-leaks-in-android-82a39ed33002)
- [Baeldung - Scheduling Repeating Task in Kotlin](https://www.baeldung.com/kotlin/schedule-repeating-task)
- [Gist - Coroutine-based solution for delayed and periodic work](https://gist.github.com/gmk57/67591e0c878cedc2a318c10b9d9f4c0c)

### Tertiary (LOW confidence)
- Various blog posts on CoroutineScope best practices (requires verification with official docs)
- GitHub discussions on coroutine scheduling (architectural guidance, not API specifics)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All libraries are official Kotlin/Android SDK components
- Architecture: HIGH - Patterns verified from official Kotlin Coroutines documentation
- Pitfalls: HIGH - Identified from official Android docs + project-specific PITFALLS.md

**Research date:** 2026-02-05
**Valid until:** 2026-05-05 (90 days - Kotlin Coroutines is stable, patterns unlikely to change significantly)
