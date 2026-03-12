# SPEC: Two-Level Cache (V6) — Android Port of iOS Zhenya Strategy

## Overview

Port of the iOS "Zhenya" two-level cache strategy to Android as the `twolevel` package alongside the existing `denis` package. This is cache strategy V6, registered in `AdCacheVersion` and selectable via `cache_settings` JSON extras.

**V6 will eventually replace Denis (V2) as the primary caching strategy.**

**Supported ad types:** Interstitial + Banner (MVP scope).

---

## Architecture Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Auction model | Sequential waterfall (iOS-style) | Faithful port: process ad units one-by-one via `SequentialAuctionPipeline`, first fill fires onLoad immediately |
| Auction wiring | Custom `SequentialAuctionPipeline` | No dependency on `Auction.start()`. Directly uses `GetTokensUseCase`, `GetAuctionRequestUseCase`, `AdSource`. Mirrors iOS `ZhenyaAuctionController` OperationQueue |
| Storage scope | Static singleton per AdType | Mirrors iOS `Cacher.swift` — one `CacheStorage` + `FallbackCacheStorage` pair per `AdType`, shared across all auctionKeys |
| Lifecycle | Manager pool with WeakReference | Singleton pool keyed by auctionKey. Managers survive InterstitialImpl destruction, periodic cleanup of idle managers |
| Config source | JSON extras (`cache_settings`) | Read capacity, threshold, fallbackSize from `BidonSdk.getExtras()["cache_settings"]` per ad type |
| Default values | capacity=10, threshold=80 | Matches iOS `Cacher.swift` fallback defaults |
| TTL | None (like iOS) | Ads stay cached until popped, evicted by capacity, or pool cleanup |
| Show fallback | Simple pop chain (like iOS) | Pop from Main first, then Fallback. No recursive retry |
| Cache entry type | Store `AuctionResult` directly | CacheStorage operates on AuctionResult. Price from `adSource.getStats().price` |
| Stats handling | Custom pipeline stats | `SequentialAuctionPipeline` collects stats via `ResultsCollector` and `AuctionStat` |
| Entry identity | `demandId` | Use `adSource.getStats().demandId.demandId` for duplicate detection |
| Iteration threshold | Iteration-only (like iOS) | Threshold only applies within one auction round via `beginIteration()`/`insert()` cycle |
| Clear semantics | Detach only, don't clear cache | `clear()` detaches caller from pool; shared stores survive |
| Callback dispatch | Main thread | Fire onSuccess/onFailure on `Dispatchers.Main`, matching V1 and iOS |
| Concurrent loads | AtomicBoolean guard | If auction running, second `load()` is ignored (not queued) |
| Failure fallback | Check Fallback on auction failure | If auction fails, check Fallback cache for ads >= pricefloor before propagating failure |
| Thread safety | Mutex (coroutine-friendly) | `kotlinx.coroutines.sync.Mutex` for all CacheStorage operations. `@Volatile headSnapshot` for lock-free peek |
| Version | V6 | Package: `org.bidon.sdk.ads.cache.twolevel` |

---

## Package Structure

```
bidon/src/main/java/org/bidon/sdk/ads/cache/twolevel/
├── AdCacheTwoLevelFactory.kt       # Factory entry point
├── ZhenyaAdManager.kt              # AdCache facade (mirrors iOS ZhenyaFullscreenAdManager)
├── ZhenyaAdManagerProxy.kt         # Lazy proxy (suspending ManagerPool.getOrCreate)
│
├── storage/
│   ├── CacheStorage.kt             # Main cache: sorted array, sticky head, iteration threshold
│   ├── FallbackCacheStorage.kt     # Fallback cache: simple sorted array, capacity eviction
│   ├── InsertResult.kt             # Sealed class: Success / Rejected(reason)
│   └── TwoLevelCacheStores.kt      # Static singleton StorePair(main, fallback) per AdType
│
├── auction/
│   ├── SequentialAuctionPipeline.kt # Custom sequential waterfall (replaces Auction.start())
│   └── ZhenyaAuctionController.kt  # Orchestrator: delegates to pipeline, fallback on failure
│
├── pool/
│   └── ManagerPool.kt              # Singleton pool keyed by auctionKey, WeakReference, cleanup
│
└── config/
    └── TwoLevelCacheConfig.kt      # Parses config from JSON extras (capacity, threshold, fallbackSize)
```

---

## Component Specifications

### 1. CacheStorage (Main Cache)

Port of iOS `CacheStorage.swift`. Sorted array with sticky-head mode and iteration threshold.

**State:**
- `items: MutableList<AuctionResult>` — sorted by price descending
- `indexByKey: MutableMap<String, Int>` — demandId -> index for O(1) lookup
- `stickyHeadActive: Boolean` — when true, items[0] is protected from eviction/resorting
- `iterationMaxPrice: Double?` — reset on `beginIteration()`, tracks max price in current iteration
- `capacity: Int` — from config (default 10)
- `iterationThreshold: Int` — percentage (default 80)
- `headSnapshot: AuctionResult?` — `@Volatile`, lock-free snapshot for synchronous `peekSnapshot()`
- `mutex: Mutex` — coroutine-friendly locking

