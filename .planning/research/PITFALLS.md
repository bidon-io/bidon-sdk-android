# Pitfalls Research: Ad Caching v2 for Android SDK

**Domain:** Ad Mediation SDK - Ad Caching with Kotlin Coroutines
**Researched:** 2026-02-05
**Confidence:** HIGH

## Critical Pitfalls

### Pitfall 1: Activity Context Retained by Singleton Cache

**What goes wrong:**
Application-wide caches (READY_TO_SHOW, RTB_PAYLOAD) are singleton-scoped, but loaded AdSource instances may hold strong references to Activity contexts passed during `loadAd()`. When Activities are destroyed but ads remain cached for 30 minutes, the AdSource prevents Activity garbage collection, causing massive memory leaks (entire Activity hierarchy retained).

**Why it happens:**
Ad network SDKs (AdMob, Meta, etc.) require Activity context for ad loading and often store it internally. Developers pass Activity context to maintain the chain, not realizing the singleton cache creates a lifecycle mismatch.

**How to avoid:**
- Use ApplicationContext wherever possible in AdSource implementations
- For AdSources that MUST use Activity, implement WeakReference pattern:
  ```kotlin
  private var activityRef: WeakReference<Activity>? = null
  ```
- Mandatory destroy() call in periodic sweep (every 5 min) to release Activity references
- Add LeakCanary checks specifically for cached AdSource → Activity chains
- Document in adapter contracts: "Adapters MUST NOT retain strong Activity references beyond show()"

**Warning signs:**
- LeakCanary reports: "Activity leaked: retained by AdSource in READY_TO_SHOW cache"
- Memory profiler shows Activities surviving beyond expected lifecycle
- OOM crashes increase with repeated loadAd() → back navigation patterns
- Heap dump shows multiple Activity instances when only one should exist

**Phase to address:**
Phase 1 (Foundation) - Establish strict AdSource lifecycle contracts and WeakReference patterns before implementing parallel loading.

---

### Pitfall 2: Race Condition Between notifyAdLoadedIfNeeded() and showAd()

**What goes wrong:**
`notifyAdLoadedIfNeeded()` runs in RTB/CPM coroutine threads while `showAd()` runs on main thread. Without proper synchronization, cache.put() + getBest() creates race: CPM[0] loads → put() → getBest() returns null because not yet committed → onAdLoaded never fires OR fires with wrong ad.

**Why it happens:**
ConcurrentHashMap guarantees atomic individual operations BUT compound operations (put + notify, getBest + isEmpty) are NOT atomic. The spec requires "put(entry) + notifyAdLoadedIfNeeded() atomic" but implementations miss this.

**How to avoid:**
```kotlin
private val cacheLock = Any()

fun putAndNotify(entry: ReadyToShowEntry) {
    synchronized(cacheLock) {
        cache.put(entry)
        notifyAdLoadedIfNeeded()  // Must happen atomically
    }
}

fun getBestForShow(): ReadyToShowEntry? {
    synchronized(cacheLock) {
        return cache.getBest()?.also {
            cache.remove(it.uid)  // Atomic get-and-remove
        }
    }
}
```
- Use Mutex.withLock for suspend functions (preferred in coroutine contexts)
- AtomicBoolean for callback flag is NOT enough - protects flag, not cache state
- Code reviews must verify: every cache.put() that triggers callback is synchronized

**Warning signs:**
- Flaky test: sometimes onAdLoaded fires, sometimes doesn't (non-deterministic)
- Production logs: "showAd() called but cache isEmpty=true" immediately after loadAd()
- Race detector tools show: Thread-1 in put(), Thread-2 in getBest() simultaneously
- User reports: "Ad loaded notification but 'No ad available' when showing"

**Phase to address:**
Phase 1 (Foundation) - Critical synchronization infrastructure before any parallel processing.

---

### Pitfall 3: Coroutine Cancellation Doesn't Clean Up Finally Blocks with Suspending Calls

