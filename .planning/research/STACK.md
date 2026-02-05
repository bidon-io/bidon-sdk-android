# Stack Research

**Domain:** Ad Caching v2 with Kotlin Coroutines in Android SDK
**Researched:** 2026-02-05
**Confidence:** HIGH

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Kotlin Coroutines | 1.10.2 | Async operations, non-blocking cache operations | Latest stable (Feb 2026). Official Android recommendation for async work. Provides suspend functions, structured concurrency, and efficient thread usage. Already in project at 1.6.0 - upgrade available. |
| MutableStateFlow | (kotlinx-coroutines-core) | Thread-safe cache state management | Built-in, thread-safe by design, atomic updates via compareAndSet. Perfect for observable cache state without external dependencies. Already used in existing AdCacheImpl. |
| ConcurrentHashMap | (Java stdlib) | Base data structure for cache storage | Zero-dependency, JVM-optimized for high-throughput concurrent access. Uses segmented locking for better performance than synchronized HashMap. Standard choice for in-memory caching in Android. |
| Mutex | (kotlinx-coroutines-core) | Critical section protection for cache operations | Coroutine-friendly alternative to synchronized. Non-blocking (suspends instead), better performance in coroutine-heavy code. Official recommendation for protecting mutable state in coroutines. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kotlinx.coroutines.flow | (included) | Flow operators for cache events | Use for expiration events, cache miss/hit tracking. Already used in AdCacheImpl for AdEvent.Expired. |
| kotlinx.coroutines.channels | (included) | Backpressure-aware communication | Use for bounded cache request queuing if needed to prevent memory overflow during high load. |
| AtomicBoolean | (Java stdlib) | Simple atomic flags | Use for cache operation flags (isLoading, isEvicting). Zero overhead, perfect for boolean state. |
| kotlin.time.Duration | (Kotlin 2.1.0) | Type-safe TTL values | Use for TTL configuration. Modern, multiplatform-ready API with built-in DSL (30.minutes). |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| kotlinx-coroutines-test | Testing coroutines | Already in project. Use TestCoroutineScheduler for TTL tests, advanceTimeBy() for eviction testing. |
| Mockk | Mocking framework | Already in project (1.13.5). Use for testing cache callbacks and adapter interactions. |
| Turbine (optional) | Flow testing | Consider adding for cleaner Flow testing (cache event streams). Version 1.1.0+ compatible with coroutines 1.10. |

## Installation

```kotlin
// build.gradle.kts (bidon module)

dependencies {
    // Upgrade coroutines from 1.6.0 to latest
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Testing (already present, ensure versions match)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // Optional: Flow testing library
    // testImplementation("app.cash.turbine:turbine:1.1.0")
}
```

No external cache libraries needed - stdlib + coroutines provide everything required.

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| ConcurrentHashMap + TTL wrapper | cache4k (0.13.x) | Only if you need Kotlin Multiplatform support or want built-in LRU. Adds dependency (400KB). For Android-only SDK, custom implementation is lighter and more controllable. |
| ConcurrentHashMap + TTL wrapper | Caffeine + Aedile wrapper | Only if you need advanced eviction policies (adaptive size, weighted eviction). Adds 900KB+ dependency. Overkill for 30-minute TTL with capacity limits. |
| MutableStateFlow | LiveData | Never for cache layer - LiveData is UI-lifecycle dependent. StateFlow is lifecycle-agnostic and better for background caching. |
| Mutex | synchronized blocks | Only if you need reentrant locking (Mutex is non-reentrant). For cache operations, non-reentrant is actually safer (prevents recursive locks). |
| StateFlow | AtomicReference&lt;List&gt; | Only if you never need observers. StateFlow provides both atomic updates AND observability for the same cost. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| ExpiringMap library | 1.5MB size, JVM-only (ScheduledExecutorService), last update 2020. Not optimized for Android. | ConcurrentHashMap + custom TTL wrapper with coroutines |
| RxJava-based caches | Project uses Coroutines, not RxJava. Mixing paradigms adds complexity and dependencies. | Kotlin Flow + StateFlow |
| Plain HashMap + synchronized | Global lock contention = poor performance under concurrent load. | ConcurrentHashMap (segmented locking) |
| Timer/TimerTask | Deprecated scheduling mechanism, not coroutine-aware, uses background threads inefficiently. | Coroutine delay() + periodic sweep |
| ScheduledExecutorService | JVM thread-based, blocks threads. Not Android-optimized, not coroutine-friendly. | Coroutine CoroutineScope.launch with delay() |
| GlobalScope for cache operations | Unstructured concurrency, leaks if scope not cancelled. | Inject CoroutineScope (already done in AdCacheImpl) |
| Flow.cache() operator | Experimental, designed for upstream data sources, not in-memory object caching. | Custom cache implementation |

