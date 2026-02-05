# Architecture Research: Ad Caching v2 with Parallel Processing

**Domain:** Mobile Ad Mediation SDK - Two-Level Cache System
**Researched:** 2026-02-05
**Confidence:** HIGH

## Standard Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           APPLICATION LAYER                                  │
│  (Publisher App - Integration via BidonSdk API)                              │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   │ loadAd(pricefloor)
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AD CACHE LAYER (v2)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────┐    ┌────────────────────┐    ┌─────────────────┐   │
│  │  AdCacheDenisImpl  │───▶│  AuctionCoordinator│───▶│ CacheManager    │   │
│  │  (Entry Point)     │    │  (Flow Control)     │    │ (Stores)        │   │
│  └────────────────────┘    └────────────────────┘    └─────────────────┘   │
│           │                         │                          │            │
│           │                         │                          ▼            │
│           │                         │                 ┌─────────────────┐   │
│           │                         │                 │ READY_TO_SHOW   │   │
│           │                         │                 │ (Singleton)     │   │
│           │                         │                 │ ConcurrentMap   │   │
│           │                         │                 └─────────────────┘   │
│           │                         │                          │            │
│           │                         │                          ▼            │
│           │                         │                 ┌─────────────────┐   │
│           │                         │                 │ RTB_PAYLOAD     │   │
│           │                         │                 │ (Singleton)     │   │
│           │                         │                 │ ConcurrentMap   │   │
│           │                         │                 └─────────────────┘   │
│           │                         ▼                                       │
│           │              ┌────────────────────┐                             │
│           │              │ Parallel Processor │                             │
│           │              │ (Kotlin Coroutines)│                             │
│           │              └────────────────────┘                             │
│           │                      │      │                                   │
│           │                      │      │                                   │
│           │            ┌─────────┘      └──────────┐                        │
│           │            │                           │                        │
│           │            ▼                           ▼                        │
│           │   ┌─────────────────┐       ┌─────────────────┐                │
│           │   │ RTB Processor   │       │ CPM Processor   │                │
│           │   │ (async)         │       │ (async)         │                │
│           │   └─────────────────┘       └─────────────────┘                │
│           │            │                           │                        │
│           └────────────┼───────────────────────────┼────────────────────────┘
│                        │                           │
│                        ▼                           ▼
├─────────────────────────────────────────────────────────────────────────────┤
│                      EXISTING AUCTION LAYER                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────────┐        │
│  │ GetTokensUseCase│  │ GetAuctionRequest │  │ ExecuteAuctionUseCase│       │
│  │ (Reused)        │  │ UseCase (Reused)  │  │ (Reused/Modified)    │       │
│  └─────────────────┘  └──────────────────┘  └─────────────────────┘        │
│          │                     │                        │                   │
│          └─────────────────────┼────────────────────────┘                   │
│                                │                                            │
└────────────────────────────────┼────────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      ADAPTER INTEGRATION LAYER                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ AdmobAdapter │  │ MetaAdapter  │  │ UnityAdapter │  │ BidMachine   │   │
│  │ (CPM)        │  │ (RTB)        │  │ (CPM)        │  │ (RTB)        │   │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘   │
│        │                  │                  │                  │           │
└────────┼──────────────────┼──────────────────┼──────────────────┼───────────┘
         │                  │                  │                  │
         ▼                  ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THIRD-PARTY AD NETWORK SDKS                               │
│  (AdMob SDK, Meta SDK, Unity Ads SDK, BidMachine SDK, etc.)                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| **AdCacheDenisImpl** | Entry point implementing AdCache interface; orchestrates cache-aware auction flow | Kotlin class implementing AdCache with coroutine scope |
| **AuctionCoordinator** | Determines auction type (cold/warm), coordinates parallel processing, manages callbacks | State machine with Flow-based events |
| **CacheManager** | Facade for both cache stores; handles TTL, eviction, thread safety | Singleton object with ConcurrentHashMap stores |
| **READY_TO_SHOW Store** | Application-wide storage for loaded ads ready to display | Singleton object with ConcurrentHashMap<String, ReadyToShowEntry> |
| **RTB_PAYLOAD Store** | Application-wide storage for RTB bid responses (payload only) | Singleton object with ConcurrentHashMap<String, RtbPayloadEntry> |
| **Parallel Processor** | Splits waterfall, launches RTB and CPM processors concurrently | Coroutine scope with async/await pattern |
| **RTB Processor** | Loads first RTB ad, caches rest as payloads | Async coroutine with sequential fallback logic |
| **CPM Processor** | Sequentially loads CPM ads, caches each success | Async coroutine with sequential processing |
| **GetTokensUseCase** | Collects RTB tokens from adapters (reused from existing) | Parallel coroutine calls to adapters |
| **GetAuctionRequestUseCase** | Builds and sends /v2/auction request (reused) | HTTP POST with device/app/token data |
| **ExecuteAuctionUseCase** | Executes waterfall processing (modified for v2) | Sequential or parallel based on cache version |
| **AdSource** | Adapter-specific ad loading implementation | Adapter-provided interface implementation |
| **AuctionResolver** | Sorts auction results by price (reused) | Price-based descending sort |

