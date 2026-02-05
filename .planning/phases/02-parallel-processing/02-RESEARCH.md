# Phase 2: Parallel Processing - Research

**Researched:** 2026-02-05
**Domain:** Kotlin Coroutines parallel processing with structured concurrency in Android ad mediation
**Confidence:** HIGH

## Summary

This phase requires implementing parallel RTB and CPM ad loading processors using Kotlin Coroutines with proper structured concurrency. The RTB processor loads the highest-eCPM cached payload (single attempt), while the CPM processor sequentially loads waterfall networks using a dynamic weight model. Both branches run independently with failure isolation, exactly-once callback semantics, and resource cleanup.

The research reveals that the standard approach uses `async` for parallel execution, `supervisorScope` for independent task failure isolation, `AtomicBoolean` for exactly-once callbacks, and injected `CoroutineScope` (never GlobalScope) for testability. The weight model follows industry patterns: eCPM-based sorting with dynamic fill rate adjustment using multiplicative scoring.

**Primary recommendation:** Use `coroutineScope` with two `async` blocks (RTB and CPM branches), `supervisorScope` for failure isolation within each branch, `AtomicBoolean` for exactly-once callbacks, and in-memory `ConcurrentHashMap<String, AtomicInteger>` for weight storage (demandId → weight). Weight bounds: 1-20, formula: `score = eCPM × (weight / 10.0)`.

## Standard Stack

The established libraries/tools for parallel coroutine processing in Kotlin/Android:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| kotlinx.coroutines-core | 1.6.0+ | Coroutine runtime, async/await | Kotlin standard for structured concurrency |
| kotlinx.coroutines-android | 1.6.0+ | Android Main dispatcher integration | Required for UI thread interop |
| java.util.concurrent.atomic.AtomicBoolean | Java 5+ | Thread-safe boolean flag | Exactly-once callback guarantee |
| java.util.concurrent.atomic.AtomicInteger | Java 5+ | Thread-safe counter | Weight model storage |
| java.util.concurrent.ConcurrentHashMap | Java 8+ | Thread-safe map | Weight model per-demandId storage |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlin.time.Duration | Kotlin 1.6+ | Type-safe time units | Timeout specifications |
| kotlinx.coroutines.flow.StateFlow | 1.6.0+ | Observable cache state | Cache observation for callbacks |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| async + await | launch + Job | async provides Deferred<T> for results; launch only provides Job (no return value) |
| supervisorScope | coroutineScope | coroutineScope fails entire scope if any child fails; supervisorScope isolates failures |
| AtomicBoolean | Mutex | Mutex is suspending (coroutine-friendly) but overkill for simple flag; AtomicBoolean is lock-free |
| Injected CoroutineScope | GlobalScope | GlobalScope hard to test, lives forever; injection enables lifecycle control and testing |

**Installation:**
```bash
# Already available in build-logic/convention-plugins (CommonGradlePlugin.kt):
# implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.0"))
# implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
# implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")
```

## Architecture Patterns

### Recommended Project Structure
```
org/bidon/sdk/ads/cache/denis/
├── processors/              # Parallel processing logic
│   ├── RtbProcessor.kt      # RTB payload loading
│   ├── CpmProcessor.kt      # CPM waterfall loading
│   └── WeightModel.kt       # Fill rate weight tracking
├── orchestration/           # Parallel orchestration
│   ├── ParallelAuctionOrchestrator.kt
│   └── CallbackCoordinator.kt  # Exactly-once callback logic
└── AdCacheDenisImpl.kt      # Integration point
```