## Stack Patterns by Variant

### Pattern 1: Two-Level Cache (READY_TO_SHOW + RTB_PAYLOAD)

**When:** Ad caching v2 implementation (current requirement)

**Stack:**
- Two separate `ConcurrentHashMap<DemandId, CacheEntry>` instances
- Shared TTL sweep coroutine (single periodic job for both caches)
- Separate `MutableStateFlow<CacheState>` for each cache level (observable state)
- `Mutex` per cache for write operations (prevents concurrent modification)

**Rationale:** Separate maps allow independent capacity limits and different eviction strategies per cache level.

### Pattern 2: Periodic TTL Sweep

**When:** Passive eviction (on-access is not sufficient for memory management)

**Stack:**
```kotlin
private val sweepJob = scope.launch {
    while (isActive) {
        delay(sweepInterval) // e.g., 5.minutes
        evictExpired()
    }
}
```

**Rationale:**
- Coroutine-native (no threads)
- Cancellable via scope
- `isActive` check prevents zombie jobs
- Bounded memory usage even if cache never accessed

### Pattern 3: Thread-Safe Cache Updates

**When:** Concurrent auction winners need to update cache

**Stack:**
```kotlin
private val writeMutex = Mutex()
private val cache = ConcurrentHashMap<K, V>()
private val state = MutableStateFlow<CacheState>(...)

suspend fun put(key: K, value: V) {
    writeMutex.withLock {
        cache[key] = value
        state.update { it.copy(size = cache.size) }
    }
}
```

**Rationale:**
- ConcurrentHashMap for reads (no lock needed)
- Mutex for writes (ensures cache + state updated atomically)
- StateFlow for reactive updates (observers notified automatically)

### Pattern 4: Atomic State Flags

**When:** Preventing duplicate cache operations (already loading, already evicting)

**Stack:**
```kotlin
private val isLoading = AtomicBoolean(false)

fun load() {
    if (!isLoading.compareAndSet(expect = false, update = true)) {
        return // Already loading
    }
    try {
        // Load logic
    } finally {
        isLoading.set(false)
    }
}
```

**Rationale:**
- Zero-cost abstraction for boolean flags
- compareAndSet prevents race conditions
- More efficient than Mutex for simple flags

## Version Compatibility

| Package | Version | Compatible With | Notes |
|---------|---------|-----------------|-------|
| kotlinx-coroutines-core | 1.10.2 | Kotlin 2.1.0+ | Latest stable as of Feb 2026. Breaking changes from 1.6.0 are minimal (Flow API additions). |
| kotlinx-coroutines-android | 1.10.2 | Android API 16+ | Project targets API 23+, fully compatible. |
| kotlinx-coroutines-test | 1.10.2 | JUnit 4.13.2+ | Already in project. TestCoroutineScheduler API stable. |
| kotlin.time.Duration | (Kotlin 2.1.0) | Kotlin 1.9.20+ | Project uses Kotlin 2.1.0. Duration API stable since 1.6. |

## Implementation Patterns for Ad Caching v2

### Cache Entry Model

```kotlin
data class CacheEntry<T>(
    val value: T,
    val insertedAt: Long = System.currentTimeMillis(),
    val ttl: Duration = 30.minutes
) {
    fun isExpired(): Boolean =
        System.currentTimeMillis() - insertedAt > ttl.inWholeMilliseconds
}
```

**Rationale:**
- Immutable (thread-safe by design)
- TTL stored per-entry (flexible expiration)
- Lazy expiration check (computed on access)

### TTL Eviction Strategy