## Recommended Project Structure

```
bidon/src/main/java/org/bidon/sdk/ads/cache/
├── AdCache.kt                           # Interface (existing, unchanged)
├── AdCacheFactory.kt                    # Factory interface (existing)
├── AdCacheVersion.kt                    # Version enum (existing)
├── impl/
│   ├── AdCacheImpl.kt                   # Old implementation (existing)
│   ├── AdCacheFactoryImpl.kt            # Factory implementation
│   ├── AdCacheDenisImpl.kt              # NEW: v2 entry point
│   └── denis/                           # NEW: v2 components package
│       ├── coordinator/
│       │   ├── AuctionCoordinator.kt    # Flow control and state machine
│       │   ├── AuctionType.kt           # Cold vs Warm start enum
│       │   └── CallbackManager.kt       # onAdLoaded coordination (exactly once)
│       ├── stores/
│       │   ├── CacheManager.kt          # Facade for both stores
│       │   ├── ReadyToShowStore.kt      # Singleton: READY_TO_SHOW cache
│       │   ├── RtbPayloadStore.kt       # Singleton: RTB_PAYLOAD cache
│       │   ├── ReadyToShowEntry.kt      # Data class for loaded ads
│       │   ├── RtbPayloadEntry.kt       # Data class for RTB payloads
│       │   └── EvictionPolicy.kt        # TTL checker and sweeper
│       ├── processors/
│       │   ├── ParallelProcessor.kt     # Splits waterfall, launches processors
│       │   ├── RtbProcessor.kt          # RTB group processing (async)
│       │   ├── CpmProcessor.kt          # CPM group processing (async)
│       │   └── WaterfallSplitter.kt     # Splits adUnits by bidType
│       └── lifecycle/
│           ├── PeriodicSweeper.kt       # Background job for expired entries
│           └── CancellationHandler.kt   # CPM cancellation on showAd()
```

### Structure Rationale

- **denis/ package:** Isolates v2 implementation from existing code; easily removable if rollback needed
- **coordinator/ subpackage:** Separates flow control logic from caching and processing
- **stores/ subpackage:** Groups all cache-related storage, clear ownership of data
- **processors/ subpackage:** Parallel processing logic encapsulated, easier to test independently
- **lifecycle/ subpackage:** Time-based operations (TTL sweep, cancellation) separated from business logic

## Architectural Patterns

### Pattern 1: Two-Level Cache (L1/L2 Pattern)

**What:** Hierarchical caching with different purposes per level - READY_TO_SHOW (L1) holds displayable ads, RTB_PAYLOAD (L2) holds bid responses for reuse

**When to use:** When cached data has different lifecycles and usage patterns. L1 = hot cache (ready to use), L2 = warm cache (needs processing before use)

**Trade-offs:**
- ✅ Faster warm starts (L1 hit returns immediately)
- ✅ Reduced token collection overhead (L2 hit skips network calls)
- ⚠️ Increased memory footprint (two separate stores)
- ⚠️ More complex invalidation logic (coordinate TTL across stores)

**Example:**
```kotlin
// L1: READY_TO_SHOW - immediate use
object ReadyToShowStore {
    private val cache = ConcurrentHashMap<String, ReadyToShowEntry>()

    fun getBest(): ReadyToShowEntry? =
        cache.values
            .filter { !it.isExpired() }
            .maxByOrNull { it.ecpm }
}

// L2: RTB_PAYLOAD - needs adapter.load() before use
object RtbPayloadStore {
    private val cache = ConcurrentHashMap<String, RtbPayloadEntry>()

    fun getCachedDemandIds(): Set<String> =
        cache.values
            .filter { !it.isExpired() }
            .map { it.demandId }
            .toSet()
}
```

### Pattern 2: Parallel Processing with First-Response Callback

**What:** Split work into independent groups (RTB/CPM), process concurrently, trigger callback on first success from either group

**When to use:** When different processing paths have varying latencies and first result is sufficient for user feedback

**Trade-offs:**
- ✅ Reduced perceived latency (callback on first fill, not last)
- ✅ Better utilization of network parallelism
- ⚠️ Requires careful callback coordination (exactly-once semantics)
- ⚠️ Background work continues after callback (resource usage)

