# iOS Reference: Zhenya Two-Level Cache

## Local iOS Sources

Path: `/Users/glavatskikh/XcodeProjects/bidon-sdk-ios`
Branch: `feature/ad-caching`
GitHub: `bidon-io/bidon-sdk-ios`

## Key Files

| File | Path | Purpose |
|------|------|---------|
| CacheStorage.swift | `Bidon/SDK/CacheSandbox/Zhenya/Formats/Fullscreen/CacheStorage.swift` | Main cache: sorted array, sticky head, iteration threshold |
| FallbackCacheStorage.swift | `Bidon/SDK/CacheSandbox/Zhenya/Formats/Fullscreen/FallbackCacheStorage.swift` | Fallback cache: simpler sorted store |
| Cacher.swift | `Bidon/SDK/CacheSandbox/Zhenya/Formats/Fullscreen/Cacher.swift` | Static singletons per ad type (Main + Fallback) |
| ZhenyaManagerPool.swift | `Bidon/SDK/CacheSandbox/Zhenya/Formats/Fullscreen/ZhenyaManagerPool.swift` | Singleton pool keyed by auctionKey, weak refs, cleanup |
| ZhenyaFullscreenAdManager.swift | `Bidon/SDK/CacheSandbox/Zhenya/Formats/Fullscreen/ZhenyaFullscreenAdManager.swift` | Main facade: load/show/warm-start/singleLoadCompletion |
| ZhenyaAuctionController.swift | `Bidon/Modules/Auction/Controller/ZhenyaAuctionController.swift` | Sequential waterfall auction, singleLoadCompletion per-bid |
| AdCacheConfig.swift | `Bidon/SDK/Cache/AdCacheConfig.swift` | Config: adunitCacheSize, fallbackCacheSize, threshold |
| cache_strategy_two_levels.md | root | Architecture doc in Russian |

## Key iOS Patterns Ported

### Cache Scope: Static Singleton Per AdType
```swift
// Cacher.swift — one storage instance per ad type, shared across all auctionKeys
enum Main {
    static let interstitialStorage = CacheStorage(capacity: config?.interstitial.adunitCacheSize ?? 10, iterationThreshold: config?.interstitial.threshold ?? 80)
}
enum Fallback {
    static let interstitialStorage = FallbackCacheStorage(capacity: config?.interstitial.fallbackCacheSize ?? 10)
}
```
Android equivalent: `TwoLevelCacheStores.getOrCreate(adType, config)` returns shared `StorePair(main, fallback)`.

### Defaults: capacity=10, threshold=80
iOS `Cacher.swift` uses 10/10/80 as fallbacks. Android `TwoLevelCacheConfig` matches.

### CacheStorage.insert() Flow
1. Iteration threshold check (capacity > 1 only) — `shouldRejectByIterationThreshold`
2. Duplicate check by id (same price = update, diff price = remove + reinsert)
3. Sticky head protection (capacity == 1, sticky active, non-sticky insert = reject)
4. Cache full check — `cheapestAllowedToEvictPrice()` respects sticky mode
5. Insert + `sortAccordingToMode()` + `trimIfNeeded()`

Android `CacheStorage.kt` follows this exact flow.

### sortAccordingToMode()
- Sticky mode: sort only tail `items[1..]`, head stays at [0]
- Normal mode: sort all items

### FallbackCacheStorage.insert()
- Eviction uses strict `>` (NOT `>=`): `element.price > cheapest.price`

### ZhenyaFullscreenAdManager.loadAd() Flow
1. Warm start: `Main.peek()` with price >= pricefloor -> onLoad immediately
2. Cold start: `performAuction()` -> `beginIteration()` -> sequential waterfall
3. `singleLoadCompletion`: try `Main.insert(sticky: isFirstLoad)`, if rejected -> `Fallback.insert()`
4. First fill -> fire `delegate.didLoad` on main thread
5. Auction failure -> check `Fallback.peek()` >= pricefloor, serve or fail

Android `ZhenyaAdManager.kt` follows this exact flow.

### ZhenyaAuctionController (Sequential Waterfall)
- `OperationQueue(maxConcurrentOperationCount=1)` — processes ad units one by one
- `createFinishDemandOperation`: after each unit completes, calls `singleLoadCompletion` for fills
- `scheduleNextOperation()`: dequeues next pending operation after current finishes
- `load(completion:)` fires when ALL units processed (via `finishAuctionOperation`)

Android equivalent: `SequentialAuctionPipeline.kt` uses a sequential coroutine `for` loop — same semantics as `OperationQueue(maxConcurrent=1)`.

### ZhenyaManagerPool
- Singleton keyed by `auctionKey ?? "default"`
- `weak var interstitial` — entry eligible for cleanup when Interstitial deallocated
- Cleanup every 60s: remove entries that are idle AND (older than 5min OR weak ref dead)
- `getOrCreateManager()` / `getManager()` / `removeManager()`

Android `ManagerPool.kt` uses `WeakReference<ZhenyaAdManager>` for the same lifecycle semantics.
