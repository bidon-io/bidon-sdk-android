# Phase 1: Foundation (Cache Stores) - Research

**Researched:** 2026-02-05
**Domain:** Thread-safe in-memory caching with TTL expiration in Kotlin/Android
**Confidence:** HIGH

## Summary

This phase requires implementing two thread-safe cache stores (ReadyToShowCache and RtbPayloadCache) for the Bidon SDK's ad caching system. The caches must handle TTL-based expiration, duplicate detection with eCPM comparison, memory-aware capacity limits, and proper thread safety using Kotlin's concurrent primitives.

The research reveals that the standard approach combines ConcurrentHashMap for thread-safe storage with atomic operations (compute/computeIfAbsent) for compound operations, SystemClock.elapsedRealtime() for monotonic time tracking, and Kotlin Coroutines with Mutex for coordinating access patterns. Android's LruCache provides memory management patterns but is insufficient for TTL requirements, so a custom implementation is necessary.

**Primary recommendation:** Use ConcurrentHashMap with atomic compute() operations for duplicate detection, SystemClock.elapsedRealtime() for TTL tracking, and Kotlin singleton objects for application-wide scope. Implement lazy eviction on access and periodic sweep via coroutine with while(true) + delay(5.minutes).

## Standard Stack

The established libraries/tools for thread-safe caching in Kotlin/Android:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| java.util.concurrent.ConcurrentHashMap | Java 8+ | Thread-safe map with lock striping | Built-in to JVM, optimized for concurrent access, atomic operations |
| android.os.SystemClock | Android API 1+ | Monotonic time source | Platform-provided, guarantees monotonic time, includes deep sleep |
| kotlinx.coroutines | 1.7+ | Async/await, structured concurrency | Kotlin standard for async operations, non-blocking |
| kotlinx.coroutines.sync.Mutex | 1.7+ | Coroutine-friendly mutual exclusion | Suspending lock without blocking threads |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| java.util.concurrent.atomic.AtomicBoolean | Java 5+ | Thread-safe boolean operations | Single callback guarantee, cancellation flags |
| kotlin.time.Duration | Kotlin 1.6+ | Type-safe time units | TTL calculations, delay intervals |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| ConcurrentHashMap | Android LruCache | LruCache is LRU-only (no TTL support), thread-safe but optimized for size not time eviction |
| ConcurrentHashMap | Guava Cache | External dependency (46KB), more features than needed, overkill for simple TTL cache |
| SystemClock.elapsedRealtime() | System.currentTimeMillis() | currentTimeMillis() affected by time changes, not monotonic, can jump backward |
| Mutex | synchronized blocks | synchronized blocks thread, can't be used with suspend functions |

**Installation:**
```bash
# All required dependencies are part of Kotlin stdlib and Android SDK
# No additional dependencies needed for Phase 1
```

## Architecture Patterns

### Recommended Project Structure
```
org/bidon/sdk/ads/cache/denis/
├── stores/              # Cache store implementations
│   ├── ReadyToShowCache.kt
│   ├── RtbPayloadCache.kt
│   └── CacheEntry.kt    # Data class with timestamp
├── eviction/            # Eviction logic
│   ├── TtlEviction.kt   # Lazy eviction logic
│   └── PeriodicSweep.kt # Background cleanup job
└── AdCacheDenisImpl.kt  # Integration with existing AdCache interface
```

### Pattern 1: Singleton Object for Application-Wide Cache
**What:** Use Kotlin `object` declaration for cache stores to ensure single instance across application
**When to use:** Application-scoped singletons that don't hold Activity context
**Example:**
```kotlin
// Source: Kotlin official documentation + verified pattern
object ReadyToShowCache {
    private val cache = ConcurrentHashMap<String, CacheEntry<LoadedAd>>()

    fun put(key: String, value: LoadedAd) {
        val entry = CacheEntry(
            value = value,
            expiresAt = SystemClock.elapsedRealtime() + TTL_MILLIS
        )
        cache[key] = entry
    }

    fun get(key: String): LoadedAd? {
        val entry = cache[key] ?: return null
        return if (isExpired(entry)) {
            cache.remove(key) // Lazy eviction
            null
        } else {
            entry.value
        }
    }

    private fun isExpired(entry: CacheEntry<*>): Boolean {
        return SystemClock.elapsedRealtime() > entry.expiresAt
    }
}

data class CacheEntry<T>(
    val value: T,
    val expiresAt: Long // SystemClock.elapsedRealtime() + TTL
)
```