**Example:**
```kotlin
class ParallelProcessor(private val callbackManager: CallbackManager) {
    suspend fun process(rtbGroup: List<AdUnit>, cpmGroup: List<AdUnit>) {
        coroutineScope {
            val rtbJob = async { processRtbGroup(rtbGroup) }
            val cpmJob = async { processCpmGroup(cpmGroup) }

            // Both run in parallel, callback fires on first fill
            rtbJob.start()
            cpmJob.start()
        }
    }

    private suspend fun processRtbGroup(group: List<AdUnit>) {
        val result = loadFirstRtb(group[0])
        if (result.isSuccess) {
            callbackManager.triggerOnAdLoadedOnce(result)
            cacheRemainingPayloads(group.drop(1))
        }
    }
}
```

### Pattern 3: Application-Wide Singleton Stores

**What:** Cache stores scoped to entire application lifecycle, shared across all ad instances of same type

**When to use:** When cached data is valid across multiple ad requests and should persist beyond single ad instance lifecycle

**Trade-offs:**
- ✅ Maximum reuse of cached data across instances
- ✅ Simplifies memory management (single storage location)
- ⚠️ Must handle concurrent access from multiple threads
- ⚠️ Requires explicit cleanup on SDK destroy (not automatic)

**Example:**
```kotlin
// Application-wide singleton using object declaration
object ReadyToShowStore {
    // Thread-safe via ConcurrentHashMap
    private val cache = ConcurrentHashMap<String, ReadyToShowEntry>()

    fun put(entry: ReadyToShowEntry) {
        // Duplicate policy: replace only if higher eCPM
        cache.compute(entry.adUnitUid) { _, existing ->
            when {
                existing == null -> entry
                entry.ecpm > existing.ecpm -> {
                    existing.adSource.destroy() // cleanup old
                    entry
                }
                else -> existing // keep higher value
            }
        }
    }
}
```

### Pattern 4: Warm Start Optimization

**What:** Check cache state before expensive operations (token collection, auction request); return immediately if cache can satisfy request

**When to use:** When previous work can be reused and latency is critical for user experience

**Trade-offs:**
- ✅ Sub-second response time for warm starts (vs 3-15 seconds cold)
- ✅ Reduced server load and battery consumption
- ⚠️ Slightly stale data (up to TTL age)
- ⚠️ Requires careful cache coherence management

**Example:**
```kotlin
class AuctionCoordinator(
    private val readyToShowStore: ReadyToShowStore,
    private val rtbPayloadStore: RtbPayloadStore
) {
    suspend fun loadAd(pricefloor: Double) {
        val auctionType = determineAuctionType()

        when (auctionType) {
            AuctionType.WARM_START -> {
                // Immediate callback if cache non-empty
                if (!readyToShowStore.isEmpty()) {
                    callbackManager.onAdLoaded(readyToShowStore.getBest())
                }
                // Background: refresh cache with new auction
                launchBackgroundRefresh(pricefloor)
            }
            AuctionType.COLD_START -> {
                // Full flow: tokens → /auction → waterfall
                executeFullAuction(pricefloor)
            }
        }
    }
}
```

### Pattern 5: Dynamic Pricefloor Adjustment

**What:** Set auction pricefloor to maximum of user-provided value, cached READY_TO_SHOW max eCPM, and RTB_PAYLOAD max pricefloor

**When to use:** When cache contains higher-value ads than current request, ensuring new ads don't degrade revenue

**Trade-offs:**
- ✅ Prevents revenue degradation from lower-value fills
- ✅ Automatic optimization without publisher intervention
- ⚠️ May reduce fill rate if cache has unusually high eCPM
- ⚠️ Requires cache state visibility at auction request time

**Example:**
```kotlin
fun calculateDynamicPricefloor(userPricefloor: Double): Double {
    val readyToShowMax = readyToShowStore.getMaxEcpm()
    val rtbPayloadMax = rtbPayloadStore.getMaxEcpm()

    return maxOf(userPricefloor, readyToShowMax, rtbPayloadMax)
}

// Usage in auction request
val auctionRequest = buildAuctionRequest(
    pricefloor = calculateDynamicPricefloor(adTypeParam.pricefloor),
    tokens = collectTokensWithCacheSkip()
)
```

## Data Flow

### Cold Start Flow (First Auction, Empty Cache)