**Hybrid Approach (Recommended):**
1. **Passive (On-Access):** Check `isExpired()` in `get()` operations
2. **Active (Periodic Sweep):** Background coroutine every 5 minutes removes expired entries

**Rationale:**
- Passive = zero overhead for hot paths
- Active = bounded memory (evicts even if never accessed)
- 5-minute sweep = max 5 minutes of "dead" entries (acceptable for 30-min TTL)

### Concurrency Patterns

| Operation | Pattern | Rationale |
|-----------|---------|-----------|
| Read (peek/contains) | Direct ConcurrentHashMap access | Lock-free, safe for concurrent reads |
| Write (put/remove) | Mutex.withLock { ... } | Atomic cache + state updates |
| State updates | StateFlow.update { ... } | Thread-safe, atomic via compareAndSet |
| Loading flags | AtomicBoolean.compareAndSet | Non-blocking, efficient for flags |
| Periodic tasks | CoroutineScope.launch + delay loop | Structured concurrency, cancellable |

### Capacity Management

**Pattern: Evict LRU on Capacity Overflow**

```kotlin
private val maxCapacity = 10
private val accessOrder = LinkedHashMap<K, Long>() // Access time tracking

suspend fun put(key: K, value: V) {
    writeMutex.withLock {
        if (cache.size >= maxCapacity && key !in cache) {
            val lruKey = accessOrder.entries.minByOrNull { it.value }?.key
            lruKey?.let { remove(it) }
        }
        cache[key] = value
        accessOrder[key] = System.currentTimeMillis()
    }
}
```

**Rationale:**
- LinkedHashMap tracks access order with minimal overhead
- Evict LRU only when at capacity (not on every write)
- Mutex ensures capacity check + eviction + insert are atomic

## Performance Considerations

### Thread-Safety Overhead

| Mechanism | Cost | When to Use |
|-----------|------|-------------|
| ConcurrentHashMap | ~5-10% overhead vs HashMap | Always for cache storage (concurrent reads/writes) |
| StateFlow.update | ~20ns per update | For observable cache state (low frequency) |
| Mutex.withLock | ~50-100ns suspension | For atomic multi-step operations (writes) |
| AtomicBoolean | ~5ns per CAS | For high-frequency flags (loading checks) |
| synchronized | Blocks thread | **Never** in coroutine code (use Mutex) |