### Pattern 2: Atomic Duplicate Detection with eCPM Comparison
**What:** Use ConcurrentHashMap.compute() for atomic read-compare-write operations
**When to use:** Duplicate demandId detection where higher eCPM wins
**Example:**
```kotlin
// Source: Verified from Java ConcurrentHashMap documentation
fun putIfHigherEcpm(demandId: String, ad: LoadedAd, ecpm: Double) {
    cache.compute(demandId) { _, existing ->
        if (existing == null || ecpm > existing.ecpm) {
            CacheEntry(ad, ecpm, SystemClock.elapsedRealtime() + TTL_MILLIS)
        } else {
            existing // Keep existing if higher eCPM
        }
    }
}

data class CacheEntry<T>(
    val value: T,
    val ecpm: Double,
    val expiresAt: Long
)
```

### Pattern 3: Periodic Sweep with Coroutines
**What:** Background coroutine that periodically removes expired entries
**When to use:** Cleanup of expired entries that haven't been lazily evicted
**Example:**
```kotlin
// Source: Kotlin Coroutines official patterns
class PeriodicSweep(
    private val scope: CoroutineScope,
    private val cache: ConcurrentHashMap<String, CacheEntry<*>>
) {
    private var sweepJob: Job? = null

    fun start() {
        sweepJob?.cancel()
        sweepJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(5.minutes)
                sweepExpired()
            }
        }
    }

    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
    }

    private fun sweepExpired() {
        val now = SystemClock.elapsedRealtime()
        cache.entries.removeIf { (_, entry) ->
            now > entry.expiresAt
        }
    }
}
```

### Pattern 4: Mutex for Compound Operations
**What:** Use Mutex.withLock for compound operations that must be atomic
**When to use:** Cache put + callback notification that must happen together
**Example:**
```kotlin
// Source: Kotlin Coroutines Mutex documentation
class CacheWithNotification {
    private val cache = ConcurrentHashMap<String, CacheEntry<LoadedAd>>()
    private val mutex = Mutex()
    private val listeners = mutableListOf<CacheListener>()

    suspend fun putAndNotify(key: String, value: LoadedAd) {
        mutex.withLock {
            cache[key] = CacheEntry(value, SystemClock.elapsedRealtime() + TTL_MILLIS)
            listeners.forEach { it.onCacheUpdated(key, value) }
        }
    }
}
```

### Pattern 5: Memory-Aware Capacity Limits
**What:** Check size before insertion and evict LRU entry if at capacity
**When to use:** Prevent unbounded memory growth
**Example:**
```kotlin
// Source: Android LruCache pattern adapted for TTL cache
class BoundedCache<K, V>(private val maxCapacity: Int) {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val accessOrder = ConcurrentHashMap<K, Long>() // Track access time

    fun put(key: K, value: V) {
        if (cache.size >= maxCapacity) {
            evictLeastRecentlyUsed()
        }
        cache[key] = CacheEntry(value, SystemClock.elapsedRealtime() + TTL_MILLIS)
        accessOrder[key] = SystemClock.elapsedRealtime()
    }

    private fun evictLeastRecentlyUsed() {
        val lruKey = accessOrder.entries.minByOrNull { it.value }?.key
        lruKey?.let {
            cache.remove(it)
            accessOrder.remove(it)
        }
    }
}
```

### Anti-Patterns to Avoid
- **Check-then-act with separate get() and put():** Race condition where two threads see null and both insert. Use compute() instead.
- **synchronized blocks around suspend functions:** Blocks thread while suspended, defeats coroutines purpose. Use Mutex instead.
- **System.currentTimeMillis() for TTL:** Can jump backward with time changes. Use SystemClock.elapsedRealtime().
- **GlobalScope for periodic sweep:** Leaks coroutine, can't be cancelled. Inject CoroutineScope parameter.
- **Holding Activity context in singleton:** Memory leak. Use ApplicationContext or WeakReference.

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Thread-safe map | Custom synchronized wrapper | ConcurrentHashMap | Lock striping, optimized internal locking, atomic operations built-in |
| Atomic compare-and-swap | Manual synchronization | ConcurrentHashMap.compute() | Single atomic operation, no race conditions |
| Monotonic time | currentTimeMillis() + checks | SystemClock.elapsedRealtime() | Platform-guaranteed monotonic, handles deep sleep correctly |
| Periodic task scheduling | Timer/Handler | Kotlin Coroutines with delay() | Structured concurrency, cancellation support, testable |
| Mutex implementation | Custom lock with wait/notify | kotlinx.coroutines.sync.Mutex | Non-blocking suspension, integrates with coroutine cancellation |