```
Publisher calls loadAd(pricefloor)
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 1. CACHE CHECK                                                  │
│    READY_TO_SHOW.isEmpty() = true                              │
│    RTB_PAYLOAD.isEmpty() = true                                │
│    → AuctionType = COLD_START                                  │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 2. TOKEN COLLECTION (Parallel)                                 │
│    GetTokensUseCase.invoke()                                   │
│    ├─ Call Meta RTB adapter → token                            │
│    ├─ Call BidMachine adapter → token                          │
│    ├─ Call Mintegral adapter → token                           │
│    └─ ...all RTB adapters in parallel                          │
│    Result: Map<demandId, TokenInfo>                            │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 3. AUCTION REQUEST                                             │
│    POST /v2/auction/interstitial                               │
│    Request: {                                                  │
│      auctionId: UUID,                                          │
│      pricefloor: 0.01 (user-provided),                         │
│      tokens: {...collected tokens...},                         │
│      adapters: [...adapter info...],                           │
│      device: {...}, app: {...}, user: {...}                    │
│    }                                                           │
│    Response: {                                                 │
│      adUnits: [                                                │
│        {uid: "rtb1", demandId: "meta", bidType: RTB, pf: 5.0, │
│         payload: {...bid response...}},                        │
│        {uid: "cpm1", demandId: "admob", bidType: CPM, pf: 4.5},│
│        {uid: "rtb2", demandId: "bidm", bidType: RTB, pf: 3.0, │
│         payload: {...}},                                       │
│        {uid: "cpm2", demandId: "unity", bidType: CPM, pf: 2.5} │
│      ]                                                         │
│    }                                                           │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 4. WATERFALL SPLIT                                             │
│    WaterfallSplitter.split(adUnits)                            │
│    RTB Group: [rtb1 $5.0, rtb2 $3.0]  (preserve order)        │
│    CPM Group: [cpm1 $4.5, cpm2 $2.5]  (preserve order)        │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 5. PARALLEL PROCESSING (async launch)                         │
│                                                                │
│    ┌──────────────────────┐    ┌──────────────────────────┐   │
│    │  RTB Processor       │    │  CPM Processor           │   │
│    │  (async)             │    │  (async)                 │   │
│    ├──────────────────────┤    ├──────────────────────────┤   │
│    │ Load rtb1 (Meta):    │    │ Load cpm1 (AdMob):       │   │
│    │   adapter.load()     │    │   adapter.load()         │   │
│    │   → SUCCESS          │    │   → SUCCESS              │   │
│    │   → READY_TO_SHOW.   │    │   → READY_TO_SHOW.       │   │
│    │      put(rtb1)       │    │      put(cpm1)           │   │
│    │   → Callback fired!  │    │   → Callback already     │   │
│    │      onAdLoaded()    │    │      fired, skip         │   │
│    │                      │    │                          │   │
│    │ Save rtb2 payload:   │    │ Load cpm2 (Unity):       │   │
│    │   RTB_PAYLOAD.       │    │   adapter.load()         │   │
│    │   put(rtb2)          │    │   → SUCCESS              │   │
│    │   (not loaded yet)   │    │   → READY_TO_SHOW.       │   │
│    │                      │    │      put(cpm2)           │   │
│    └──────────────────────┘    └──────────────────────────┘   │
│              │                            │                    │
│              └────────────────┬───────────┘                    │
└───────────────────────────────┼────────────────────────────────┘
                                ▼
                       Both complete, cache populated:
                       READY_TO_SHOW: [rtb1, cpm1, cpm2]
                       RTB_PAYLOAD: [rtb2]
```

### Warm Start Flow (Subsequent Auction, Cache Populated)

```
Publisher calls loadAd(pricefloor)
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 1. CACHE CHECK                                                 │
│    READY_TO_SHOW.isEmpty() = false (3 ads cached)             │
│    RTB_PAYLOAD.isEmpty() = false (1 payload cached)           │
│    → AuctionType = WARM_START                                 │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 2. IMMEDIATE CALLBACK                                          │
│    bestAd = READY_TO_SHOW.getBest()  // rtb1 with $5.0 eCPM   │
│    onAdLoaded(bestAd)                                          │
│    ⏱️  <1 second response time                                 │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 3. BACKGROUND REFRESH (async, non-blocking)                   │
│                                                                │
│    Calculate dynamic pricefloor:                              │
│      readyMax = 5.0, payloadMax = 3.0, userPf = 0.01         │
│      dynamicPf = max(5.0, 3.0, 0.01) = 5.0                    │
│                                                                │
│    Collect tokens (with cache skip):                          │
│      RTB_PAYLOAD contains: [bidm]                             │
│      Skip token collection for: bidm                          │
│      Collect tokens for: [meta, mintegral, ...]              │
│                                                                │
│    POST /v2/auction with pricefloor=5.0                       │
│      (Server returns only ads >= $5.0)                        │
│                                                                │
│    Process waterfall (parallel RTB/CPM)                       │
│      New fills → READY_TO_SHOW (may replace if higher eCPM)   │
│      New RTB payloads → RTB_PAYLOAD                           │
│                                                                │
│    Note: No callback fired (already called in step 2)         │
└────────────────────────────────────────────────────────────────┘
```