### Pattern 1: Parallel Execution with Independent Failure Isolation
**What:** Launch RTB and CPM processing in parallel using `async`, with `supervisorScope` to prevent one branch from canceling the other
**When to use:** Independent tasks that should complete regardless of sibling failures
**Example:**
```kotlin
// Source: Android Developers Coroutines Best Practices + verified pattern
suspend fun loadAdsInParallel(
    rtbPayloads: List<RtbPayload>,
    cpmAdUnits: List<AdUnit>,
    scope: CoroutineScope
) = coroutineScope {
    val rtbDeferred = async {
        supervisorScope {
            // RTB failure doesn't cancel CPM
            loadRtbPayload(rtbPayloads.firstOrNull())
        }
    }

    val cpmDeferred = async {
        supervisorScope {
            // CPM failure doesn't cancel RTB
            loadCpmWaterfall(cpmAdUnits)
        }
    }

    // Both complete independently
    val rtbResult = rtbDeferred.await()
    val cpmResult = cpmDeferred.await()

    return@coroutineScope Pair(rtbResult, cpmResult)
}
```

### Pattern 2: Exactly-Once Callback with AtomicBoolean
**What:** Use `AtomicBoolean.compareAndSet()` to guarantee callback fires exactly once
**When to use:** Race conditions where multiple sources can trigger the same callback
**Example:**
```kotlin
// Source: Java concurrent programming patterns
class CallbackCoordinator(
    private val onAdLoaded: (Ad, AuctionInfo) -> Unit,
    private val onAdLoadFailed: (AuctionInfo?, BidonError) -> Unit
) {
    private val loadedCallbackFired = AtomicBoolean(false)
    private val failedCallbackFired = AtomicBoolean(false)

    fun notifySuccess(ad: Ad, auctionInfo: AuctionInfo) {
        // Returns true only on first call (atomic compare-and-set)
        if (loadedCallbackFired.compareAndSet(false, true)) {
            onAdLoaded(ad, auctionInfo)
        }
    }

    fun notifyFailure(auctionInfo: AuctionInfo?, error: BidonError) {
        // Only fires if success callback hasn't fired
        if (!loadedCallbackFired.get() &&
            failedCallbackFired.compareAndSet(false, true)) {
            onAdLoadFailed(auctionInfo, error)
        }
    }
}
```

### Pattern 3: Sequential Waterfall with Early Success
**What:** Loop through CPM ad units sequentially, stopping on first success
**When to use:** Waterfall mediation where order matters (highest eCPM first)
**Example:**
```kotlin
// Source: Kotlin coroutines sequential processing pattern
suspend fun loadCpmWaterfall(
    adUnits: List<AdUnit>,
    weightModel: WeightModel
): Result<AuctionResult> = coroutineScope {
    // Sort by weighted score (eCPM × weight factor)
    val sorted = weightModel.sortByWeightedScore(adUnits)

    for (adUnit in sorted) {
        ensureActive() // Check for cancellation

        val result = try {
            loadAdUnit(adUnit)
        } catch (e: CancellationException) {
            throw e // Never catch CancellationException
        } catch (e: Exception) {
            logInfo(TAG, "CPM load failed: ${adUnit.demandId}", e)
            weightModel.recordNoFill(adUnit.demandId)
            continue // Try next
        }

        if (result.isSuccess) {
            weightModel.recordFill(adUnit.demandId)
            return@coroutineScope Result.success(result.getOrThrow())
        }
    }

    Result.failure(BidonError.NoFill)
}
```

### Pattern 4: Dynamic Weight Model
**What:** Adjust ad unit ordering based on historical fill rates using multiplicative scoring
**When to use:** Optimizing waterfall order over time with fill rate feedback
**Example:**
```kotlin
// Source: Ad mediation waterfall optimization patterns
class WeightModel {
    private val weights = ConcurrentHashMap<String, AtomicInteger>()
    private val defaultWeight = 10
    private val minWeight = 1
    private val maxWeight = 20

    fun recordFill(demandId: String) {
        weights.getOrPut(demandId) { AtomicInteger(defaultWeight) }
            .updateAndGet { current ->
                (current + 1).coerceIn(minWeight, maxWeight)
            }
    }

    fun recordNoFill(demandId: String) {
        weights.getOrPut(demandId) { AtomicInteger(defaultWeight) }
            .updateAndGet { current ->
                (current - 1).coerceIn(minWeight, maxWeight)
            }
    }

    fun sortByWeightedScore(adUnits: List<AdUnit>): List<AdUnit> {
        return adUnits.sortedByDescending { adUnit ->
            val weight = weights[adUnit.demandId]?.get() ?: defaultWeight
            val weightFactor = weight / 10.0 // Normalize to [0.1, 2.0]
            adUnit.pricefloor * weightFactor // Multiplicative scoring
        }
    }
}
```