**Key insight:** ConcurrentHashMap's atomic operations (compute, computeIfAbsent, merge) eliminate entire classes of race conditions that manual synchronization would introduce. The compute() method is specifically designed for compound operations like duplicate detection with value comparison.

## Common Pitfalls

### Pitfall 1: Clock Skew with System.currentTimeMillis()
**What goes wrong:** TTL calculated with currentTimeMillis() can expire prematurely or never expire if user changes system time or NTP sync occurs
**Why it happens:** currentTimeMillis() returns wall-clock time which can jump forward/backward
**How to avoid:** Always use SystemClock.elapsedRealtime() for TTL calculations
```kotlin
// WRONG - can break with time changes
val expiresAt = System.currentTimeMillis() + TTL_MILLIS

// CORRECT - monotonic, immune to time changes
val expiresAt = SystemClock.elapsedRealtime() + TTL_MILLIS
```
**Warning signs:** Cache entries expiring at wrong times, TTL checks failing unexpectedly

### Pitfall 2: Race Condition in Duplicate Detection
**What goes wrong:** Two threads check for duplicate, both see null, both insert - higher eCPM entry may be overwritten by lower
**Why it happens:** get() followed by put() is not atomic, window for race condition between operations
**How to avoid:** Use ConcurrentHashMap.compute() for atomic read-compare-write
```kotlin
// WRONG - race condition between get and put
val existing = cache.get(demandId)
if (existing == null || ecpm > existing.ecpm) {
    cache.put(demandId, newEntry) // Another thread can interleave here
}

// CORRECT - atomic operation
cache.compute(demandId) { _, existing ->
    if (existing == null || ecpm > existing.ecpm) newEntry else existing
}
```
**Warning signs:** Lower eCPM ads occasionally winning over higher eCPM ads in concurrent scenarios

### Pitfall 3: Memory Leak from Singleton Holding Activity Context
**What goes wrong:** Singleton cache holds reference to Activity context, prevents Activity from being garbage collected
**Why it happens:** Singletons live for application lifetime, Activity should be garbage collected when finished
**How to avoid:** Use ApplicationContext or WeakReference for Activity contexts
```kotlin
// WRONG - holds Activity reference forever
object Cache {
    private var context: Context? = null
    fun init(activity: Activity) {
        context = activity // Memory leak!
    }
}

// CORRECT - use ApplicationContext
object Cache {
    private lateinit var appContext: Context
    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

// CORRECT - WeakReference if Activity needed
object Cache {
    private var activityRef: WeakReference<Activity>? = null
    fun init(activity: Activity) {
        activityRef = WeakReference(activity)
    }
}
```
**Warning signs:** Memory leaks in LeakCanary, OutOfMemoryError after multiple Activity recreations

### Pitfall 4: Coroutine Cancellation Breaking Cleanup
**What goes wrong:** finally block with suspend function fails to execute when coroutine cancelled
**Why it happens:** Cancelled coroutines can't suspend, suspend calls in finally throw CancellationException
**How to avoid:** Use withContext(NonCancellable) for cleanup suspend calls
```kotlin
// WRONG - cleanup may not run if cancelled
try {
    // cache operations
} finally {
    suspendingCleanup() // Throws if cancelled!
}

// CORRECT - cleanup guaranteed to run
try {
    // cache operations
} finally {
    withContext(NonCancellable) {
        suspendingCleanup() // Runs even if cancelled
    }
}
```
**Warning signs:** Resources not cleaned up, cache entries not removed on cancellation

### Pitfall 5: Periodic Sweep Job Not Cancelled
**What goes wrong:** Background sweep job continues running after cache should be destroyed, consuming resources
**Why it happens:** Job launched in scope that outlives the component, no cleanup on destroy
**How to avoid:** Store Job reference and cancel explicitly, or use scoped CoroutineScope
```kotlin
// WRONG - job runs forever
object Cache {
    init {
        GlobalScope.launch {
            while (true) {
                delay(5.minutes)
                sweep()
            }
        }
    }
}

// CORRECT - cancellable job
class Cache(private val scope: CoroutineScope) {
    private var sweepJob: Job? = null

    fun start() {
        sweepJob = scope.launch {
            while (isActive) {
                delay(5.minutes)
                sweep()
            }
        }
    }

    fun stop() {
        sweepJob?.cancel()
    }
}
```
**Warning signs:** CPU usage when cache not in use, battery drain, multiple sweep jobs running