**Operations:**
- `beginIteration()` — reset `iterationMaxPrice` to null
- `insert(element, sticky): InsertResult` — full insert logic (exact iOS port):
  1. Iteration threshold check (capacity > 1 only)
  2. Duplicate check by demandId (same price = update in place, diff price = remove + reinsert)
  3. Sticky head protection (capacity == 1 case)
  4. Capacity check (evict cheapest if new is more expensive)
  5. Insert + `sortAccordingToMode()` + `trimIfNeeded()`
- `popFirst(): AuctionResult?` — remove and return items[0], disable sticky mode, resort
- `peek(): AuctionResult?` — return items[0] without removal (suspend, Mutex)
- `peekSnapshot(): AuctionResult?` — lock-free `@Volatile` read for synchronous `isReady` checks

### 2. FallbackCacheStorage

Port of iOS `FallbackCacheStorage.swift`. Simpler: no sticky mode, no iteration threshold.

**Operations:**
- `insert(element): InsertResult` — duplicate check + capacity eviction (strict `>`, not `>=`) + sorted insert
- `popFirst(): AuctionResult?` — remove and return highest-priced
- `peek(): AuctionResult?` — non-destructive read (suspend, Mutex)
- `peekSnapshot(): AuctionResult?` — lock-free `@Volatile` read

### 3. InsertResult

```kotlin
sealed class InsertResult {
    data object Success : InsertResult()
    data class Rejected(val reason: Reason) : InsertResult()

    enum class Reason {
        IterationThreshold,
        StickyHeadProtected,
        CacheFull,
    }

    val isInserted: Boolean get() = this is Success
}
```

### 4. TwoLevelCacheStores

Port of iOS `Cacher.swift`. Static singleton `StorePair(main, fallback)` per `AdType`.

```kotlin
internal object TwoLevelCacheStores {
    fun getOrCreate(adType: AdType, config: TwoLevelCacheConfig): StorePair
}
```

### 5. SequentialAuctionPipeline

**Custom sequential waterfall auction.** Direct Android port of iOS `ZhenyaAuctionController`'s `OperationQueue(maxConcurrentOperationCount=1)` pattern.

No dependency on `Auction.start()`. Directly orchestrates:
1. Collect RTB tokens via `GetTokensUseCase`
2. POST to `/auction` endpoint via `GetAuctionRequestUseCase`
3. For EACH ad unit sequentially:
   - Find adapter via `AdaptersSource`
   - Create `AdSource` via `AdSourceFactory`
   - Call `AdSource.load()` and wait for `Fill` / `LoadFailed` / `Expired`
   - On fill: call `singleLoadCompletion(AuctionResult)` IMMEDIATELY
   - On failure: log and continue to next unit
4. After all units processed: call `onComplete`

**Key difference from standard Auction:** fires `singleLoadCompletion` per-fill as each ad unit loads, not after all units complete.

### 6. ZhenyaAuctionController

Thin orchestrator. Delegates to `SequentialAuctionPipeline`. On pipeline failure, checks `FallbackCacheStorage` for ad >= pricefloor before propagating error (mirrors iOS `handlePerformAuctionRequestFailed`).

### 7. ZhenyaAdManager

Implements `AdCache` interface. Facade over CacheStorage, FallbackCacheStorage, ZhenyaAuctionController.

**`cache(adTypeParam, onSuccess, onFailure)`:**
1. **Warm start:** Main.peek() has ad with price >= pricefloor -> pop and fire `onSuccess` immediately
2. **Guard:** If auction already running -> ignore duplicate (AtomicBoolean)
3. **Cold start:** via ZhenyaAuctionController:
   - `singleLoadCompletion`: for each fill:
     - `isFirst = firstFillFired.compareAndSet(false, true)`
     - Try `Main.insert(sticky=isFirst)`, if rejected -> `Fallback.insert()`, if both reject -> destroy
     - If isFirst -> fire `onSuccess` on Main thread
   - `onComplete`: if error and no first fill -> fire `onFailure`

**`peek()`:** `mainCache.peekSnapshot() ?: fallbackCache.peekSnapshot()` (lock-free)

**`pop()`:** `runBlocking { mainCache.popFirst() ?: fallbackCache.popFirst() }`

**`poll()`:** Suspends with 100ms polling interval until entry available