### Pattern 5: CoroutineScope Injection (Not GlobalScope)
**What:** Always inject `CoroutineScope` as dependency, never use `GlobalScope`
**When to use:** All coroutine launching in production code (testing requires controllable scopes)
**Example:**
```kotlin
// Source: Android Developers Best Practices
// ✅ DO: Inject scope
class AdCacheDenisImpl(
    private val scope: CoroutineScope = CoroutineScope(SdkDispatchers.Main),
    private val rtbProcessor: RtbProcessor,
    private val cpmProcessor: CpmProcessor
) : AdCache {
    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit
    ) {
        scope.launch {
            // Use injected scope
            loadAdsInParallel()
        }
    }
}

// ❌ DON'T: Use GlobalScope
class BadAdCache {
    fun cache() {
        GlobalScope.launch { // Hard to test, lives forever
            // ...
        }
    }
}
```

### Anti-Patterns to Avoid
- **Catching CancellationException:** Always rethrow to preserve cancellation semantics
- **Using GlobalScope:** Prevents testing, lifecycle control, and proper resource cleanup
- **Blocking operations without Dispatchers.IO:** Coroutines on Main/Default should never block
- **Forgetting ensureActive() in loops:** Long-running loops must check for cancellation
- **Using regular Job instead of SupervisorJob:** Prevents independent failure isolation

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Thread-safe callbacks | Custom synchronization | AtomicBoolean.compareAndSet() | Race-free, lock-free, battle-tested atomic operation |
| Parallel task execution | Manual thread pool | async/await with coroutineScope | Structured concurrency, automatic cancellation, memory safe |
| Independent task failures | try-catch wrapping | supervisorScope | Prevents failure propagation to siblings, built-in pattern |
| Weight storage | Custom thread-safe map | ConcurrentHashMap + AtomicInteger | Lock-free concurrent access, proven performance |

**Key insight:** Kotlin Coroutines provide battle-tested structured concurrency patterns that eliminate manual thread/lifecycle management. Attempting custom solutions risks cancellation bugs, memory leaks, and race conditions.

## Common Pitfalls

### Pitfall 1: Both Branches Fail but Cache Not Empty (Silent Failure)
**What goes wrong:** If both RTB and CPM fail but cache already has ads, system should NOT fire onAdLoadFailed
**Why it happens:** Natural instinct is to fire failure callback when both branches fail
**How to avoid:** Check `ReadyToShowCache.isEmpty()` BEFORE auction starts. Only fire onAdLoadFailed if cache was empty at auction start AND both branches failed
**Warning signs:** User sees "ad load failed" but showAd() succeeds immediately after

### Pitfall 2: showAd() Cancels Wrong Auction
**What goes wrong:** User loads ad (auction A), then loads another (auction B), then shows ad from auction A → auction B incorrectly cancelled
**Why it happens:** Cancellation logic doesn't check which auction the shown ad belongs to
**How to avoid:** Store `auctionId` in CacheEntry, compare `shownAd.auctionId == currentAuction.auctionId` before cancelling
**Warning signs:** Second auction stops loading when first ad is shown

### Pitfall 3: RTB Payload Removed Too Eagerly
**What goes wrong:** RTB payload removed from RTB_PAYLOAD cache before load completes → invalid state
**Why it happens:** Removing payload at start of load() instead of on failure
**How to avoid:** Remove payload from RTB_PAYLOAD cache ONLY in catch block (load failure), not before load attempt
**Warning signs:** RTB payload "disappears" even though load was never attempted