### showAd() Flow (Best Ad Selection)

```
Publisher calls showAd()
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 1. SELECT BEST AD                                              │
│    bestEntry = READY_TO_SHOW.getBest()  // highest eCPM       │
│    if (bestEntry == null) → throw NoFill error                │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 2. CANCEL BACKGROUND WORK                                     │
│    CancellationHandler.cancelCpmProcessing()                   │
│    (If CPM processing still running, cancel remaining loads)   │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 3. REMOVE FROM CACHE                                           │
│    READY_TO_SHOW.remove(bestEntry.adUnitUid)                  │
│    (Ad now "consumed", no longer available for next show)      │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 4. DISPLAY AD                                                  │
│    bestEntry.adSource.show(activity)                           │
│    → onAdShown() callback                                      │
│    → onRevenuePaid() callback                                  │
│    → onAdClosed() callback                                     │
└────────────────────────────────────────────────────────────────┘
```

### TTL Expiration Flow (Background Sweep)

```
Every 5 minutes (ad-instance scoped coroutine job)
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 1. SWEEP READY_TO_SHOW                                         │
│    for (entry in READY_TO_SHOW.getAll()) {                    │
│      if (entry.isExpired()) {                                 │
│        entry.adSource.destroy()  // cleanup adapter resources │
│        READY_TO_SHOW.remove(entry.adUnitUid)                  │
│        if (entry == winnerAd) {                               │
│          emit(AdEvent.Expired)  // only for winner            │
│        }                                                       │
│      }                                                         │
│    }                                                           │
└────────────────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│ 2. SWEEP RTB_PAYLOAD                                           │
│    for (entry in RTB_PAYLOAD.getAll()) {                      │
│      if (entry.isExpired()) {                                 │
│        RTB_PAYLOAD.remove(entry.adUnitUid)                    │
│        // No destroy() needed, payload-only                    │
│      }                                                         │
│    }                                                           │
└────────────────────────────────────────────────────────────────┘

Note: Lazy eviction also happens on every access (get/getBest/etc.)
```

## Component Boundaries

### What Talks to What

```
AdCacheDenisImpl
    ├─► AuctionCoordinator (delegates all logic)
    └─► CacheManager (for clear() operation)

AuctionCoordinator
    ├─► CacheManager (check state, get ads)
    ├─► CallbackManager (trigger onAdLoaded exactly once)
    ├─► GetTokensUseCase (token collection, existing)
    ├─► GetAuctionRequestUseCase (auction request, existing)
    ├─► WaterfallSplitter (split adUnits by bidType)
    └─► ParallelProcessor (launch RTB/CPM processing)

ParallelProcessor
    ├─► RtbProcessor (async launch)
    ├─► CpmProcessor (async launch)
    └─► CallbackManager (coordinate first-fill callback)

RtbProcessor
    ├─► AdSource (adapter.load() for RTB ads)
    ├─► ReadyToShowStore (put loaded ads)
    ├─► RtbPayloadStore (put remaining payloads)
    └─► CallbackManager (trigger if first fill)

CpmProcessor
    ├─► AdSource (adapter.load() for CPM ads)
    ├─► ReadyToShowStore (put loaded ads)
    └─► CallbackManager (trigger if first fill)

CacheManager (Facade)
    ├─► ReadyToShowStore (all operations)
    └─► RtbPayloadStore (all operations)

ReadyToShowStore (Singleton)
    └─► EvictionPolicy (check TTL on access)

RtbPayloadStore (Singleton)
    └─► EvictionPolicy (check TTL on access)

PeriodicSweeper (Background Job)
    ├─► ReadyToShowStore (iterate, remove expired)
    └─► RtbPayloadStore (iterate, remove expired)

CallbackManager
    └─► (Single AtomicBoolean for exactly-once semantics)
```

### Integration Points with Existing System

```
NEW CODE                          EXISTING CODE
─────────────────────────────────────────────────────────────
AdCacheDenisImpl      ───────►   AdCache interface
                      implements

AdCacheDenisImpl      ───────►   AuctionResolver
                      delegates   (sortWinners)

AuctionCoordinator    ───────►   GetTokensUseCase
                      calls       (token collection)

AuctionCoordinator    ───────►   GetAuctionRequestUseCase
                      calls       (auction server request)

RtbProcessor          ───────►   AdSource interface
CpmProcessor          ───────►   (adapter.load(), existing)

AdCacheFactoryImpl    ───────►   AdCacheVersion enum
                      reads       (select old vs v2)

AuctionCoordinator    ───────►   ResultsCollector
                      uses        (stats collection, existing)
```