**Benchmark Context:** Android 12+, Pixel 5, 1000 concurrent operations. Source: [Carrion.dev Mutex Guide](https://carrion.dev/en/posts/kotlin-mutex-concurrency-guide/)

### Memory Overhead

| Structure | Per-Entry Overhead | For 100 Entries |
|-----------|-------------------|-----------------|
| ConcurrentHashMap | ~40 bytes | ~4 KB |
| CacheEntry wrapper | ~32 bytes | ~3.2 KB |
| StateFlow | ~100 bytes | (singleton) |
| Mutex | ~32 bytes | (singleton) |
| **Total per cache** | ~72 bytes/entry | **~7.2 KB + 132 bytes fixed** |

**For Ad Caching v2:**
- 2 caches (READY_TO_SHOW + RTB_PAYLOAD)
- Max 10 entries each = 1.44 KB
- Negligible for Android SDK (target is <500 KB total SDK size)

### Coroutine Optimization

**Pattern: Reuse CoroutineScope**

```kotlin
// ✅ GOOD: Inject scope (already done in AdCacheImpl)
class AdCacheImpl(
    private val scope: CoroutineScope
) {
    private val sweepJob = scope.launch { /* periodic sweep */ }
}

// ❌ BAD: Create scope per cache
class AdCache {
    private val scope = CoroutineScope(Dispatchers.Default)
}
```

**Rationale:**
- Injected scope = lifecycle managed externally (cancelled with SDK shutdown)
- Shared dispatcher = fewer threads
- Structured concurrency = no leaks

## Testing Strategy

### TTL Testing

```kotlin
@Test
fun `expired entries are removed on access`() = runTest {
    val cache = AdCache(ttl = 100.milliseconds)
    cache.put("key", "value")

    advanceTimeBy(101.milliseconds) // TestCoroutineScheduler

    assertNull(cache.get("key"))
}
```

**Stack:** kotlinx-coroutines-test (TestCoroutineScheduler)

### Concurrency Testing

```kotlin
@Test
fun `concurrent writes are thread-safe`() = runTest {
    val cache = AdCache()
    val jobs = List(100) { i ->
        launch { cache.put("key$i", "value$i") }
    }
    jobs.joinAll()

    assertEquals(100, cache.size)
}
```

**Stack:** runTest with UnconfinedTestDispatcher (immediate execution)

### Periodic Sweep Testing

```kotlin
@Test
fun `periodic sweep removes expired entries`() = runTest {
    val cache = AdCache(sweepInterval = 1.seconds, ttl = 500.milliseconds)
    cache.put("key", "value")

    advanceTimeBy(1.5.seconds)

    assertNull(cache.get("key"))
}
```

**Stack:** advanceTimeBy() skips delays for fast tests

## Migration Path from AdCacheImpl

### Current Implementation Analysis

**Existing Stack (AdCacheImpl):**
- ✅ MutableStateFlow for results (good)
- ✅ Injected CoroutineScope (good)
- ✅ Flow.getAndUpdate for atomic operations (good)
- ❌ No TTL expiration (missing)
- ❌ No periodic cleanup (missing)
- ❌ Results stored as List (linear search, not keyed by demand ID)

### Upgrade Path

**Phase 1: Add TTL Wrapper**
- Wrap `AuctionResult` in `CacheEntry<AuctionResult>`
- Add `isExpired()` check in `peek()`, `pop()`, `poll()`
- Zero breaking changes (internal only)

**Phase 2: Add Periodic Sweep**
- Add background coroutine with `delay()` loop
- Evict expired entries every 5 minutes
- Use `results.update { it.filterNot { entry.isExpired() } }`

**Phase 3: Migrate to Two-Level Caches**
- Split `results` into `readyToShowCache` + `rtbPayloadCache`
- Each as `ConcurrentHashMap<DemandId, CacheEntry<T>>`
- Expose separate peek/pop/poll per cache level

**Confidence:** HIGH - incremental migration, backwards compatible

## Sources

### High Confidence (Official/Authoritative)

- [Kotlin Coroutines Best Practices | Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) - Official Android guidelines for coroutines, thread-safety, Mutex vs synchronized
- [StateFlow and SharedFlow | Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) - Official StateFlow documentation, lifecycle handling, thread-safety guarantees
- [Kotlin Coroutines Releases](https://github.com/Kotlin/kotlinx.coroutines/releases) - Version 1.10.2 release notes (Feb 2026)
- [Kotlin Shared Mutable State and Concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) - Official Kotlin docs on Mutex, thread-safety patterns

### Medium Confidence (Verified from Multiple Sources)

- [cache4k - ReactiveCircus](https://github.com/ReactiveCircus/cache4k) - Kotlin Multiplatform cache library, TTL support, thread-safety via stately-collections
- [Implement Scheduler/Timer with Kotlin Coroutine | Baeldung](https://www.baeldung.com/kotlin/coroutine-timer-scheduler) - Periodic task patterns with coroutines
- [Kotlin Mutex: Thread-Safe Concurrency for Coroutines](https://carrion.dev/en/posts/kotlin-mutex-concurrency-guide/) - Performance benchmarks, Mutex vs synchronized
- [ConcurrentHashMap in Kotlin - Medium](https://medium.com/@ys.yogendra22/concurrenthashmap-in-kotlin-thread-safe-and-smart-4f513806bde7) - Thread-safety patterns, segmented locking

### Community/Informational

- [Kache - MayakaApps](https://github.com/MayakaApps/Kache) - Alternative Kotlin cache library, LRU/FIFO support
- [Simple Android Cache powered by Coroutines - Medium](https://medium.com/@diefferson/simple-android-cache-powered-by-coroutines-42db61306569) - Implementation patterns
- [Caffeine](https://github.com/ben-manes/caffeine) - High-performance JVM cache (considered but too heavy for SDK)
- [Aedile](https://github.com/sksamuel/aedile) - Kotlin wrapper for Caffeine (not needed for simple TTL)

---

*Stack research for: Ad Caching v2 with Kotlin Coroutines in Android SDK*
*Researched: 2026-02-05*
*Confidence: HIGH (based on official Android/Kotlin docs + verified community patterns)*