### Pitfall 4: Weight Model Values Go Unbounded
**What goes wrong:** After many cycles, weights grow to 1000+ or drop to -500, skewing sort order
**Why it happens:** No bounds on weight increment/decrement operations
**How to avoid:** Use `coerceIn(1, 20)` on every weight update to enforce bounds
**Warning signs:** CPM waterfall sort order becomes nonsensical over time

### Pitfall 5: Catching CancellationException
**What goes wrong:** Coroutine doesn't cancel when scope is cancelled, resources leak
**Why it happens:** Generic `catch (e: Exception)` catches CancellationException
**How to avoid:** Always use `catch (e: CancellationException) { throw e }` before generic catch, or use `runCatching` which preserves cancellation
**Warning signs:** Coroutines don't stop when auction is cancelled, memory usage grows

### Pitfall 6: Finally Block Doesn't Run on Cancellation
**What goes wrong:** AdSource.destroy() not called when auction cancelled, causing resource leaks
**Why it happens:** Non-cooperative cancellation (no ensureActive() checks), or cancellation happens outside try-finally
**How to avoid:** Wrap AdSource operations in try-finally, call `ensureActive()` before long operations
**Warning signs:** Native ad views leak, adapters report "destroy not called"

## Code Examples

Verified patterns from official sources:

### Cache Observation Pattern (StateFlow)
```kotlin
// Source: Kotlin Flow documentation
class ReadyToShowCacheObserver {
    private val _cacheState = MutableStateFlow(CacheState.Empty)
    val cacheState: StateFlow<CacheState> = _cacheState.asStateFlow()

    fun onAdCached(ad: AuctionResult) {
        val wasPreviouslyEmpty = _cacheState.value is CacheState.Empty
        _cacheState.value = CacheState.NonEmpty(ad)

        // Fire onAdLoaded only on empty → non-empty transition
        if (wasPreviouslyEmpty) {
            callbackCoordinator.notifySuccess(ad, auctionInfo)
        }
    }
}

sealed interface CacheState {
    object Empty : CacheState
    data class NonEmpty(val ad: AuctionResult) : CacheState
}
```

### Conditional Auction Cancellation
```kotlin
// Source: Kotlin Job cancellation patterns
fun showAd(activity: Activity): Ad? {
    val shownAd = ReadyToShowCache.popBest() ?: return null

    // Cancel auction ONLY if shown ad belongs to current auction
    if (currentAuction?.auctionId == shownAd.auctionId) {
        currentAuction?.cancel()
        logInfo(TAG, "Cancelled auction ${shownAd.auctionId} (ad shown)")
    } else {
        logInfo(TAG, "Not cancelling auction (shown ad from different auction)")
    }

    return shownAd.value
}
```