### Pitfall 6: Mutex Reentrancy Deadlock
**What goes wrong:** Same coroutine tries to acquire Mutex it already holds, deadlocks
**Why it happens:** Kotlin Mutex is non-reentrant by design
**How to avoid:** Structure code to avoid nested lock acquisition, use separate mutexes if needed
```kotlin
// WRONG - deadlock if called from within mutex
suspend fun outerOperation() {
    mutex.withLock {
        innerOperation() // Deadlock!
    }
}

suspend fun innerOperation() {
    mutex.withLock {
        // ...
    }
}

// CORRECT - restructure to avoid nesting
suspend fun outerOperation() {
    mutex.withLock {
        innerOperationUnlocked()
    }
}

fun innerOperationUnlocked() {
    // No lock here
}
```
**Warning signs:** Coroutine hangs indefinitely, app becomes unresponsive

## Code Examples

Verified patterns from official sources:

### ConcurrentHashMap Atomic Operations
```kotlin
// Source: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html
val cache = ConcurrentHashMap<String, CacheEntry>()

// Atomic compute for duplicate detection
cache.compute(key) { _, existing ->
    if (existing == null || newValue.ecpm > existing.ecpm) {
        newValue
    } else {
        existing
    }
}

// Atomic computeIfAbsent for lazy initialization
cache.computeIfAbsent(key) {
    CacheEntry(value, SystemClock.elapsedRealtime() + TTL)
}
```

### SystemClock for Monotonic Time
```kotlin
// Source: https://developer.android.com/reference/android/os/SystemClock
val TTL_MILLIS = 30 * 60 * 1000L // 30 minutes

data class CacheEntry<T>(
    val value: T,
    val expiresAt: Long // SystemClock.elapsedRealtime() timestamp
)

fun put(key: String, value: T) {
    val entry = CacheEntry(
        value = value,
        expiresAt = SystemClock.elapsedRealtime() + TTL_MILLIS
    )
    cache[key] = entry
}

fun isExpired(entry: CacheEntry<*>): Boolean {
    return SystemClock.elapsedRealtime() > entry.expiresAt
}
```

### Kotlin Singleton Object
```kotlin
// Source: https://www.baeldung.com/kotlin/singleton-classes
object ReadyToShowCache {
    private val cache = ConcurrentHashMap<String, CacheEntry<LoadedAd>>()

    // Thread-safe by default (object initialization is thread-safe)
    fun peek(key: String): LoadedAd? {
        return cache[key]?.takeIf { !isExpired(it) }?.value
    }

    fun pop(key: String): LoadedAd? {
        val entry = cache.remove(key)
        return entry?.takeIf { !isExpired(it) }?.value
    }
}
```

### Mutex for Coroutine Synchronization
```kotlin
// Source: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/
class SynchronizedCache {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mutex = Mutex()

    suspend fun putAndNotify(key: String, value: LoadedAd) {
        mutex.withLock {
            // Compound operation is atomic
            cache[key] = CacheEntry(value, SystemClock.elapsedRealtime() + TTL)
            notifyListeners(key, value)
        }
    }
}
```

### Periodic Task with Coroutines
```kotlin
// Source: https://www.baeldung.com/kotlin/schedule-repeating-task
fun startPeriodicSweep(scope: CoroutineScope): Job {
    return scope.launch(Dispatchers.Default) {
        while (isActive) {
            delay(5.minutes) // Type-safe duration
            sweepExpiredEntries()
        }
    }
}

fun sweepExpiredEntries() {
    val now = SystemClock.elapsedRealtime()
    cache.entries.removeIf { (_, entry) ->
        now > entry.expiresAt
    }
}
```

