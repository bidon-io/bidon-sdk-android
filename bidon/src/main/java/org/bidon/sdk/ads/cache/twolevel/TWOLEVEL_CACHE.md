# Two-Level Cache — Full Flow Documentation

Port of the iOS "Zhenya" two-level cache strategy.
Registered as `AdCacheVersion.V2` in `AdCacheFactoryImpl`, selectable via `cache_settings` JSON extras (`"strategy_version": "v2"`). Replaces Denis.

Supported ad types: Interstitial + Banner (MVP scope).

iOS sources: `/Users/glavatskikh/XcodeProjects/bidon-sdk-ios`, branch `feature/ad-caching`.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Package Structure](#package-structure)
3. [iOS -> Android File Mapping](#ios---android-file-mapping)
4. [Full Ad Lifecycle Flow](#full-ad-lifecycle-flow)
   - [load() — Warm Start](#1-load--warm-start)
   - [load() — Cold Start (Auction)](#2-load--cold-start-auction)
   - [Sequential Auction Pipeline](#3-sequential-auction-pipeline)
   - [singleLoadCompletion — Cache Routing](#4-singleloadcompletion--cache-routing)
   - [Auction Completion](#5-auction-completion)
   - [show() — Ad Delivery](#6-show--ad-delivery)
   - [peek() / isReady](#7-peek--isready)
   - [clear() — Cleanup](#8-clear--cleanup)
5. [CacheStorage (Main Cache) — Insert Algorithm](#cachestorage-main-cache--insert-algorithm)
6. [FallbackCacheStorage — Insert Algorithm](#fallbackcachestorage--insert-algorithm)
7. [ManagerPool Lifecycle](#managerpool-lifecycle)
8. [TwoLevelCacheStores — Singleton Pattern](#twolevelcachestores--singleton-pattern)
9. [Config](#config)
10. [Thread Safety](#thread-safety)
11. [Android vs iOS — All Differences](#android-vs-ios--all-differences)
12. [iOS Bugs Found During Validation](#ios-bugs-found-during-validation)
13. [Android Fixes Applied During Port](#android-fixes-applied-during-port)
14. [Tests](#tests)
15. [Out of Scope](#out-of-scope)

---

## Architecture Overview

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
├── TwoLevelAdManager.kt            # AdCache facade (iOS ZhenyaFullscreenAdManager)
├── TwoLevelAdManagerProxy.kt       # Lazy proxy (suspending ManagerPool.getOrCreate)
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
│   └── TwoLevelAuctionController.kt  # Orchestrator: delegates to pipeline, fallback on failure
│
├── pool/
│   └── ManagerPool.kt              # Singleton pool keyed by auctionKey, WeakReference, cleanup
│
└── config/
    └── TwoLevelCacheConfig.kt      # Parses config from JSON extras (capacity, threshold, fallbackSize)
```

---

## iOS -> Android File Mapping

| iOS | Android |
|-----|---------|
| `CacheStorage.swift` | `CacheStorage.kt` |
| `FallbackCacheStorage.swift` | `FallbackCacheStorage.kt` |
| `Cacher.swift` (static singletons) | `TwoLevelCacheStores.kt` |
| `ZhenyaManagerPool.swift` | `ManagerPool.kt` |
| `ZhenyaFullscreenAdManager.swift` | `TwoLevelAdManager.kt` |
| `ZhenyaAuctionController.swift` | `SequentialAuctionPipeline.kt` + `TwoLevelAuctionController.kt` |
| `ZhenyaSandbox.swift` (banner) | `TwoLevelAdManager.kt` (unified for all ad types) |
| `AdCacheConfig` (from AppManager) | `TwoLevelCacheConfig.kt` |
| `OperationQueue(maxConcurrent=1)` | Kotlin coroutine sequential `for` loop |
| `NSLock` / `DispatchQueue` | `kotlinx.coroutines.sync.Mutex` |
| `weak var` | `WeakReference<TwoLevelAdManager>` |
| `Timer.scheduledTimer` | `CoroutineScope` + `delay()` loop |
| `InsertResult` enum | `InsertResult` sealed class |
| `BidContainer` / `Ad` protocol | `AuctionResult` (existing SDK type) |

---

## Full Ad Lifecycle Flow

### 1. load() — Warm Start

When `InterstitialImpl.load(pricefloor)` is called and the cache already has an ad:

```
Android (TwoLevelAdManager.cacheInternal):              iOS (ZhenyaFullscreenAdManager.loadAd):
─────────────────────────────────────────────            ────────────────────────────────────────
1. mainCache.peek()                                      1. Cacher.Main.interstitialStorage.peek()
2. Check: price >= pricefloor?                           2. Check: ad.price >= pricefloor?
3. mainCache.popFirst()  ← POP immediately               3. Do NOT pop (leave in cache)
4. Build synthetic AuctionInfo                           4. Create ImpressionController(bid)
5. withContext(Main) { onSuccess(popped, info) }         5. state = .ready(controller)
                                                         6. delegate?.adManager(didLoad: ad)
```

**Key difference**: Android pops from Main on warm start. iOS leaves the ad in cache and pops later in `show()`. Android approach prevents double-serve race conditions.

**Note**: Both platforms only check Main cache for warm start. Fallback is NOT checked here — it is only consulted after auction failure.

---

### 2. load() — Cold Start (Auction)

When no warm ad is available:

```
Android (TwoLevelAdManager.cacheInternal):              iOS (ZhenyaFullscreenAdManager.loadAd → performAuction):
─────────────────────────────────────────────            ──────────────────────────────────────────────────────
1. auctionRunning.CAS(false, true)                       1. super.loadAd() → triggers performAuction
   → if already true: return (ignore duplicate)             (implicit state machine guards duplicates)

2. firstFillFired = AtomicBoolean(false)                 2. isFirstLoad = true

3. mainCache.beginIteration()                            3. Cacher.Main.interstitialStorage.beginIteration()
   → resets iterationMaxPrice = null                        → resets iterationMaxPrice = nil

4. controller.start(                                     4. auction = ZhenyaAuctionController { builder in ... }
     demandAd, adTypeParam,                                 auction.singleLoadCompletion = { ... }
     singleLoadCompletion = { ... },                        auction.load { result in ... }
     onComplete = { ... },
   )

5. finally { auctionRunning.set(false) }                 5. self.auction = nil (in load completion)
```

**Key difference**: Android uses `AtomicBoolean` CAS for explicit duplicate load guard. iOS relies on implicit state machine in `BaseFullscreenAdManager`. Android approach is more explicit and safer.

---

### 3. Sequential Auction Pipeline

Both platforms process ad units one-by-one in order. The first fill triggers `singleLoadCompletion` immediately without waiting for remaining units.

```
Android (SequentialAuctionPipeline.execute):             iOS (ZhenyaAuctionController.load):
────────────────────────────────────────────             ───────────────────────────────────
1. Collect RTB tokens from bidding adapters              1. setupDemandRequestOperations()
2. POST /auction → receive adUnits list                     → creates operations from adUnits
3. withTimeout(auctionTimeout) {                         2. setupAuctionTimeout(timeoutInSeconds)
     for (adUnit in adUnits) {                           3. scheduleNextOperation() → loop:
       loadSingleAdUnit(adUnit)                             dequeue → performDemandRequest(op)
       if (fill) singleLoadCompletion(result)               → createFinishDemandOperation:
     }                                                        if bid: singleLoadCompletion(bid)
   }                                                          scheduleNextOperation()
4. onComplete(info, error)                               4. finishAuction → finishAuctionOperation

Per-unit loading:                                        Per-unit loading:
─────────────────                                        ─────────────────
a. Find adapter by demandId                              a. OperationQueue runs operation
b. AdSourceFactory.createAdSource()                      b. Operation finds adapter, creates ad source
c. withTimeout(adUnit.timeout) {                         c. Ad source load with bid/direct flow
     withContext(Main) { adSource.load(params) }            (per-unit timeout in operation)
     adSource.adEvent.first { Fill|Fail|Expired }
   }
d. Fill → AuctionResult.Network/Bidding                  d. Fill → bid available in operation
e. Failure → destroy ad source, continue                 e. Failure → next operation scheduled
```

**Key differences**:
- Android: explicit `withTimeout(adUnit.timeout)` per unit + `withTimeout(auctionTimeout)` global
- iOS: `Timer.scheduledTimer` for global timeout + `timeoutReached()` per unit via Operation cancellation
- Android: coroutine sequential `for` loop — simpler, no Operation/OperationQueue overhead
- iOS: OperationQueue(maxConcurrent=1) with BlockOperation chaining

---

### 4. singleLoadCompletion — Cache Routing

Called immediately for every ad unit that fills. Routes the winner to Main → Fallback → destroy.

```
Android (TwoLevelAdManager):                            iOS (ZhenyaFullscreenAdManager.performAuction):
────────────────────────────                             ──────────────────────────────────────────────
1. isFirst = firstFillFired.CAS(false, true)             1. (isFirstLoad is a simple Bool)

2. mainResult = mainCache.insert(winner, sticky=isFirst) 2. result = Cacher.Main.insert(ad, sticky: isFirstLoad)

3. if (!mainResult.isInserted) {                         3. if result.isInserted {
     fallbackResult = fallbackCache.insert(winner)            adRevenueObserver.observe(bid)
     if (!fallbackResult.isInserted) {                      } else {
       winner.adSource.destroy()  ← CLEANUP                  Cacher.Fallback.insert(ad)  ← NO DESTROY
     }                                                        // if Fallback also rejects → AD LEAKS
   }                                                      }

4. if (isFirst) {                                        4. if isFirstLoad {
     withContext(Main) { onSuccess(winner, info) }            DispatchQueue.main.async {
   }                                                            delegate?.adManager(didLoad: ad)
                                                              }
                                                           }

                                                         5. isFirstLoad = false
```

**Key differences**:
1. **Android destroys ad source on dual rejection**. iOS does NOT — this is a **memory leak** in iOS when both caches are full.
2. **Android uses AtomicBoolean CAS** for `isFirst` — thread-safe. iOS uses simple `Bool` + assignment — not thread-safe if singleLoadCompletion can be called concurrently (though it's sequential in practice).
3. **Android routes to Fallback ONLY on Main rejection**. iOS always inserts into Fallback (even if Main accepted) — but looking at the code, iOS also only routes to Fallback on Main rejection (`else` branch).

---

### 5. Auction Completion

After all ad units have been processed:

```
Android (TwoLevelAuctionController):                    iOS (ZhenyaFullscreenAdManager):
────────────────────────────────────                     ──────────────────────────────────
Pipeline reports (auctionInfo, error):                   auction.load completion(result):

if error != null:                                        case .failure(let error):
  → handlePipelineFailure:                                 DispatchQueue.main.async {
    1. fallbackCache.peek()                                  if let ad = Fallback.peek(), ad.price >= floor
    2. price >= pricefloor?                                    → state = .ready
    3. YES: fallbackCache.popFirst() ← POP                    → delegate.didLoad(ad)  ← NO POP
       → onComplete(info, null)                              else:
    4. NO: onComplete(null, error)                             → state = .idle
                                                               → delegate.didFailToLoad(error)
if error == null:                                            }
  → onComplete(info, null)
                                                         case .success:
                                                           break
```

**Key differences**:
1. **Android pops from Fallback immediately on failure recovery**. iOS peeks but does NOT pop — relies on `show()` to pop later. This creates a **race condition** in iOS: between `handlePerformAuctionRequestFailed` and `show()`, another manager (different auctionKey, same AdType) could pop the same ad from the shared Fallback cache.
2. **Android has a separate `TwoLevelAuctionController`** that encapsulates fallback-on-failure logic. iOS has this logic inline in the manager's `performAuction` closure and `handlePerformAuctionRequestFailed` method — **duplicated in 3 places** (interstitial performAuction, interstitial handleFailed, banner performAuction + banner handleFailed = 4 copies).

---

### 6. show() — Ad Delivery

```
Android (TwoLevelAdManager.pop):                        iOS (ZhenyaFullscreenAdManager.show):
────────────────────────────────                         ──────────────────────────────────────
runBlocking {                                            switch state {
  mainCache.popFirst()                                   case .ready:
    ?: fallbackCache.popFirst()                            guard let ad = Main.popFirst()
}                                                                       ?? Fallback.popFirst()
                                                           → create ImpressionController
                                                           → state = .impression
                                                           → imprController.show(from: rootVC)
                                                         default:
                                                           → delegate.didFailToPresent(.inconsistency)
                                                         }
```

**Key difference**: Android `pop()` is a simple data extraction (return the AuctionResult). iOS `show()` creates an ImpressionController and begins the impression flow. The impression/show logic in Android is handled by the caller (`InterstitialImpl`), not the cache.

---

### 7. peek() / isReady

```
Android (TwoLevelAdManager.peek):                       iOS (ZhenyaAdManager.isReady):
──────────────────────────────────                       ──────────────────────────────
mainCache.peekSnapshot()                                 Cacher.Main.interstitialStorage.peek() != nil
  ?: fallbackCache.peekSnapshot()                          || Cacher.Fallback.interstitialStorage.peek() != nil

→ Lock-free via @Volatile headSnapshot                   → NSLock.lock() on every peek
→ May be stale by one operation (acceptable)             → Always exact (but contends with insert/pop)
```

**Key difference**: Android uses `@Volatile headSnapshot` for lock-free synchronous reads. iOS acquires `NSLock` on every `peek()` call. Android approach avoids lock contention on hot path (isReady is called frequently).

---

### 8. clear() — Cleanup

```
Android (TwoLevelAdManager.clear):                      iOS (ZhenyaManagerPool.removeManager):
──────────────────────────────────                       ──────────────────────────────────────
scope.launch {                                           queue.async(flags: .barrier) {
  ManagerPool.remove(auctionKey)                           managers.removeValue(forKey: key)
}                                                        }
→ Does NOT clear shared CacheStores                      → Does NOT clear shared Cacher stores
→ Static stores survive manager removal                  → Static stores survive manager removal
```

Both platforms: `clear()` only removes the manager from the pool. The shared per-AdType cache stores persist independently.

---

## CacheStorage (Main Cache) — Insert Algorithm

Port of `CacheStorage.swift`. Sorted array with sticky-head and per-iteration threshold filtering.

### insert(element, sticky) — Step by Step

```
Step 1: ITERATION THRESHOLD (capacity > 1 only)
├── First item in iteration (iterationMaxPrice == null)? → set max, PASS
├── price > currentMax? → update max, PASS
├── price >= currentMax * (threshold / 100)? → PASS
└── Otherwise → REJECT (InsertResult.Reason.IterationThreshold)

Step 2: DUPLICATE CHECK by demandId
├── Same key, same price → update in-place (protect sticky head from non-sticky update)
├── Same key, different price → remove old entry, fall through to re-insert
└── No duplicate → continue

Step 3: STICKY HEAD PROTECTION (capacity == 1 only)
├── capacity == 1 AND stickyHeadActive AND items.isNotEmpty AND !sticky
└── → REJECT (InsertResult.Reason.StickyHeadProtected)

Step 4: CAPACITY CHECK (no eager eviction)
├── items.size >= capacity?
│   ├── cheapestAllowedToEvictPrice() → items.last().price (O(1), sorted descending)
│   │   In sticky mode: requires >= 2 items (head is protected), else null
│   ├── cheapest != null AND price <= cheapest? → REJECT (InsertResult.Reason.CacheFull)
│   └── cheapest == null (only sticky head)? → PASS (trimIfNeeded handles overflow)
└── items.size < capacity → PASS

Step 5: INSERT + SORT + TRIM
├── sticky → items.add(0, element), stickyHeadActive = true
├── non-sticky → items.add(element)  // append to tail
├── sortAccordingToMode():
│   ├── stickyHeadActive → sort only tail items[1..] descending by price
│   └── normal → sort all items descending by price
├── trimIfNeeded() → while items.size > capacity: remove items.last()
│   → returns evicted items → caller destroys their ad sources
└── Update headSnapshot
```

### popFirst()

```
1. Remove items[0]
2. stickyHeadActive = false
3. sortAccordingToMode() → now sorts ALL items (sticky disabled)
4. rebuildIndex()
5. Update headSnapshot
```

### beginIteration()

```
Reset iterationMaxPrice = null
Called before each auction round to reset threshold tracking.
```

---

## FallbackCacheStorage — Insert Algorithm

Port of `FallbackCacheStorage.swift`. No sticky mode, no iteration threshold.

### insert(element) — Step by Step

```
Step 1: DUPLICATE CHECK by demandId
├── Same key, same price → update in-place, sort, SUCCESS
├── Same key, different price → remove old, fall through
└── No duplicate → continue

Step 2: CAPACITY EVICTION (strict > required)
├── items.size >= capacity?
│   ├── Find cheapest via minByOrNull { price } (Android)
│   │   iOS uses items.last (assumes sorted — see bugs section)
│   ├── price > cheapest? → evict cheapest, destroy its ad source
│   └── price <= cheapest? → REJECT (InsertResult.Reason.CacheFull)
│       Note: equal price is REJECTED (strict >, not >=)
└── items.size < capacity → continue

Step 3: INSERT
├── items.add(element)
├── items.sortByDescending { price }
└── Update headSnapshot
```

---

## ManagerPool Lifecycle

Singleton pool managing `TwoLevelAdManager` instances per auctionKey.

```
getOrCreate(auctionKey, demandAd, config):
├── Existing entry with live WeakReference? → reuse manager
├── Dead WeakReference? → remove entry, create new
└── No entry? → create new:
    1. TwoLevelCacheStores.getOrCreate(adType, config) → StorePair(main, fallback)
    2. SequentialAuctionPipeline(adaptersSource, getTokens, getAuctionRequest, ...)
    3. TwoLevelAuctionController(pipeline, fallbackCache)
    4. TwoLevelAdManager(demandAd, mainCache, fallbackCache, controller, auctionKey)
    5. Pool entry = PoolEntry(WeakReference(manager), adType, createdAt)

Periodic cleanup (every 60s):
├── For each entry:
│   ├── isWeakRefDead = weakRef.get() == null
│   ├── isIdle = manager?.isIdle() ?: true  (no auction running)
│   ├── isOldEnough = (now - createdAt) > 5 min
│   └── Remove if: isIdle AND (isOldEnough OR isWeakRefDead)
└── Log removed entries
```

### iOS vs Android Pool

| Aspect | Android | iOS |
|--------|---------|-----|
| WeakReference target | `TwoLevelAdManager` | `Interstitial` (the caller, not the manager) |
| Cleanup condition | `isIdle && (old || dead)` | `!isActive && (!isRecent || !hasInterstitial)` |
| Thread safety | `kotlinx.coroutines.sync.Mutex` | `DispatchQueue(concurrent)` with barrier |
| Timer | `CoroutineScope` + `delay(60s)` loop | `Timer.scheduledTimer(60s, repeats: true)` |
| Manager creation | Explicit DI via `get()` | Builder pattern with generics |
| Delegate update | N/A (callback-based) | Updates `delegate` on reuse (weak ref to Interstitial may change) |

---

## TwoLevelCacheStores — Singleton Pattern

```
Android:                                                iOS (Cacher.swift):
────────                                                ────────────────────
object TwoLevelCacheStores {                            final class Cacher {
  private val stores = mutableMap<AdType, StorePair>()    enum Main {
  fun getOrCreate(adType, config): StorePair {              static let bannerStorage = CacheStorage(...)
    stores.getOrPut(adType) { StorePair(...) }              static let interstitialStorage = CacheStorage(...)
  }                                                       }
}                                                         enum Fallback {
                                                            static let bannerStorage = FallbackCacheStorage(...)
Lazy init on first access per AdType.                       static let interstitialStorage = FallbackCacheStorage(...)
Guarded by ManagerPool Mutex.                             }
Config used only on first creation.                     }
                                                        Eager init at class load time.
                                                        Config read from AppManager at init.
```

**Key difference**: Android is lazy (created on first access), iOS is eager (static let = initialized once at class load). Android can pick up config changes before first cache use.

---

## Config

### TwoLevelCacheConfig

| Field | Default | Range | JSON key |
|-------|---------|-------|----------|
| mainCacheSize | 10 | 1-10 | `adunit_cache_size` |
| fallbackCacheSize | 10 | 1-10 | `fallback_cache_size` |
| threshold | 80 | 0-100 | `threshold` |

### Config JSON Example

```json
{
  "cache_settings": {
    "interstitial": {
      "strategy_version": "v2",
      "adunit_cache_size": 3,
      "fallback_cache_size": 2,
      "threshold": 80
    },
    "banner": {
      "strategy_version": "v2",
      "adunit_cache_size": 5,
      "fallback_cache_size": 5,
      "threshold": 80
    }
  }
}
```

---

## Thread Safety

| Component | Mechanism | Notes |
|-----------|-----------|-------|
| CacheStorage | `Mutex` for mutations, `@Volatile headSnapshot` for lock-free peek | Coroutine-friendly; headSnapshot may be stale by one op |
| FallbackCacheStorage | `Mutex` for mutations, `@Volatile headSnapshot` for lock-free peek | Same pattern as Main |
| TwoLevelCacheStores | Guarded by caller (ManagerPool Mutex) | Not internally synchronized |
| ManagerPool | `Mutex` for pool access + cleanup | One lock for all operations |
| SequentialAuctionPipeline | Sequential by design (coroutine `for` loop) | No concurrent ad unit loading |
| TwoLevelAdManager | `AtomicBoolean` for auction-running guard | CAS prevents duplicate starts |
| Callbacks (onSuccess/onFailure) | Always `withContext(Dispatchers.Main)` | UI-safe delivery |

---

## Android vs iOS — All Differences

### Behavioral Differences

| # | Aspect | Android | iOS | Impact |
|---|--------|---------|-----|--------|
| 1 | **Warm start pop** | Pops from Main immediately | Leaves in cache, pops in `show()` | Android prevents double-serve |
| 2 | **Duplicate load guard** | `AtomicBoolean.CAS(false, true)` | Implicit state machine in base class | Android is explicit and thread-safe |
| 3 | **First-fill flag** | `AtomicBoolean` (thread-safe) | `Bool isFirstLoad` (not thread-safe) | Android is safer for concurrent access |
| 4 | **Dual rejection → destroy** | `winner.adSource.destroy()` | No destroy (leak) | Android prevents memory leak |
| 5 | **Fallback-on-failure pop** | peek + pop immediately | peek only, defer pop to show() | Android avoids race condition |
| 6 | **Per-unit timeout** | `withTimeout(adUnit.timeout)` | Global timeout only via Timer | Android more granular |
| 7 | **Fallback cheapest lookup** | `minByOrNull { price }` (safe) | `items.last` (assumes sorted) | Android doesn't rely on sort invariant |
| 8 | **headSnapshot for peek** | `@Volatile` lock-free | `NSLock` on every peek | Android avoids contention |
| 9 | **Pool WeakRef target** | `TwoLevelAdManager` | `Interstitial` (the caller) | Android tracks the actual managed object |
| 10 | **Stores initialization** | Lazy (on first access) | Eager (static let) | Android can apply config before init |
| 11 | **Banner manager** | Unified `TwoLevelAdManager` for all types | Separate `ZhenyaBannerAdManager` class | Android is simpler |
| 12 | **Ad source cleanup on evict** | `trimIfNeeded()` returns evicted → `destroy()` | `trimIfNeeded()` just removes, no destroy | Android prevents ad source leaks |

### Code Structure Differences

| # | Aspect | Android | iOS |
|---|--------|---------|-----|
| 1 | Auction controller | Split: `SequentialAuctionPipeline` (execution) + `TwoLevelAuctionController` (orchestration) | Single class `ZhenyaAuctionController` with OperationQueue |
| 2 | Fallback-on-failure code | One place: `TwoLevelAuctionController.handlePipelineFailure()` | Duplicated in 4 places (interstitial + banner × performAuction + handleFailed) |
| 3 | Manager class | One `TwoLevelAdManager` for all ad types | Separate `ZhenyaAdManager<...>` (interstitial) + `ZhenyaBannerAdManager` (banner) |
| 4 | DI | Kodein `get()` | Generic builder pattern + typed context |
| 5 | Concurrency primitives | `Mutex`, `AtomicBoolean`, `Dispatchers`, `withTimeout` | `NSLock`, `DispatchQueue`, `OperationQueue`, `Timer` |

### CacheStorage.insert() Differences

| Step | Android | iOS | Notes |
|------|---------|-----|-------|
| 4: capacity check | `items.size >= capacity` | `items.count == capacity` | Android uses `>=` (stricter); iOS uses `==` (exact). Both work in normal flow — items never exceed capacity outside of step 5. |
| 5: empty list insert | Always uses sticky/non-sticky path | Special-cases `items.isEmpty`: appends + sets `stickyHeadActive = sticky` | iOS is more nuanced for empty list; both produce correct results |

### CacheStorage.sortAccordingToMode() Difference

| Android | iOS |
|---------|-----|
| Guard: `items.size > 1` | Guard: `items.count > 2` |
| Sorts tail when 2+ items exist | Only sorts tail when 3+ items exist |

This is an **iOS bug** — see Bugs section.

### CacheStorage.popFirst() Difference

| Android | iOS |
|---------|-----|
| Always calls `sortAccordingToMode()` | Only sorts if `stickyHeadActive` was true |
| Then `stickyHeadActive = false` (before sort) | Then `stickyHeadActive = false` (before sort) |

If popping when `stickyHeadActive == false`, iOS skips sorting. This is an **iOS bug** — cache can become unsorted.

---

## iOS Bugs Found During Validation

### Bug 1: CacheStorage.sortTailKeepingHead() guard is `count > 2` instead of `count > 1`

**File**: `CacheStorage.swift:218`
```swift
private func sortTailKeepingHead() {
    guard items.count > 2 else { return }  // BUG: should be > 1
    ...
}
```
**Impact**: With exactly 2 items in sticky mode `[stickyHead, item2]`, the tail is never sorted. If item2 was inserted with a higher price than a later item, the ordering is wrong.
**Android fix**: Uses `items.size > 1` — correctly sorts tail when 2+ items.

### Bug 2: CacheStorage.popFirst() doesn't sort when stickyHeadActive == false

**File**: `CacheStorage.swift:145-148`
```swift
if stickyHeadActive {
    stickyHeadActive = false
    items.sort { $0.price > $1.price }
}
// No sort when stickyHeadActive was already false!
```
**Impact**: If `popFirst()` is called when `stickyHeadActive == false`, remaining items are not re-sorted. Example: `[100, 90, 95]` → pop 100 → `[90, 95]` (unsorted).
**Android fix**: Always calls `sortAccordingToMode()` after pop.

### Bug 3: singleLoadCompletion leaks ad source on dual rejection

**File**: `ZhenyaFullscreenAdManager.swift:168-170`
```swift
if !result.isInserted {
    Cacher.Fallback.interstitialStorage.insert(ad)
    // If Fallback ALSO rejects → ad source is never destroyed
}
```
**Impact**: Memory leak when both Main and Fallback caches are full. The loaded ad source is abandoned without cleanup.
**Android fix**: Explicit `winner.adSource.destroy()` when both caches reject.

### Bug 4: Fallback-on-failure peek without pop — race condition

**File**: `ZhenyaFullscreenAdManager.swift:89,217`
```swift
if let ad = Cacher.Fallback.interstitialStorage.peek() as? BidContainer, ad.price >= self.pricefloor {
    self.state = .ready(controller: controller)
    self.delegate?.adManager(self, didLoad: ad, ...)
    // Ad remains in Fallback! Another manager could pop it before show()
}
```
**Impact**: Between `handlePerformAuctionRequestFailed`/`auction.load completion` and `show()`, another manager with different auctionKey but same AdType could pop the same ad from the shared Fallback cache. Leads to "ready but nothing to show" state.
**Android fix**: `TwoLevelAuctionController.handlePipelineFailure()` does peek + popFirst() immediately.

### Bug 5: FallbackCacheStorage.peek() logs with wrong tag

**File**: `FallbackCacheStorage.swift:92`
```swift
Logger.debug("[ZhenyaCache] [Main] Peek: \(format(first))")
// Should be [Fallback], not [Main] — copy-paste error
```
**Impact**: Misleading debug logs.

### Bug 6: FallbackCacheStorage relies on items.last for cheapest (fragile)

**File**: `FallbackCacheStorage.swift:54`
```swift
let cheapest = items.last!  // Assumes sorted descending
```
**Impact**: After duplicate-replacement path (remove old + rebuildIndex on line 46-49), items list is NOT re-sorted before the capacity check on line 53. `items.last` may not be the actual cheapest.
**Android fix**: Uses `items.minByOrNull { it.price() }` — always finds the true minimum regardless of sort order.

### Bug 7: Duplicated fallback-on-failure code across 4 locations

**Files**: `ZhenyaFullscreenAdManager.swift` (2 places) + `ZhenyaSandbox.swift` (2 places)
**Impact**: Maintenance burden — any fix must be applied in all 4 copies. Risk of divergence.
**Android fix**: Single `TwoLevelAuctionController.handlePipelineFailure()` method.

---

## Android Fixes Applied During Port

Issues found and fixed in Android during validation against iOS source code:

1. **CacheStorage step 3: missing `!sticky` guard** (capacity==1) — rejected ALL inserts including sticky. Fixed: added `&& !sticky`.
2. **CacheStorage step 4: null cheapest treated as reject** — iOS skips via `if let`. Fixed: `cheapest != null && price <= cheapest`.
3. **CacheStorage step 4: eager eviction before insert** — iOS defers to `trimIfNeeded()`. Fixed: removed eviction, only reject check.
4. **CacheStorage step 5: sticky insert at tail** — iOS inserts at head. Fixed: `items.add(0, element)`.
5. **CacheStorage cheapestAllowedToEvictPrice O(n)** — iOS uses `items.last` O(1). Fixed: renamed + O(1).
6. **Missing `beginIteration()` before auction** — `iterationMaxPrice` could carry over from previous round. Fixed: added call before `controller.start()`.

---

## Tests

- `CacheStorageTest.kt` — 30 tests:
  - Basic insert and retrieval
  - Sticky head protection (capacity > 1 and capacity == 1)
  - Iteration threshold filtering
  - Capacity eviction and trimming
  - Duplicate handling (same price update, different price re-insert)
  - `beginIteration()` reset
  - `popFirst()` and sorting
  - Edge cases: capacity == 1 with two sticky inserts

- `FallbackCacheStorageTest.kt` — 19 tests:
  - Basic insert and retrieval
  - Strict `>` eviction (equal price rejected)
  - Capacity overflow eviction
  - Duplicate handling
  - `popFirst()` ordering

---

## Out of Scope

- Rewarded ad type support
- TTL / periodic sweep of cached ads
- ShowWithFallback recursive retry
- RtbPayloadCache / skip-token optimization (Denis-specific)