**`clear()`:** Detach from ManagerPool (don't clear shared caches)

### 8. ManagerPool

Port of iOS `ZhenyaManagerPool`. Singleton with `WeakReference<ZhenyaAdManager>`.

**Behavior:**
- `getOrCreate(auctionKey, demandAd, config): ZhenyaAdManager` — reuse live weak ref or create new
- `remove(auctionKey)` — called by `ZhenyaAdManager.clear()`
- Periodic cleanup (60s): remove entries where manager is idle AND (older than 5min OR weak ref dead)
- Thread-safe via Mutex

### 9. TwoLevelCacheConfig

Parses from `BidonSdk.getExtras()["cache_settings"]` JSON.

```kotlin
data class TwoLevelCacheConfig(
    val mainCacheSize: Int,        // default 10
    val fallbackCacheSize: Int,    // default 10
    val threshold: Int,            // default 80, percentage
)
```

---

## Integration Points

### AdCacheVersion.kt
`V6` data object added.

### AdCacheFactoryImpl.kt
`AdCacheVersion.V6 -> AdCacheTwoLevelFactory.create(demandAd)` case added.

### InterstitialImpl / BannerImpl
No changes needed — they use AdCache interface which V6 implements.

---

## Data Flow

```
InterstitialImpl.load(pricefloor)
  -> AdCache.cache(adTypeParam, onSuccess, onFailure)
    |
    +- WARM START: Main.peek() has ad >= pricefloor?
    |   -> pop from Main, fire onSuccess immediately
    |
    +- AUCTION RUNNING: ignore duplicate (AtomicBoolean guard)
    |
    +- COLD START: ZhenyaAuctionController -> SequentialAuctionPipeline
        |
        +- Collect RTB tokens
        +- POST /auction -> receive adUnits list
        |
        +- For each adUnit (sequential, one by one):
        |   +- Find adapter, create AdSource, load()
        |   +- On fill -> singleLoadCompletion:
        |   |   +- isFirst = firstFillFired.CAS(false, true)
        |   |   +- Try Main.insert(sticky=isFirst)
        |   |   |   +- Success -> stays in Main
        |   |   |   +- Rejected -> Fallback.insert()
        |   |   |       +- Rejected -> destroy ad source
        |   |   +- If isFirst -> fire onSuccess on Main thread
        |   +- On failure -> log, continue to next unit
        |
        +- All units processed -> onComplete:
            +- Has fills -> onComplete(auctionInfo, null)
            +- No fills -> controller checks Fallback:
                +- Fallback has ad >= pricefloor -> onComplete(info, null)
                +- Fallback empty -> onComplete(null, error) -> onFailure

InterstitialImpl.show()
  -> AdCache.pop()
    -> Main.popFirst() ?? Fallback.popFirst()
```

---

## Thread Safety Model

- **CacheStorage / FallbackCacheStorage**: `kotlinx.coroutines.sync.Mutex` for all mutating ops. `@Volatile headSnapshot` for lock-free `peekSnapshot()`
- **TwoLevelCacheStores**: `Mutex` for lazy initialization of per-AdType stores
- **ManagerPool**: `Mutex` for pool access + cleanup
- **SequentialAuctionPipeline**: sequential by design (coroutine `for` loop)
- **ZhenyaAdManager**: `AtomicBoolean` for auction-running guard
- **Callbacks**: always dispatched on `Dispatchers.Main`

---

## iOS -> Android Mapping

| iOS | Android |
|-----|---------|
| `CacheStorage.swift` | `CacheStorage.kt` |
| `FallbackCacheStorage.swift` | `FallbackCacheStorage.kt` |
| `Cacher.swift` (static singletons) | `TwoLevelCacheStores.kt` |
| `ZhenyaManagerPool.swift` | `ManagerPool.kt` |
| `ZhenyaFullscreenAdManager.swift` | `ZhenyaAdManager.kt` |
| `ZhenyaAuctionController.swift` (OperationQueue) | `SequentialAuctionPipeline.kt` + `ZhenyaAuctionController.kt` |
| `AdCacheConfig.swift` | `TwoLevelCacheConfig.kt` |
| `OperationQueue(maxConcurrent=1)` | Kotlin coroutine sequential `for` loop |
| `NSLock` / `DispatchQueue` | `kotlinx.coroutines.sync.Mutex` |
| `weak var` | `WeakReference<ZhenyaAdManager>` |
| `Timer.scheduledTimer` | `CoroutineScope` + `delay()` loop |

---

## Config JSON Example

```json
{
  "cache_settings": {
    "interstitial": {
      "strategy_version": "v6",
      "adunit_cache_size": 3,
      "fallback_cache_size": 2,
      "threshold": 80
    },
    "banner": {
      "strategy_version": "v6",
      "adunit_cache_size": 2,
      "fallback_cache_size": 1,
      "threshold": 80
    }
  }
}
```

---

## Tests

- `CacheStorageTest.kt` — 27 tests covering insert, sticky head, iteration threshold, eviction, duplicate handling
- `FallbackCacheStorageTest.kt` — 19 tests covering insert, eviction, strict `>` comparison

---

## Out of Scope (for now)

- Rewarded ad type support (can be added later using same architecture)
- TTL / periodic sweep (not needed per decision; config field reserved for future)
- ShowWithFallback recursive retry (simple pop chain only)
- RtbPayloadCache / skip-token optimization (Denis-specific, not part of Zhenya strategy)