## Suggested Build Order

### Phase 1: Foundation (Cache Stores) - Independent

**Components:**
- `ReadyToShowEntry.kt` (data class)
- `RtbPayloadEntry.kt` (data class)
- `EvictionPolicy.kt` (TTL checker)
- `ReadyToShowStore.kt` (singleton)
- `RtbPayloadStore.kt` (singleton)
- `CacheManager.kt` (facade)

**Why first:**
- No dependencies on other v2 components
- Can be tested in isolation with mock data
- Provides foundation for all other components
- Thread safety is critical, better to validate early

**Integration points:** None (fully self-contained)

**Validation:** Unit tests for concurrent access, TTL expiration, duplicate policy

### Phase 2: Waterfall Split & Processors - Parallel with Phase 1

**Components:**
- `WaterfallSplitter.kt` (pure function, no state)
- `RtbProcessor.kt` (depends on stores from Phase 1)
- `CpmProcessor.kt` (depends on stores from Phase 1)
- `ParallelProcessor.kt` (orchestrates processors)

**Why second:**
- Requires stores from Phase 1 to put results
- Processing logic can be built against interface mocks initially
- Waterfall splitting is pure logic, no dependencies

**Integration points:**
- Stores (Phase 1)
- AdSource interface (existing SDK)

**Validation:** Mock adapters returning success/failure, verify parallel execution timing

### Phase 3: Coordination Layer - Depends on Phase 1 & 2

**Components:**
- `AuctionType.kt` (enum)
- `CallbackManager.kt` (exactly-once semantics)
- `AuctionCoordinator.kt` (main flow control)

**Why third:**
- Orchestrates stores (Phase 1) and processors (Phase 2)
- Integrates with existing use cases (tokens, auction request)
- Complex state machine, benefits from having stable foundation

**Integration points:**
- All Phase 1 & 2 components
- GetTokensUseCase (existing)
- GetAuctionRequestUseCase (existing)
- AuctionResolver (existing)

**Validation:** End-to-end cold start and warm start flows

### Phase 4: Lifecycle Management - Depends on Phase 1

**Components:**
- `PeriodicSweeper.kt` (background TTL sweeper)
- `CancellationHandler.kt` (CPM cancellation on show)

**Why fourth:**
- Operates on stores from Phase 1
- Non-critical path (can be added after core flows work)
- Time-based logic easier to test once core is stable

**Integration points:**
- Stores (Phase 1)
- Ad instance lifecycle hooks

**Validation:** Time-accelerated tests for TTL sweep, cancellation signal tests

### Phase 5: Entry Point & Factory - Depends on All Phases

**Components:**
- `AdCacheDenisImpl.kt` (implements AdCache interface)
- Update `AdCacheFactoryImpl.kt` (add v2 version check)

**Why last:**
- Implements existing interface, requires all components ready
- Factory integration needs full v2 implementation working
- Final integration point with existing SDK

**Integration points:**
- All v2 components (Phases 1-4)
- AdCache interface (existing)
- AdCacheFactory (existing)
- AdCacheVersion enum (existing)

**Validation:** Full integration test via SDK public API (loadAd/showAd)

### Dependency Graph