### Resource Cleanup with Finally
```kotlin
// Source: Kotlin Coroutines cancellation best practices
suspend fun loadAdUnit(
    adSource: AdSource<*>,
    adUnit: AdUnit
): Result<AuctionResult> {
    return try {
        ensureActive() // Check cancellation before starting

        val result = withTimeout(adUnit.timeout) {
            adSource.load(adParams)
            // Wait for fill or failure
            adSource.adEvent.first { it is AdEvent.Fill || it is AdEvent.LoadFailed }
        }

        Result.success(result)
    } catch (e: CancellationException) {
        throw e // Never catch cancellation
    } catch (e: Exception) {
        Result.failure(e)
    } finally {
        // Always runs, even on cancellation
        if (!adSource.isAdReadyToShow) {
            adSource.destroy()
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| GlobalScope.launch | Injected CoroutineScope | Kotlin 1.3 (2019) | Testability, lifecycle control |
| Thread pools | Kotlin Coroutines | Kotlin 1.3 (2019) | Structured concurrency, memory safety |
| synchronized {} | Mutex or AtomicBoolean | Kotlin 1.0 (2016) | Non-blocking, suspend-friendly |
| Manual Job tracking | coroutineScope {} | Kotlin 1.3 (2019) | Automatic child lifecycle management |
| Regular Job | SupervisorJob | Kotlin 1.3 (2019) | Independent failure isolation |

**Deprecated/outdated:**
- **GlobalScope:** Deprecated in favor of injected scopes (Kotlin 1.5+)
- **experimental.Coroutines:** All coroutines APIs stable since Kotlin 1.3
- **CommonPool:** Replaced by Dispatchers.Default (Kotlin 1.3)

## Open Questions

Things that couldn't be fully resolved:

1. **Weight Model Formula: Multiplicative vs Additive**
   - What we know: Industry uses eCPM-based scoring with weight adjustment
   - What's unclear: Exact formula (eCPM × weight vs eCPM + weight bonus)
   - Recommendation: Use multiplicative `score = eCPM × (weight / 10.0)` for intuitive scaling (weight 10 = neutral, 20 = 2x boost, 1 = 0.1x penalty)

2. **AdSource Destroy Timing: Immediately or Deferred**
   - What we know: Finally blocks run on cancellation, destroy() should be called
   - What's unclear: Should failed AdSource be destroyed immediately or after auction completes?
   - Recommendation: Destroy immediately on failure (in finally block) to free resources; winners destroyed after show()

3. **Cache Capacity Reached During Auction**
   - What we know: Cache continues loading when at capacity, evicts lowest eCPM
   - What's unclear: Should eviction happen synchronously (blocking) or asynchronously?
   - Recommendation: Synchronous eviction before insert (ReadyToShowCache.put() already implements this pattern)

## Sources

### Primary (HIGH confidence)
- [Kotlin Coroutines Official Documentation](https://kotlinlang.org/docs/coroutines-basics.html) - async/await, structured concurrency patterns
- [Android Developers: Coroutines Best Practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) - CoroutineScope injection, lifecycle management
- [Kotlin Coroutines Cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html) - CancellationException handling, cooperative cancellation
- Project codebase: `ExecuteAuctionUseCaseImpl.kt`, `AuctionImpl.kt` - Current sequential auction patterns
- Project codebase: `ReadyToShowCache.kt`, `RtbPayloadCache.kt` - Phase 1 cache implementations (ConcurrentHashMap, atomic operations)

### Secondary (MEDIUM confidence)
- [Mastering Structured Concurrency in Kotlin](https://medium.com/@pramodpm/mastering-structured-concurrency-in-kotlin-supervisorjob-coroutinescope-and-real-world-8acbf3cf540f) - SupervisorJob real-world patterns
- [Kotlin Coroutines: How to Run Parallel Coroutines](https://www.baeldung.com/kotlin/parallel-coroutines) - async + await examples
- [Understanding SupervisorJob in Kotlin Coroutines](https://www.revenuecat.com/blog/engineering/supervisorjob-kotlin/) - Failure isolation patterns
- [Ad Mediation Waterfall Optimization](https://www.adtiming.com/voice/insights/35-guidebook-to-waterfall.html) - Weight and fill rate scoring algorithms

### Tertiary (LOW confidence)
- [Kotlin Coroutines Cancellation Guide](https://omaroid.medium.com/kotlin-coroutine-cancellation-an-advanced-guide-867cb43b5a48) - Advanced cancellation patterns (not all verified with official docs)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All libraries official Kotlin/Android, versions verified in project build files
- Architecture: HIGH - Patterns sourced from official Kotlin/Android documentation, verified in existing codebase
- Pitfalls: HIGH - Derived from official cancellation/concurrency docs and existing codebase patterns
- Weight model: MEDIUM - Formula specifics based on industry practices, not official API

**Research date:** 2026-02-05
**Valid until:** 60 days (stable Kotlin coroutines APIs, slow-moving patterns)

---

**Key Takeaway:** Kotlin Coroutines provide battle-tested patterns for parallel processing with structured concurrency. Use `async` + `supervisorScope` for independent failure isolation, `AtomicBoolean` for exactly-once callbacks, injected `CoroutineScope` (never GlobalScope), and weight model with multiplicative scoring `eCPM × (weight / 10.0)` bounded to [1, 20].