### NonCancellable Cleanup
```kotlin
// Source: https://medium.com/@shushanttiwari.ashu/mastering-noncancellable-in-kotlin-coroutines-ensuring-safe-cleanup-and-transactions-ea680053f09b
suspend fun cacheOperation() {
    try {
        // Cache operations
        cache.put(key, value)
    } finally {
        withContext(NonCancellable) {
            // Cleanup runs even if coroutine cancelled
            suspendingCleanup()
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| synchronized blocks | Kotlin Coroutines Mutex | Kotlin 1.1+ (2017) | Non-blocking suspension, better performance |
| Timer/Handler for periodic tasks | Coroutines with delay() | Kotlin 1.3+ (2018) | Structured concurrency, easier cancellation |
| Manual thread-safe collections | ConcurrentHashMap | Java 5 (2004) | Lock striping, better concurrent performance |
| RxJava for async | Kotlin Coroutines | Kotlin 1.3+ (2018) | Native language support, simpler API |
| LruCache for all caching | Custom TTL caches | Always | LruCache is size-based only, no TTL support |

**Deprecated/outdated:**
- **AsyncTask**: Deprecated in API 30, use Coroutines instead
- **HandlerThread**: Still works but Coroutines are preferred for structured concurrency
- **GlobalScope**: Discouraged since kotlinx.coroutines 1.3.9, inject CoroutineScope instead
- **Collections.synchronizedMap()**: Use ConcurrentHashMap for better performance

## Open Questions

Things that couldn't be fully resolved:

1. **Optimal capacity limits for each cache**
   - What we know: Requirements specify 1-3 for READY_TO_SHOW, 5-10 for RTB_PAYLOAD
   - What's unclear: Impact on memory across different device tiers (low-end vs high-end)
   - Recommendation: Start with conservative values (1 for READY_TO_SHOW, 5 for RTB_PAYLOAD), make configurable for future adjustment based on telemetry

2. **CoroutineScope injection for singleton objects**
   - What we know: Singleton objects initialized lazily on first access
   - What's unclear: Best pattern for injecting CoroutineScope into singleton for periodic sweep
   - Recommendation: Use application-level scope passed to init() method, or create internal SupervisorScope with lifetime tied to first usage

3. **Memory pressure handling on low-end devices**
   - What we know: Fixed capacity limits prevent unbounded growth
   - What's unclear: Whether Android's low memory callbacks should trigger early eviction
   - Recommendation: Start with fixed limits, monitor for OutOfMemoryError in telemetry, add ComponentCallbacks2 integration in future if needed

4. **TTL precision vs battery impact**
   - What we know: 5-minute periodic sweep interval specified
   - What's unclear: Whether more frequent sweeps would improve memory or harm battery
   - Recommendation: Stick with 5-minute interval (balance between memory and battery), lazy eviction catches most expired entries anyway

## Sources

### Primary (HIGH confidence)
- [Android SystemClock documentation](https://developer.android.com/reference/android/os/SystemClock) - Monotonic time source
- [Android LruCache documentation](https://developer.android.com/reference/android/util/LruCache) - Memory management patterns
- [Kotlin Mutex documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/) - Coroutine synchronization
- [ConcurrentHashMap JavaDoc](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html) - Atomic operations

### Secondary (MEDIUM confidence)
- [ConcurrentHashMap in Kotlin](https://medium.com/@ys.yogendra22/concurrenthashmap-in-kotlin-thread-safe-and-smart-4f513806bde7) - Best practices
- [Kotlin Singleton Pattern](https://www.baeldung.com/kotlin/singleton-classes) - Thread safety of objects
- [Shared Mutable State and Concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) - Official Kotlin docs on thread safety
- [Scheduling Repeating Task in Kotlin](https://www.baeldung.com/kotlin/schedule-repeating-task) - Periodic task patterns
- [Mastering NonCancellable in Kotlin Coroutines](https://medium.com/@shushanttiwari.ashu/mastering-noncancellable-in-kotlin-coroutines-ensuring-safe-cleanup-and-transactions-ea680053f09b) - Cleanup patterns
- [The Misunderstood Thread Safety of ConcurrentHashMap](https://codefarm0.medium.com/the-misunderstood-thread-safety-of-concurrenthashmap-edf86427a641) - Race condition patterns
- [Android Memory Leaks in 2025](https://artemasoyan.medium.com/top-7-android-memory-leaks-and-how-to-avoid-them-in-2025-b77e15a7b62e) - WeakReference patterns

### Tertiary (LOW confidence)
- [Cache Eviction vs Expiration](https://docs.momentohq.com/cache/learn/how-it-works/cache-eviction-vs-expiration) - General caching concepts (not Android-specific)
- [TTL Cache Implementation](https://github.com/nikhilkarnwal/cache) - Example implementation (not Kotlin)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All libraries are official Android/Kotlin/Java platform APIs
- Architecture: HIGH - Patterns verified with official documentation and established sources
- Pitfalls: HIGH - Verified from official docs and current best practices (2025-2026)

**Research date:** 2026-02-05
**Valid until:** 2026-03-05 (30 days - stable platform APIs, slow-moving domain)