```
Phase 1: Foundation
    ┌────────────────┐    ┌────────────────┐
    │ ReadyToShow    │    │ RtbPayload     │
    │ Store          │    │ Store          │
    └────────┬───────┘    └────────┬───────┘
             │                     │
             └──────────┬──────────┘
                        ▼
                ┌───────────────┐
                │ CacheManager  │
                │ (Facade)      │
                └───────────────┘

Phase 2: Processors (depends on Phase 1)
    ┌─────────────────────────────────────┐
    │       WaterfallSplitter             │
    │       (no dependencies)             │
    └─────────────────────────────────────┘
                        │
                        ▼
    ┌──────────────┐    ┌──────────────┐
    │ RtbProcessor │    │ CpmProcessor │
    │ (uses stores)│    │ (uses stores)│
    └──────┬───────┘    └──────┬───────┘
           │                   │
           └────────┬──────────┘
                    ▼
         ┌─────────────────────┐
         │ ParallelProcessor   │
         └─────────────────────┘

Phase 3: Coordination (depends on Phase 1 & 2)
    ┌─────────────────┐
    │ CallbackManager │
    └────────┬────────┘
             │
             ▼
    ┌──────────────────────┐
    │ AuctionCoordinator   │
    │ (uses all above)     │
    └──────────────────────┘

Phase 4: Lifecycle (depends on Phase 1)
    ┌────────────────┐    ┌─────────────────┐
    │ PeriodicSweeper│    │ Cancellation    │
    │ (uses stores)  │    │ Handler         │
    └────────────────┘    └─────────────────┘

Phase 5: Entry Point (depends on all)
    ┌──────────────────────┐
    │ AdCacheDenisImpl     │
    │ (orchestrates all)   │
    └──────────────────────┘
             │
             ▼
    ┌──────────────────────┐
    │ AdCacheFactoryImpl   │
    │ (version selection)  │
    └──────────────────────┘
```

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| 1-10 concurrent ad instances | Default implementation works fine. Singleton stores shared across instances. |
| 10-50 concurrent ad instances | Monitor ConcurrentHashMap contention. Consider ReadWriteLock for stores if profiling shows bottleneck. |
| 50+ concurrent ad instances | Unlikely in mobile context (single app process). If needed, partition stores by ad type (banner/interstitial/rewarded). |

### Scaling Priorities

1. **First bottleneck: ConcurrentHashMap contention**
   - Symptom: Slow getBest() or put() operations under load
   - Fix: Profile with Android Profiler, consider striping (multiple maps with hash-based routing)
   - Alternative: Use atomic field updaters instead of compute() lambdas

2. **Second bottleneck: Adapter.load() parallelism**
   - Symptom: CPM processing not utilizing all cores
   - Fix: Implement chunked parallel loading for CPM group (e.g., 2 at a time instead of sequential)
   - Note: Spec v2.0 defers this optimization to later iteration

3. **Memory pressure from large cache sizes**
   - Symptom: OOM or high GC pressure on lower-end devices
   - Fix: Add max cache size limits (LRU eviction beyond threshold)
   - Fix: Reduce TTL from 30min to 15min for memory-constrained environments

## Anti-Patterns

### Anti-Pattern 1: Calling destroy() Without Cache Removal

**What people do:** Call `adSource.destroy()` without removing entry from READY_TO_SHOW store

**Why it's wrong:** Next access to cache returns destroyed ad, causing crash or undefined behavior when attempting to show

**Do this instead:**
```kotlin
// WRONG
entry.adSource.destroy()
// Cache still contains reference to destroyed ad

// RIGHT
entry.adSource.destroy()
ReadyToShowStore.remove(entry.adUnitUid)
```

### Anti-Pattern 2: Sharing RtbPayloadEntry Across Threads Without Synchronization

**What people do:** Pass RtbPayloadEntry between coroutines without considering thread-safety of payload field

**Why it's wrong:** JsonObject payload may not be thread-safe depending on serialization library; concurrent reads during adapter.load() can cause data corruption

**Do this instead:**
```kotlin
// WRONG
val payload = rtbPayloadStore.get(uid)?.payload
adapter.load(payload) // Concurrent access risk

// RIGHT
val payloadCopy = rtbPayloadStore.get(uid)?.payload?.deepCopy()
adapter.load(payloadCopy) // Safe, independent copy
```

### Anti-Pattern 3: Triggering onAdLoaded Multiple Times

**What people do:** Let both RTB and CPM processors call callback independently without coordination

**Why it's wrong:** Publisher expects exactly one onAdLoaded callback per loadAd() call; multiple callbacks break SDK contract and confuse publisher code

**Do this instead:**
```kotlin
// WRONG
class RtbProcessor {
    fun process() {
        val result = loadAd()
        if (result.isSuccess) onAdLoaded(result) // Both processors call this
    }
}

// RIGHT
class CallbackManager {
    private val callbackFired = AtomicBoolean(false)

    fun triggerOnAdLoadedOnce(result: AuctionResult) {
        if (callbackFired.compareAndSet(false, true)) {
            onAdLoaded(result) // Exactly once, even with concurrent calls
        }
    }
}
```

### Anti-Pattern 4: Ignoring TTL on Access

**What people do:** Return cached entries without checking expiration timestamp

**Why it's wrong:** Stale ads may fail to show (expired creatives, invalid tokens) leading to poor user experience and reduced revenue

**Do this instead:**
```kotlin
// WRONG
fun getBest(): ReadyToShowEntry? = cache.values.maxByOrNull { it.ecpm }

// RIGHT
fun getBest(): ReadyToShowEntry? =
    cache.values
        .filter { !it.isExpired() } // Lazy eviction on access
        .maxByOrNull { it.ecpm }
```