**What goes wrong:**
When `showAd()` cancels CPM loading coroutines, cleanup code in finally blocks with suspending calls (e.g., `adSource.destroy()` if it's suspend) throws CancellationException and aborts cleanup mid-execution. Result: AdSource instances leak memory, Activity references retained, native ad resources not released.

**Why it happens:**
Kotlin coroutines check for cancellation at EVERY suspension point, including inside finally blocks. Spec says "cleanup on cancel" but doesn't warn: suspend in finally = cleanup aborted. Recent 2025 analysis confirms: "suspension point inside finally will throw CancellationException and abort the finally block mid-way."

**How to avoid:**
```kotlin
try {
    loadCpmAds(cpmGroup)
} finally {
    // WRONG: cleanup will be aborted if cancelled
    // cpmGroup.forEach { it.adSource.destroy() }

    // CORRECT: NonCancellable context guarantees cleanup
    withContext(NonCancellable) {
        cpmGroup.forEach { adSource ->
            try {
                adSource.destroy()  // Even if suspend, won't throw CancellationException
            } catch (e: Exception) {
                logError("Cleanup failed", e)
            }
        }
    }
}
```
- ALL resource cleanup in finally blocks MUST use `withContext(NonCancellable)`
- Document in team guidelines: "Never suspend in finally without NonCancellable"
- Adapter destroy() methods should be non-suspending where possible (use callbacks)

**Warning signs:**
- Memory profiler: AdSource instances accumulating after showAd() cancellations
- Logs: "Finally block cleanup started" but never see "cleanup completed"
- Native crashes: "Ad view already attached" (AdSource not properly destroyed)
- Heap analysis: Cancelled coroutines still holding AdSource references

**Phase to address:**
Phase 2 (Parallel Processing) - Critical before implementing RTB/CPM cancellation on showAd().

---

### Pitfall 4: TTL Clock Skew and Negative TTL Issues

**What goes wrong:**
`System.currentTimeMillis()` is wall-clock time, susceptible to user changing device time or NTP corrections. Scenario: User sets clock forward 1 hour → all cache entries instantly "expired" → cache appears empty → unnecessary re-auctions. Worse: User sets clock back → entries with "negative age" never expire → stale 5-hour-old ads shown.

**Why it happens:**
Spec uses `createdAt: Long (timestamp)` with `System.currentTimeMillis()` which is NOT monotonic. Android documentation warns: "currentTimeMillis() can jump backwards or forwards" but developers default to it for simplicity.

**How to avoid:**
```kotlin
// WRONG: Wall-clock time
val createdAt = System.currentTimeMillis()
val isExpired = (System.currentTimeMillis() - createdAt) > TTL

// CORRECT: Monotonic time
val createdAt = SystemClock.elapsedRealtime()  // Android-specific, monotonic
val isExpired = (SystemClock.elapsedRealtime() - createdAt) > TTL

// For tests, inject time provider:
interface TimeProvider {
    fun now(): Long
}
class MonotonicTimeProvider : TimeProvider {
    override fun now() = SystemClock.elapsedRealtime()
}
```
- Use `SystemClock.elapsedRealtime()` (Android) or `System.nanoTime()` (JVM) for durations
- Add safety check: `if (age < 0) { removeEntry() }` to handle clock skew edge cases
- Document: "createdAt uses elapsedRealtime, NOT currentTimeMillis"

**Warning signs:**
- User reports: "Ads disappeared after changing timezone"
- Logs: TTL check shows negative age: `age = -3600000ms`
- Analytics spike: Cache hit rate drops to 0% at specific times (correlates with NTP sync)
- Tests fail sporadically when run across midnight boundary

**Phase to address:**
Phase 1 (Foundation) - Fix time source before implementing any TTL logic.

---

### Pitfall 5: Duplicate DemandId Policy Not Enforced Atomically

**What goes wrong:**
Spec says: "If demandId exists, replace ONLY if newEcpm > oldEcpm, else discard new." Implementation: `cache.contains(demandId)` (check) → `cache.put(entry)` (update) creates race condition. Two threads load same demandId simultaneously → both check "not exists" → both put → cache has duplicates → getBest() returns wrong winner.

**Why it happens:**
ConcurrentHashMap.put() is atomic, but check-then-act pattern is NOT atomic. Spec's duplicate policy requires compare-and-swap logic but developers implement as two separate operations.

**How to avoid:**
```kotlin
// WRONG: Non-atomic check-then-act
if (cache.contains(demandId)) {
    val old = cache.get(demandId)
    if (newEcpm > old.ecpm) {
        old.adSource.destroy()
        cache.put(demandId, newEntry)
    }
} else {
    cache.put(demandId, newEntry)
}

// CORRECT: Atomic compute
cache.compute(demandId) { key, existingEntry ->
    when {
        existingEntry == null -> newEntry  // No existing, add new
        newEntry.ecpm > existingEntry.ecpm -> {
            existingEntry.adSource.destroy()  // Cleanup old
            newEntry  // Replace with new
        }
        else -> {
            newEntry.adSource.destroy()  // Discard new
            existingEntry  // Keep old
        }
    }
}
```
- Use ConcurrentHashMap.compute() or computeIfPresent() for atomic compare-and-swap
- Alternative: Synchronized block around entire check-update-destroy sequence
- Test with concurrent load scenarios: two RTB auctions returning same demandId

**Warning signs:**
- Cache size grows beyond expected (duplicates accumulating)
- Stats show: same demandId appears multiple times in single auction result
- getBest() returns lower eCPM than expected (duplicate with lower price won)
- destroy() throws "already destroyed" errors (duplicate cleanup attempts)

**Phase to address:**
Phase 1 (Foundation) - Before parallel RTB/CPM processing starts adding entries concurrently.

---

### Pitfall 6: Periodic Sweep Job Survives Activity/Fragment Destruction

**What goes wrong:**
Spec says: "Periodic sweep lifecycle: Ad-instance scoped." Implementation launches sweep in `lifecycleScope` or `viewModelScope`, but if sweep interval is 5 minutes and user navigates away after 1 minute, sweep continues in background for 4 more minutes, holding references to destroyed cache instance and logging errors.

**Why it happens:**
"Ad-instance scoped" is ambiguous: does it mean Interstitial/Rewarded object lifecycle OR Activity lifecycle? If sweep uses GlobalScope or applicationScope, it outlives the ad instance. Even lifecycleScope can leak if not properly cancelled.

**How to avoid:**
```kotlin
class AdCacheImpl(
    private val scope: CoroutineScope  // Injected, NOT GlobalScope
) {
    private var sweepJob: Job? = null

    fun startPeriodicSweep() {
        sweepJob = scope.launch {
            while (isActive) {  // Check cancellation
                delay(5.minutes.inWholeMilliseconds)
                withContext(NonCancellable) {  // Ensure cleanup completes
                    sweepExpiredEntries()
                }
            }
        }
    }

    fun clear() {
        sweepJob?.cancel()  // Cancel sweep when cache cleared
        sweepJob = null
        // ... rest of cleanup
    }
}
```
- Inject CoroutineScope (don't use GlobalScope internally)
- Cancel sweep job explicitly in clear() and destroy()
- Document: "Sweep runs until clear() or scope cancelled"
- Test: verify sweep stops when ad instance destroyed

**Warning signs:**
- Logs: "Sweep running" messages appear after Activity destroyed
- Memory profiler: CoroutineContext instances accumulating
- Crash: "Cache already cleared" in sweep callback
- LeakCanary: "Job retained by GlobalScope" after Fragment navigation

**Phase to address:**
Phase 1 (Foundation) - Establish proper scope management before implementing sweep.

---

### Pitfall 7: RTB Payload Invalidation Not Propagated to Token Collection

**What goes wrong:**
RTB[0] loads with cached payload → adapter returns error "Invalid payload" → cache removes entry from RTB_PAYLOAD → BUT next loadAd() STILL skips token collection for that demandId because removal happened mid-auction. Result: perpetual "no RTB from Meta" until app restart.

**Why it happens:**
Spec says: "If payload invalid → remove from RTB_PAYLOAD → next auction collects fresh tokens." BUT getCachedDemandIds() is called at START of auction, before any loading. Cache state changes during auction aren't reflected until NEXT auction. Creates 1-auction lag.

**How to avoid:**
```kotlin
// WRONG: Static snapshot at auction start
val skipDemandIds = rtbPayloadCache.getCachedDemandIds()
val tokens = tokenCollector.collect(skipDemandIds)

// CORRECT: Re-check after invalidation
suspend fun loadRtbWithPayload(adUnit: AdUnit): Result {
    val result = adSource.load(payload)
    if (result.isFailure) {
        rtbPayloadCache.remove(adUnit.uid)  // Invalidate
        // Option 1: Collect fresh token NOW and retry
        val freshToken = tokenCollector.collectSingle(adUnit.demandId)
        return adSource.load(freshToken)

        // Option 2: Mark for next auction
        invalidatedDemands.add(adUnit.demandId)
    }
    return result
}

// In next auction:
val skipDemandIds = rtbPayloadCache.getCachedDemandIds() - invalidatedDemands
```
- Track invalidated demands in current auction
- Option A: Immediate retry with fresh token (adds latency)
- Option B: Skip cache for invalidated demands in next auction (simpler)
- Add metrics: "RTB payload invalidation rate per demandId"

**Warning signs:**
- Logs: "Payload invalid for meta_an, removed from cache"
- Next log: "Skipping token collection for meta_an (cached)"
- Stats: Specific network shows 0% fill rate for multiple auctions in a row
- User reports: "Ads from [network] stopped appearing"

**Phase to address:**
Phase 2 (Parallel Processing) - After RTB payload caching implemented, before production.

---

### Pitfall 8: onAdLoaded Callback Fired Multiple Times Due to Warm Start Race

**What goes wrong:**
Warm start: `if (!READY_TO_SHOW.isEmpty()) { onAdLoaded() }` fires immediately, sets `adLoadedCallbackFired = true`. BUT if auction in progress ALSO completes and calls `notifyAdLoadedIfNeeded()` in parallel, race condition: both threads read `false` before either sets `true` → callback fires twice → user code breaks (assumes single call).

**Why it happens:**
AtomicBoolean.compareAndSet() is atomic, but TWO separate code paths (warm start immediate + auction completion) race to call it. If both check before either updates, both succeed. Spec assumes warm start blocks auction start, but implementation runs them in parallel.

**How to avoid:**
```kotlin
// WRONG: Two separate call sites
fun loadAd() {
    if (!readyCache.isEmpty()) {
        notifyAdLoadedIfNeeded()  // Call site 1
    }
    launchAuction {
        onAuctionComplete {
            notifyAdLoadedIfNeeded()  // Call site 2 - RACE!
        }
    }
}

// CORRECT: Single call site with lock
private val callbackLock = Mutex()

suspend fun notifyAdLoadedIfNeeded() {
    callbackLock.withLock {
        if (adLoadedCallbackFired.compareAndSet(false, true)) {
            val best = cache.getBest()
            best?.let { listener.onAdLoaded(it.ad, auctionInfo) }
        }
    }
}

// OR: Combine warm start + auction into single flow
fun loadAd() {
    val cachedAd = cache.peek()
    if (cachedAd != null) {
        listener.onAdLoaded(cachedAd.ad, auctionInfo)
        adLoadedCallbackFired.set(true)
        // Auction continues in background, won't notify
    }
    launchAuction { ... }  // This path now knows callback already fired
}
```
- Protect notifyAdLoadedIfNeeded() with Mutex (suspend) or synchronized block
- Alternative: Single flow with explicit "callback already fired" checks in auction completion
- Test: Concurrent loadAd() calls with pre-filled cache

**Warning signs:**
- User crash reports: "Callback invoked twice" or state corruption in app code
- Logs: Two "onAdLoaded" entries for single loadAd() call
- Flaky tests: sometimes passes (no race), sometimes fails (race occurred)
- Analytics: Impression count > ad show count (counted twice)

**Phase to address:**
Phase 2 (Parallel Processing) - Critical when implementing warm start optimization.

---

### Pitfall 9: Cache Growing Unbounded with High-Frequency loadAd() Calls

**What goes wrong:**
No cache size limit specified except TTL. User calls `loadAd()` every 10 seconds (refresh logic) → RTB waterfall returns 5 units/auction → 5 min = 30 auctions = 150 cached entries → OOM crash. TTL sweep runs every 5 min but entries are fresh (< 30 min), so nothing removed.

**Why it happens:**
Spec decision: "Cache size limit: NO" relies on 30-min TTL for cleanup. But high-frequency loading (banner auto-refresh, interstitial pre-cache) creates entries faster than TTL can expire them. ConcurrentHashMap grows indefinitely.

**How to avoid:**
```kotlin
// Strategy 1: Max entries per cache (LRU-like)
interface ReadyToShowCache {
    fun put(entry: ReadyToShowEntry) {
        if (size() >= MAX_ENTRIES) {
            val oldest = getOldest()  // By createdAt
            oldest?.adSource?.destroy()
            remove(oldest.uid)
        }
        internalPut(entry)
    }
}

// Strategy 2: Max entries per demandId
private val maxPerDemand = 2
fun put(entry: ReadyToShowEntry) {
    val existing = getAll().filter { it.demandId == entry.demandId }
    if (existing.size >= maxPerDemand) {
        existing.sortedBy { it.ecpm }.first().let { worst ->
            worst.adSource.destroy()
            remove(worst.uid)
        }
    }
    internalPut(entry)
}

// Strategy 3: Memory pressure monitoring
private val maxMemoryMb = 50
fun put(entry: ReadyToShowEntry) {
    if (estimatedCacheSizeMb() > maxMemoryMb) {
        evictLowestEcpm()
    }
    internalPut(entry)
}
```
- Implement bounded cache (default 50-100 entries) even with TTL
- Document: "High-frequency loadAd() (< 1 min) requires manual cache clearing"
- Add metrics: cache size over time, peak size before OOM
- Configuration: Allow apps to set maxCacheSize

**Warning signs:**
- Memory profiler: Heap usage grows linearly with time
- Logs: Cache size grows to hundreds of entries
- OOM crash: "OutOfMemoryError" with large READY_TO_SHOW HashMap in heap dump
- Performance: getBest() becomes slow (linear scan of hundreds of entries)

**Phase to address:**
Phase 3 (Optimization) - Add bounded cache after core functionality proven stable.

---

### Pitfall 10: showAd() Cancels CPM Loading But Doesn't Cancel RTB Payload Saving

**What goes wrong:**
Spec says: "showAd() → cancel ongoing CPM loading." Implementation cancels cpmProcessingJob but NOT rtbProcessingJob. Result: RTB[1..N] continue saving payloads to cache for 10+ seconds after show, wasting CPU/network. Worse: If show() fails, those payloads are corrupted (saved during interruption).

**Why it happens:**
Spec says "RTB payload caching NOT cancelled (data already available)" but misses case: What if saving payload takes 5 seconds (large payload, slow serialization)? Cancellation should stop ALL background work, not just "loading" work.

**How to avoid:**
```kotlin
// WRONG: Only cancel CPM
fun showAd() {
    cpmProcessingJob?.cancel()  // Cancel loading
    // rtbProcessingJob continues saving...
}

// CORRECT: Cancel ALL auction work
fun showAd() {
    auctionScope.cancel()  // Cancel parent scope = all children
    // Cleanup in finally blocks with NonCancellable still runs
}

// OR: Selective cancellation with check
suspend fun saveRtbPayload(entry: RtbPayloadEntry) {
    ensureActive()  // Check if cancelled before expensive operation
    cache.put(entry)  // Fast operation, let it complete
}

suspend fun saveRtbPayloadWithSerialization(entry: RtbPayloadEntry) {
    val serialized = withContext(Dispatchers.IO) {
        ensureActive()  // Check before CPU-intensive work
        serializePayload(entry.payload)  // Expensive
    }
    cache.put(entry.uid, serialized)
}
```
- Cancel entire auction scope on showAd(), not individual jobs
- Add ensureActive() checks before expensive operations in RTB saving
- Document: "All auction work stops on show, including payload serialization"
- Measure: Time from showAd() to all jobs cancelled (should be < 100ms)

**Warning signs:**
- Logs: "Saving RTB payload" messages continue 5+ seconds after "Ad shown"
- CPU profiler: High CPU usage after showAd() (background saving)
- Flaky tests: Race condition where show() happens during payload save
- Stats: RTB payload save errors spike (interrupted saves)

**Phase to address:**
Phase 2 (Parallel Processing) - When implementing showAd() cancellation logic.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Using GlobalScope for auction coroutines | Simple, no scope management | Memory leaks, cannot cancel properly | Never - always inject scope |
| Skipping WeakReference for Activity in AdSource | Faster implementation | Activity leaks with singleton cache | Never - memory leaks unacceptable |
| Single-threaded cache access (no synchronization) | Simpler code, no locks | Race conditions with parallel RTB/CPM | Only for prototype/Phase 0, never production |
| Using wall-clock time (currentTimeMillis) for TTL | Standard Java pattern | Clock skew breaks expiration | Never - use elapsedRealtime |
| No cache size limit (TTL only) | Simpler eviction logic | OOM with high-frequency loading | Acceptable for Phase 1-2, must fix by Phase 3 |
| Lazy sweep only (no periodic sweep) | Less background work | Stale ads accumulate, Activity refs retained | Only if TTL < 5 min and low load frequency |
| Synchronous AdSource.destroy() instead of suspend | Avoids NonCancellable issues | May block caller thread | Acceptable if adapter cleanup is fast (< 50ms) |
| Ignoring invalid payload errors (keep in cache) | Higher cache hit rate | Perpetual load failures for broken payloads | Never - invalidation is critical |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| AdMob RTB adapter | Passing Activity context to AdRequest, retained by cache | Use ApplicationContext for AdRequest, pass Activity only to show() |
| Meta Audience Network | Calling destroy() from non-main thread | Post destroy() to main looper or use withContext(Dispatchers.Main) |
| BidMachine payload | Caching payload as-is, but it contains timestamp that expires | Extract expiration timestamp, evict payload when expired (separate from 30min TTL) |
| Unity Ads | load() is not thread-safe, concurrent calls crash | Serialize Unity load() calls with Mutex per demandId |
| AppLovin | Adapter holds strong ref to listener callback (leak) | Implement listener with WeakReference or unregister in destroy() |
| Amazon APS | Token expires in 10 minutes, but cache TTL is 30 minutes | Store token expiration separately, evict when token expired (not cache TTL) |
| Yandex | destroy() triggers ad impression counting (side effect) | Call destroy() only AFTER show() or on explicit cancel, not on cache eviction |

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Linear scan in getBest() (no indexing) | showAd() latency increases with cache size | Use TreeMap sorted by eCPM or maintain sorted list | Cache > 20 entries |
| Synchronous destroy() blocks auction thread | Auction stuck waiting for AdSource cleanup | Make destroy() async or use Dispatchers.IO | destroy() > 100ms (network cleanup) |
| Serializing entire cache on every put() | High CPU on cache updates | Only serialize individual entries or use in-memory cache | Cache > 10 entries |
| Not batching RTB payload saves | Too many small I/O operations | Collect payloads in batch, save once at end | RTB waterfall > 5 units |
| Mutex contention on cache lock | Threads waiting for lock, low throughput | Use ReadWriteLock or fine-grained locking (per demandId) | > 5 concurrent auctions |
| Deep copying AdUnit on cache | High memory allocation, GC pressure | Share immutable AdUnit, copy only mutable fields | Cache updates > 10/sec |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Caching ad payload without validation | Malicious payload injection if server compromised | Validate payload schema before caching, reject unknown fields |
| Logging full RTB payload (contains PII) | GDPR violation, user tracking data exposed | Log only demandId + ecpm, redact payload in logs |
| Not clearing cache on logout | Next user sees previous user's targeted ads | Clear all caches on user logout or profile switch |
| Persisting cache to disk without encryption | Ad targeting data readable by other apps | Keep cache in-memory only OR encrypt with Android Keystore |
| Including user ID in cache key | Cache leaks user correlation | Use ad unit UID only, derive targeting from separate user profile |

## "Looks Done But Isn't" Checklist

- [ ] **RTB/CPM parallel processing:** Often missing cancellation in finally blocks — verify NonCancellable used in all cleanup
- [ ] **TTL expiration:** Often using currentTimeMillis() instead of elapsedRealtime() — verify monotonic time source
- [ ] **Duplicate demandId handling:** Often check-then-act pattern — verify atomic compute() or synchronized block
- [ ] **onAdLoaded callback:** Often fires twice in warm start — verify single call site or Mutex protection
- [ ] **Periodic sweep lifecycle:** Often uses GlobalScope — verify injected scope and explicit cancellation
- [ ] **Activity context leaks:** Often AdSource holds strong ref — verify WeakReference or ApplicationContext
- [ ] **showAd() cancellation:** Often cancels CPM but not RTB saving — verify entire auctionScope cancelled
- [ ] **Invalid payload handling:** Often kept in cache — verify remove() called and skip tokens in next auction
- [ ] **Cache size growth:** Often unbounded — verify eviction policy or size limit
- [ ] **Coroutine exception handling:** Often swallows errors — verify CancellationException re-thrown and logged

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Activity context leak | MEDIUM | 1. Add LeakCanary to detect, 2. Refactor AdSource to WeakReference, 3. Add destroy() audit in sweep, 4. Regression test with heap dumps |
| Race condition in callback | LOW | 1. Add Mutex to notifyAdLoadedIfNeeded(), 2. Add test with concurrent loadAd(), 3. Code review for other atomicity issues |
| Coroutine cleanup aborted | MEDIUM | 1. Wrap all finally blocks with NonCancellable, 2. Make destroy() non-suspend where possible, 3. Add cleanup completion logging |
| Clock skew breaks TTL | LOW | 1. Replace currentTimeMillis() with elapsedRealtime(), 2. Add negative age safety check, 3. Test with simulated clock changes |
| Duplicate demandId | LOW | 1. Replace put() with compute(), 2. Add concurrent load test for same demandId, 3. Verify destroy() called on replaced entry |
| Periodic sweep leak | LOW | 1. Inject scope instead of GlobalScope, 2. Cancel sweep in clear(), 3. Test sweep stops on destroy |
| Invalid payload loop | LOW | 1. Track invalidated demands, 2. Skip cache for invalidated in next auction, 3. Add metrics for invalidation rate |
| Callback fired twice | LOW | 1. Add Mutex or combine call sites, 2. Test warm start + auction concurrency, 3. Add assertion for single callback |
| Unbounded cache growth | MEDIUM | 1. Add max size limit (default 100), 2. Implement LRU eviction, 3. Add cache size monitoring, 4. Document limits |
| RTB saving not cancelled | LOW | 1. Cancel auctionScope on show, 2. Add ensureActive() before expensive ops, 3. Measure cancellation time |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Activity context leaks | Phase 1 (Foundation) | LeakCanary passes with 10+ loadAd/back cycles |
| Race in callback/getBest | Phase 1 (Foundation) | 1000 concurrent test runs, 0 duplicates or nulls |
| Coroutine cleanup aborted | Phase 2 (Parallel Processing) | Cancellation test: all AdSources destroyed |
| Clock skew breaks TTL | Phase 1 (Foundation) | Test with SystemClock mock, +/- 1 hour jumps |
| Duplicate demandId non-atomic | Phase 1 (Foundation) | Concurrent same-demandId load, only higher eCPM cached |
| Periodic sweep lifecycle | Phase 1 (Foundation) | Destroy test: sweep logs stop immediately |
| Invalid payload not handled | Phase 2 (Parallel Processing) | Inject bad payload, verify removal + token collected next |
| onAdLoaded fired twice | Phase 2 (Parallel Processing) | Warm start + concurrent auction: single callback |
| Unbounded cache growth | Phase 3 (Optimization) | Load 200 times: cache size <= maxSize |
| showAd() doesn't cancel RTB | Phase 2 (Parallel Processing) | show() during save: jobs cancelled in < 100ms |

## Sources

**Kotlin Coroutines - Memory Leaks & Thread Safety:**
- [Unavoidable memory leak when using coroutines - Kotlin Discussions](https://discuss.kotlinlang.org/t/unavoidable-memory-leak-when-using-coroutines/11603)
- [Memory leak when using coroutine context - Kotlin Discussions](https://discuss.kotlinlang.org/t/memory-leak-when-using-coroutine-context/28332)
- [Does Your Kotlin Async Cache Leak User Data Between Coroutines? - Medium](https://sam-cooper.medium.com/is-your-kotlin-async-cache-leak-user-data-between-coroutines-0b6e9fecad11)
- [Navigating Challenges with Kotlin Coroutines in Android Development - Medium](https://medium.com/@riztech.dev/navigating-challenges-with-kotlin-coroutines-in-android-development-a-guide-to-mitigating-pitfalls-d10c05ac82fd)
- [Bridging the gap between coroutines, threads, and concurrency problems - Android Developers](https://medium.com/androiddevelopers/bridging-the-gap-between-coroutines-jvm-threads-and-concurrency-problems-864e563bd7c)

**ConcurrentHashMap & Atomic Operations:**
- [How to Use Kotlin ConcurrentHashMap for Faster Apps - DhiWise](https://www.dhiwise.com/post/how-kotlin-concurrenthashmap-can-simplify-your-code)
- [Understanding ConcurrentHashMap in Kotlin/Java](https://rommansabbir.com/understanding-concurrenthashmap-in-kotlinjava)
- [ConcurrentHashMap in Kotlin — Thread-Safe and Smart - Medium](https://medium.com/@ys.yogendra22/concurrenthashmap-in-kotlin-thread-safe-and-smart-4f513806bde7)
- [How to avoid Race Conditions in Kotlin/Android - Medium](https://medium.com/@me.zahidul/why-synchronization-matters-preventing-race-conditions-in-kotlin-android-511fcd62a8df)
- [VNA03-J. Do not assume that a group of calls to independently atomic methods is atomic - SEI CERT](https://wiki.sei.cmu.edu/confluence/display/java/VNA03-J.+Do+not+assume+that+a+group+of+calls+to+independently+atomic+methods+is+atomic)
- [Shared mutable state and concurrency - Kotlin Documentation](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)

**Activity Lifecycle & Memory Leaks:**
- [Understanding Memory Leaks in Kotlin Android - Medium](https://medium.com/@afifaali931/understanding-memory-leaks-in-kotlin-android-causes-and-solutions-dbc5eb61b27c)
- [Memory Leak in Android — Understand Root Cause and its fixes - Medium](https://medium.com/@manishkumar_75473/memory-leak-in-android-understand-root-cause-and-its-fixes-b81041b88c9a)
- [How to avoid memory leak — Android and JVM languages - Medium](https://weidianhuang.medium.com/how-to-avoid-memory-leak-android-and-jvm-languages-b5283c58fe1f)
- [Preventing and detecting memory leaks in Android apps - LogRocket Blog](https://blog.logrocket.com/preventing-detecting-memory-leaks-android-apps/)
- [Avoiding Memory Leaks in Kotlin and Jetpack Android Development - Medium](https://medium.com/@fauzisho/avoiding-memory-leaks-in-kotlin-and-jetpack-android-development-tips-and-examples-86a17bab47a7)

**Coroutine Cancellation & Cleanup:**
- [Internal Mechanism of Coroutine Cancellation in Kotlin - Medium](https://medium.com/@mahesh31.ambekar/internal-mechanism-of-coroutine-cancellation-in-kotlin-b239188f87a7)
- [Cancellation and timeouts - Kotlin Documentation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
- [Best Practices for Coroutine Cancellation and Non-Cancellation in Kotlin - Medium](https://medium.com/@prakash_ranjan/best-practices-for-coroutine-cancellation-and-non-cancellation-in-kotlin-3ae214cb1c0e)
- [Coroutine Cancellation Looks Simple — Until It Breaks Your App - droidcon](https://www.droidcon.com/2025/12/11/%F0%9F%92%A3-coroutine-cancellation-looks-simple-until-it-breaks-your-app-the-hidden-traps-every-android-engineer-must-know/)
- [Cancellation in coroutines - Android Developers](https://medium.com/androiddevelopers/cancellation-in-coroutines-aa6b90163629)

**Ad Mediation & Caching Best Practices:**
- [Get started - Mobile Ads SDK for Android - Google Developers](https://developers.google.com/ad-manager/mobile-ads-sdk/android/mediation)
- [Cache Invalidation vs. Expiration: Best Practices](https://daily.dev/blog/cache-invalidation-vs-expiration-best-practices)
- [Mastering Android Caching Strategies - Medium](https://medium.com/@swapnilksh08/mastering-android-caching-strategies-a-complete-developers-guide-to-building-lightning-fast-apps-95e844f7ed3b)

**RTB & Bidding:**
- [Best Practices for RTB Applications - Google Developers](https://developers.google.com/authorized-buyers/rtb/practices-guide)
- [What is SDK Bidding and How Can It Benefit Your Mobile App? - Ad.plus](https://blog.ad.plus/sdk-bidding/)

---

*Pitfalls research for: Ad Caching v2 for Bidon Android SDK*
*Researched: 2026-02-05*
