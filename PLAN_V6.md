# PLAN V6: Two-Level Cache Implementation

## Status: IMPLEMENTED

All phases complete. Build passes, 46 tests pass (27 CacheStorage + 19 FallbackCacheStorage).

---

## What Was Built

Full port of iOS Zhenya Two-Level Cache strategy with custom sequential auction pipeline.

### Files Created

| File | iOS Equivalent | Description |
|------|---------------|-------------|
| `storage/CacheStorage.kt` | `CacheStorage.swift` | Main cache: sorted array, sticky head, iteration threshold, Mutex |
| `storage/FallbackCacheStorage.kt` | `FallbackCacheStorage.swift` | Fallback cache: simpler sorted store, strict `>` eviction |
| `storage/InsertResult.kt` | — | Sealed class: Success / Rejected(reason) |
| `storage/TwoLevelCacheStores.kt` | `Cacher.swift` | Static singleton StorePair(main, fallback) per AdType |
| `auction/SequentialAuctionPipeline.kt` | `ZhenyaAuctionController.swift` | Custom sequential waterfall (replaces Auction.start()) |
| `auction/ZhenyaAuctionController.kt` | `ZhenyaAuctionController.swift` | Thin orchestrator + fallback-on-failure |
| `ZhenyaAdManager.kt` | `ZhenyaFullscreenAdManager.swift` | AdCache facade: warm start, cold start, singleLoadCompletion |
| `ZhenyaAdManagerProxy.kt` | — | Lazy proxy for suspending ManagerPool.getOrCreate |
| `AdCacheTwoLevelFactory.kt` | — | Factory entry point |
| `pool/ManagerPool.kt` | `ZhenyaManagerPool.swift` | Singleton pool, WeakReference, periodic cleanup |
| `config/TwoLevelCacheConfig.kt` | `AdCacheConfig.swift` | Config parser (defaults: 10/10/80) |

### Files Modified

| File | Change |
|------|--------|
| `AdCacheVersion.kt` | Added `V6` data object |
| `AdCacheFactoryImpl.kt` | Added V6 branch |

### Test Files

| File | Tests |
|------|-------|
| `storage/CacheStorageTest.kt` | 27 tests |
| `storage/FallbackCacheStorageTest.kt` | 19 tests |

---

## Key Design Decisions

### 1. Custom Sequential Auction (not Auction.start() wrapper)

**Decision:** Built `SequentialAuctionPipeline` that directly uses `GetTokensUseCase`, `GetAuctionRequestUseCase`, `AdSourceFactory` — no dependency on `Auction.start()`.

**Why:** iOS `ZhenyaAuctionController` processes ad units one-by-one via `OperationQueue(maxConcurrent=1)` and fires `singleLoadCompletion` immediately per fill. The standard `Auction.start()` delivers ALL results at once after the full waterfall, making it impossible to fire onLoad on first fill without waiting for everything.

**Reference:** Denis V2 also uses a custom auction (`ParallelAuctionOrchestrator`, `CpmProcessor`, `RtbProcessor`), so this pattern has precedent in the codebase.

### 2. Static Singleton Stores Per AdType

**Decision:** `TwoLevelCacheStores` maintains one `StorePair(main, fallback)` per `AdType`, shared across all auctionKeys.

**Why:** Mirrors iOS `Cacher.swift` where `Main.interstitialStorage` and `Fallback.interstitialStorage` are static. Multiple managers for the same ad type share the same cache.

### 3. WeakReference in ManagerPool

**Decision:** Pool stores `WeakReference<ZhenyaAdManager>` instead of strong reference.

**Why:** Mirrors iOS `weak var interstitial` in `ZhenyaManagerPool`. When InterstitialImpl is GC'd, the weak ref is cleared and the pool entry becomes eligible for cleanup. Prevents memory leaks.

### 4. Lock-free peekSnapshot()

**Decision:** `@Volatile headSnapshot` field updated inside Mutex, read without locking.

**Why:** `AdCache.peek()` (isReady check) is called synchronously from any thread. Using `runBlocking` for a Mutex-protected read would risk deadlock. A stale value is acceptable — worst case: one extra auction or one missed warm start.

---

## Corrections Applied (iOS doc vs iOS code)

| Area | iOS Doc / Original SPEC | Actual iOS Code | What We Implemented |
|------|------------------------|----------------|-------------------|
| Cache scope | Per-auctionKey | Static singleton per AdType | Static singleton per AdType |
| Default capacity | 1 | 10 (Cacher.swift) | 10 |
| Default threshold | 70% | 80% (Cacher.swift) | 80% |
| Auction model | Wrap Auction.start() | OperationQueue sequential | Custom SequentialAuctionPipeline |
| Eviction (Fallback) | `>=` | Strict `>` | Strict `>` |
| Sticky sort | Sort all | Sort only tail in sticky mode | Sort only tail in sticky mode |

---

## Out of Scope

- Rewarded ad type support
- TTL / periodic sweep
- ShowWithFallback recursive retry
- RtbPayloadCache / skip-token optimization (Denis-specific)