### Anti-Pattern 5: Blocking Main Thread with Synchronous Cache Operations

**What people do:** Call cache operations with blocking I/O or long computations on Android main thread

**Why it's wrong:** Causes ANR (Application Not Responding) errors, poor user experience

**Do this instead:**
```kotlin
// WRONG
fun showAd() {
    val best = blockingOperation() // ANR risk
    best.show()
}

// RIGHT
suspend fun showAd() {
    val best = withContext(Dispatchers.Default) {
        getCachedAd() // Off main thread
    }
    withContext(Dispatchers.Main) {
        best.show() // Back to main for UI
    }
}
```

## Integration Points

### Internal Boundaries (within v2 system)

| Boundary | Communication | Notes |
|----------|---------------|-------|
| AdCacheDenisImpl ↔ AuctionCoordinator | Direct method calls | Entry point delegates to coordinator |
| AuctionCoordinator ↔ ParallelProcessor | suspend function invocation | Pass adUnit groups, receive completion signal |
| ParallelProcessor ↔ RtbProcessor/CpmProcessor | async coroutine launch | Fire-and-forget pattern with callback coordination |
| Processors ↔ Stores | Direct method calls (thread-safe) | ConcurrentHashMap ensures safety |
| AuctionCoordinator ↔ CallbackManager | Direct method calls (atomic) | AtomicBoolean ensures exactly-once |

### External Boundaries (integration with existing SDK)

| Boundary | Communication | Notes |
|----------|---------------|-------|
| AdCacheDenisImpl ↔ AdCache interface | Interface implementation | Implements cache(), peek(), pop(), poll(), clear() |
| AdCacheFactoryImpl ↔ AdCacheVersion | Enum-based factory pattern | Switch between old/v2 implementations |
| AuctionCoordinator ↔ GetTokensUseCase | suspend operator fun invoke() | Reuse existing token collection logic |
| AuctionCoordinator ↔ GetAuctionRequestUseCase | suspend operator fun invoke() | Reuse existing auction request logic |
| Processors ↔ AdSource | Interface method calls | adapter.load(adUnit), adapter.getStats(), adapter.destroy() |
| AuctionCoordinator ↔ AuctionResolver | suspend function call | sortWinners(list) for price-based sorting |

## Sources

This architecture research synthesizes findings from:

**Industry Patterns:**
- [Cache hierarchy - Wikipedia](https://en.wikipedia.org/wiki/Cache_hierarchy) - Multi-level cache architecture fundamentals
- [Architecture Patterns: Caching (Part-1) | Kislay Verma](https://kislayverma.com/software-architecture/architecture-patterns-caching-part-1/) - Caching strategies and patterns
- [Design Distributed Cache | System Design - GeeksforGeeks](https://www.geeksforgeeks.org/system-design/design-distributed-cache-system-design/) - Distributed cache system design
- [Cache-Aside Pattern - Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside) - Cache-aside pattern (read-through)

**Ad Mediation Specific:**
- [Waterfall vs In-App Bidding | Kayzen](https://help.kayzen.io/en/articles/6886186-waterfall-vs-in-app-bidding) - Waterfall vs parallel bidding comparison
- [Guide to AdMob Mediation - Google AdMob](https://support.google.com/admob/answer/13420272?hl=en) - Industry standard mediation patterns
- [Top App Mediation Partners in 2025 - MonetizeMore](https://www.monetizemore.com/blog/in-app-header-bidding-or-mediation/) - Modern mediation architectures
- [Mobile ad mediation's new era: SDK bidding | Mobile Dev Memo](https://mobiledevmemo.com/mobile-ad-mediation-new-era/) - SDK bidding architecture evolution

**RTB and Caching:**
- [Prebid Server Mobile SDK Architecture](https://docs.prebid.org/prebid-server/use-cases/pbs-sdk.html) - Mobile RTB SDK patterns
- [Bid Caching: Busting 7 Myths | PubMatic](https://pubmatic.com/blog/busting-bid-caching-myths/) - RTB payload caching strategies
- [Prebid Server Cache Storage](https://docs.prebid.org/prebid-server/features/pbs-pbc-storage.html) - Cache storage for header bidding

**Project Context:**
- Bidon SDK existing codebase at `/Users/glavatskikh/StudioProjects/bidon-sdk-android`
- Architecture documentation: `.planning/codebase/ARCHITECTURE.md`
- Specification: `docs/AD_CACHING_SPEC.md` (v2.0-final)
- Current auction flow: `docs/AUCTION_ARCHITECTURE.md`

---

*Architecture research completed: 2026-02-05*
*For roadmap creation and phase structure planning*
