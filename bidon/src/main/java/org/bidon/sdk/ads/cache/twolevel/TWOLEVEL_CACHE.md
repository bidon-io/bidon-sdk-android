# Two-Level Cache (V6) — Android Port of iOS Zhenya Strategy

Port of the iOS "Zhenya" two-level cache strategy. Cache strategy V6, registered in `AdCacheVersion`, selectable via `cache_settings` JSON extras. Will eventually replace Denis (V2).

Supported ad types: Interstitial + Banner (MVP scope).

---

## Architecture

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Auction model | Sequential waterfall | Process ad units one-by-one via `SequentialAuctionPipeline`, first fill fires onLoad immediately |
| Auction wiring | Custom pipeline | No dependency on `Auction.start()`. Uses `GetTokensUseCase`, `GetAuctionRequestUseCase`, `AdSource` directly |
| Storage scope | Static singleton per AdType | Mirrors iOS `Cacher.swift` — shared across all auctionKeys |
| Lifecycle | Manager pool with WeakReference | Singleton keyed by auctionKey, periodic cleanup |
| Defaults | capacity=10, threshold=80 | Matches iOS `Cacher.swift` fallback defaults |
| Thread safety | Mutex + @Volatile snapshot | Coroutine-friendly locking, lock-free `peekSnapshot()` |

---

## Package Structure

```
twolevel/
├── AdCacheTwoLevelFactory.kt       # Factory entry point
├── ZhenyaAdManager.kt              # AdCache facade (iOS ZhenyaFullscreenAdManager)
├── ZhenyaAdManagerProxy.kt         # Lazy proxy (suspending ManagerPool.getOrCreate)
├── TWOLEVEL_CACHE.md               # This file
│
├── storage/
│   ├── CacheStorage.kt             # Main cache: sorted array, sticky head, iteration threshold
│   ├── FallbackCacheStorage.kt     # Fallback cache: simple sorted array, strict > eviction
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

## iOS -> Android Mapping

| iOS | Android |
|-----|---------|
| `CacheStorage.swift` | `CacheStorage.kt` |
| `FallbackCacheStorage.swift` | `FallbackCacheStorage.kt` |
| `Cacher.swift` (static singletons) | `TwoLevelCacheStores.kt` |
| `ZhenyaManagerPool.swift` | `ManagerPool.kt` |
| `ZhenyaFullscreenAdManager.swift` | `ZhenyaAdManager.kt` |
| `ZhenyaAuctionController.swift` | `SequentialAuctionPipeline.kt` + `ZhenyaAuctionController.kt` |
| `AdCacheConfig.swift` | `TwoLevelCacheConfig.kt` |
| `OperationQueue(maxConcurrent=1)` | Kotlin coroutine sequential `for` loop |
| `NSLock` / `DispatchQueue` | `kotlinx.coroutines.sync.Mutex` |
| `weak var` | `WeakReference<ZhenyaAdManager>` |
| `Timer.scheduledTimer` | `CoroutineScope` + `delay()` loop |

iOS sources: `/Users/glavatskikh/XcodeProjects/bidon-sdk-ios`, branch `feature/ad-caching`.

---

## Data Flow

```
InterstitialImpl.load(pricefloor)
  -> AdCache.cache(adTypeParam, onSuccess, onFailure)
    |
    +- WARM START: Main.peek() >= pricefloor?
    |   -> pop from Main, fire onSuccess immediately
    |
    +- AUCTION RUNNING: ignore duplicate (AtomicBoolean guard)
    |
    +- COLD START: beginIteration() -> ZhenyaAuctionController -> SequentialAuctionPipeline
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
                +- Fallback >= pricefloor -> serve
                +- Fallback empty -> onFailure

InterstitialImpl.show()
  -> AdCache.pop() -> Main.popFirst() ?? Fallback.popFirst()
```

---

## Components

### CacheStorage (Main Cache)

Port of `CacheStorage.swift`. Sorted array with sticky-head and iteration threshold.

**insert(element, sticky) flow** (exact iOS port):
1. Iteration threshold check (capacity > 1 only) — `shouldRejectByIterationThreshold`
2. Duplicate check by demandId (same price = update, diff price = remove + reinsert)
3. Sticky head protection (capacity == 1, sticky active, **non-sticky** = reject)
4. Capacity check — `cheapestAllowedToEvictPrice()` O(1) via `items.last()`. Reject only, no eager eviction
5. Insert (sticky at head, non-sticky at tail) + `sortAccordingToMode()` + `trimIfNeeded()`

**sortAccordingToMode:** sticky mode sorts only tail `items[1..]`; normal sorts all.

### FallbackCacheStorage

Port of `FallbackCacheStorage.swift`. No sticky mode, no threshold. Eviction uses strict `>` (not `>=`).

### SequentialAuctionPipeline

Custom sequential waterfall. Processes ad units one-by-one, fires `singleLoadCompletion` immediately per fill. Per-unit timeout via `withTimeout(adUnit.timeout)`, global timeout via `withTimeout(auctionTimeout)`.

### ZhenyaAuctionController

Thin orchestrator. Delegates to pipeline. On failure, checks Fallback >= pricefloor before propagating error.

### ZhenyaAdManager

`AdCache` facade. Warm start (peek+pop Main), cold start (beginIteration + controller), duplicate load guard (AtomicBoolean).

- `peek()` = `mainCache.peekSnapshot() ?: fallbackCache.peekSnapshot()` (lock-free)
- `pop()` = `runBlocking { Main.popFirst() ?: Fallback.popFirst() }`
- `clear()` = detach from ManagerPool (shared caches survive)

### ManagerPool

Singleton pool keyed by auctionKey. `WeakReference<ZhenyaAdManager>`. Cleanup every 60s: idle AND (>5min OR weak ref dead).

### TwoLevelCacheStores

Static singleton `StorePair(main, fallback)` per `AdType`. Mirrors iOS `Cacher.swift`.

### TwoLevelCacheConfig

Parses from `BidonSdk.getExtras()["cache_settings"]` JSON. Defaults: mainCacheSize=10, fallbackCacheSize=10, threshold=80.

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
    }
  }
}
```

---

## Thread Safety

| Component | Mechanism |
|-----------|-----------|
| CacheStorage / FallbackCacheStorage | `Mutex` for mutations, `@Volatile headSnapshot` for lock-free peek |
| TwoLevelCacheStores | `Mutex` for lazy init |
| ManagerPool | `Mutex` for pool access + cleanup |
| SequentialAuctionPipeline | Sequential by design (coroutine `for` loop) |
| ZhenyaAdManager | `AtomicBoolean` for auction-running guard |
| Callbacks | Always `Dispatchers.Main` |

---

## Tests

- `CacheStorageTest.kt` — 30 tests (insert, sticky head, iteration threshold, eviction, duplicates, capacity==1 scenarios)
- `FallbackCacheStorageTest.kt` — 19 tests (insert, eviction, strict `>`)

---

## Design Decisions (Android vs iOS differences)

| Aspect | Android | iOS | Decision |
|--------|---------|-----|----------|
| Fallback routing | Only on Main reject | Always insert Fallback | Keep Android (no duplicate caching) |
| Warm start | Pop from cache | Leave in cache | Android correct (prevents double serve) |
| Duplicate load guard | AtomicBoolean | Implicit state machine | Android safer |
| Per-unit timeout | `withTimeout(adUnit.timeout)` | Global only | Android more granular |

---

## Validation History

All bugs found during iOS code review have been fixed:

1. **CacheStorage capacity==1 sticky insert** — 3 sub-bugs: missing `!sticky`, null cheapest handling, wrong insert position
2. **Missing `beginIteration()` before auction** — iterationMaxPrice could carry over
3. **Eager eviction in step 4** — iOS defers to `trimIfNeeded()`, Android now matches
4. **`cheapestAllowedToEvictPrice` O(n)** — changed to O(1) `items.last()`

---

## Out of Scope

- Rewarded ad type support
- TTL / periodic sweep
- ShowWithFallback recursive retry
- RtbPayloadCache / skip-token optimization (Denis-specific)
