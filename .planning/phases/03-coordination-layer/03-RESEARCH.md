# Phase 3: Coordination Layer - Research

**Researched:** 2026-02-05
**Domain:** Kotlin coroutine orchestration, auction state management, RTB optimization
**Confidence:** HIGH

## Summary

Phase 3 implements the coordination layer that orchestrates auction flow by detecting cache state (cold vs warm start), calculating dynamic pricefloors from cached eCPM values, skipping token collection for cached RTB payloads, and splitting the waterfall into RTB and CPM groups before parallel processing.

The coordination layer acts as the "brain" between user-facing API (`AdCache.cache()`) and parallel processors (Phase 2). It inspects ReadyToShowCache and RtbPayloadCache to determine auction strategy, modifies GetTokensUseCase behavior to skip cached adapters, adjusts pricefloor based on cache state, and delegates to ParallelAuctionOrchestrator.

**Key challenges:**
- Warm start requires immediate callback (<1s) without auction delays
- Token collection must skip adapters with valid RTB_PAYLOAD cache entries
- Dynamic pricefloor must balance cache value protection (90% safety margin) with auction competitiveness
- Waterfall splitting requires adapter interface inspection (`Adapter.Bidding` vs `Adapter.Network`)
- Cache state may change during processing (race condition handling)

**Primary recommendation:** Use sealed class state machine pattern for cold/warm start decision, extend GetTokensUseCase with skipDemandIds parameter, calculate pricefloor once at auction start, and leverage existing ExecuteAuctionUseCaseImpl waterfall splitting logic.

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin Coroutines | 1.7+ | Async orchestration, structured concurrency | Android standard for async operations, enables supervisorScope isolation |
| kotlinx.atomicfu | Latest | AtomicBoolean for exactly-once semantics | Lock-free synchronization, used in Phase 2 CallbackCoordinator |
| ConcurrentHashMap | JDK | Thread-safe cache operations | Native Java concurrent collection, used in ReadyToShowCache and RtbPayloadCache |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| SystemClock.elapsedRealtime | Android API | Monotonic time for TTL | Avoid clock drift in TTL calculations (SAFETY-01) |
| supervisorScope | Coroutines | Failure isolation | Prevent RTB failures from canceling CPM branch |
| ensureActive() | Coroutines | Cancellation checks | Cooperative cancellation in loops |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Sealed class state machine | Enum + when | Sealed classes provide exhaustive checking and can carry state data |
| supervisorScope | coroutineScope | supervisorScope isolates failures, required for PARALLEL-02 |
| Extension to GetTokensUseCase | New interface | Reuse reduces code duplication, maintains compatibility (INT-03) |

**Installation:**
No external dependencies - uses existing SDK infrastructure and Kotlin stdlib.

## Architecture Patterns

### Recommended Project Structure
```
bidon/src/main/java/org/bidon/sdk/ads/cache/denis/
├── orchestration/
│   ├── CoordinationLayer.kt         # Phase 3: Entry point, cold/warm decision
│   ├── ParallelAuctionOrchestrator.kt  # Phase 2: RTB + CPM execution
│   └── CallbackCoordinator.kt       # Phase 2: Callback management
├── processors/
│   ├── RtbProcessor.kt              # Phase 2: RTB payload loading
│   ├── CpmProcessor.kt              # Phase 2: CPM waterfall loading
│   └── WeightModel.kt               # Phase 2: CPM ordering
└── stores/
    ├── ReadyToShowCache.kt          # Phase 1: Loaded ads
    ├── RtbPayloadCache.kt           # Phase 1: RTB bid responses
    └── CacheEntry.kt                # Phase 1: Cache data structure
```

### Pattern 1: Cold/Warm Start State Machine

**What:** Sealed class hierarchy representing auction start conditions
**When to use:** Decision point in CoordinationLayer before token collection
**Example:**
```kotlin
// Source: User decision in CONTEXT.md + Kotlin sealed class best practices
internal sealed class AuctionStartState {
    /**
     * Warm start: READY_TO_SHOW cache has ads, serve immediately
     */
    data class WarmStart(
        val bestAd: CacheEntry<AuctionResult>
    ) : AuctionStartState()

    /**
     * Cold start with RTB optimization: RTB_PAYLOAD cache has entries, skip their tokens
     */
    data class ColdStartWithCache(
        val cachedDemandIds: Set<String>,
        val maxCachedEcpm: Double
    ) : AuctionStartState()

    /**
     * Pure cold start: Both caches empty, full token collection
     */
    data class PureColdStart(
        val userPricefloor: Double
    ) : AuctionStartState()
}

// Usage in coordination layer
suspend fun coordinateAuction(...): AuctionStartState {
    return when {
        !ReadyToShowCache.isEmpty() -> {
            // Warm start path
            val bestAd = ReadyToShowCache.getBest()
            AuctionStartState.WarmStart(bestAd)
        }
        !RtbPayloadCache.isEmpty() -> {
            // Cold start with cached payloads
            AuctionStartState.ColdStartWithCache(
                cachedDemandIds = RtbPayloadCache.getCachedDemandIds(),
                maxCachedEcpm = RtbPayloadCache.getMaxEcpm()
            )
        }
        else -> {
            // Pure cold start
            AuctionStartState.PureColdStart(userPricefloor = pricefloor)
        }
    }
}
```

**Rationale:** Sealed classes enable exhaustive when expressions with compile-time safety. Each state carries necessary data for subsequent processing. Pattern matches user decision to "Always serve immediately if READY_TO_SHOW cache is not empty."

### Pattern 2: GetTokensUseCase Extension with Skip Set

**What:** Extend existing GetTokensUseCase interface to support skipping cached adapters
**When to use:** Token collection phase when RtbPayloadCache has entries
**Example:**
```kotlin
// Source: GetTokensUseCaseImpl.kt pattern + INT-03 requirement
internal interface GetTokensUseCase {
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String> = emptySet() // New parameter, backward compatible
    ): Map<String, TokenInfo>
}

// Implementation modification
internal class GetTokensUseCaseImpl : GetTokensUseCase {
    override suspend fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String>
    ): Map<String, TokenInfo> = withContext(SdkDispatchers.Default) {
        // Filter bidding adapters AND skip cached ones
        val biddingAdapters = adaptersSource.adapters
            .filterIsInstance<Adapter.Bidding>()
            .filter { it.demandId.demandId !in skipDemandIds } // Skip cached
            .onEach(Adapter::applyRegulation)

        logInfo(TAG, "Token collection: ${biddingAdapters.size} adapters, ${skipDemandIds.size} skipped")

        supervisorScope {
            biddingAdapters.map { adapter ->
                async { adapter.demandId.demandId to getTokenInfo(adapter, adTypeParam, tokenTimeout) }
            }.awaitAll().toMap()
        }
    }
}
```

**Rationale:** Matches existing `.filterIsInstance<Adapter.Bidding>()` pattern from GetTokensUseCaseImpl. Default parameter maintains backward compatibility. Skip set prevents redundant token collection for cached RTB payloads.

### Pattern 3: Dynamic Pricefloor Calculation

**What:** Calculate pricefloor once at auction start based on cache state with safety margin
**When to use:** Before token collection and auction request
**Example:**
```kotlin
// Source: User decision in CONTEXT.md + cache getMaxEcpm() APIs
internal fun calculateDynamicPricefloor(
    userPricefloor: Double,
    readyToShowMaxEcpm: Double = ReadyToShowCache.getMaxEcpm(),
    rtbPayloadMaxEcpm: Double = RtbPayloadCache.getMaxEcpm()
): Double {
    val maxCachedEcpm = maxOf(readyToShowMaxEcpm, rtbPayloadMaxEcpm)

    // Apply 90% safety margin to allow slightly better bids
    val cachedFloorWithMargin = maxCachedEcpm * 0.9

    // Take max of user pricefloor and cached pricefloor
    val dynamicPricefloor = maxOf(userPricefloor, cachedFloorWithMargin)

    logInfo(TAG, "Dynamic pricefloor: user=$userPricefloor, cached=$maxCachedEcpm, " +
        "calculated=$dynamicPricefloor")

    return dynamicPricefloor
}
```

**Rationale:** Safety margin (0.9 = 90%) allows auctions to find slightly better bids while protecting cached value. Calculated once at auction start to avoid cache state changes during processing. User pricefloor is respected even on cold start.

### Pattern 4: Waterfall Splitting by Adapter Interface

**What:** Split AdUnit list into RTB and CPM groups based on adapter type inspection
**When to use:** After auction response, before parallel processing
**Example:**
```kotlin
// Source: ExecuteAuctionUseCaseImpl.kt + Adapter.kt interface hierarchy
internal data class SplitWaterfall(
    val rtbAdUnits: List<AdUnit>,
    val cpmAdUnits: List<AdUnit>
)

internal suspend fun splitWaterfallByAdapterType(
    adUnits: List<AdUnit>,
    adaptersSource: AdaptersSource
): SplitWaterfall {
    // Build map of demandId to adapter type
    val biddingDemandIds = adaptersSource.adapters
        .filterIsInstance<Adapter.Bidding>()
        .map { it.demandId.demandId }
        .toSet()

    val rtbAdUnits = mutableListOf<AdUnit>()
    val cpmAdUnits = mutableListOf<AdUnit>()

    adUnits.forEach { adUnit ->
        if (adUnit.demandId in biddingDemandIds) {
            rtbAdUnits.add(adUnit)
        } else {
            cpmAdUnits.add(adUnit)
        }
    }

    logInfo(TAG, "Waterfall split: ${rtbAdUnits.size} RTB, ${cpmAdUnits.size} CPM")

    return SplitWaterfall(rtbAdUnits, cpmAdUnits)
}
```

**Rationale:** Matches GetTokensUseCaseImpl pattern of `.filterIsInstance<Adapter.Bidding>()`. Determines RTB vs CPM by checking if adapter implements `Adapter.Bidding` interface. Backend may return mixed waterfall - coordination layer splits before passing to processors.

### Pattern 5: Cache State Snapshot

**What:** Capture cache state at auction start, don't re-validate during processing
**When to use:** Beginning of coordinateAuction() function
**Example:**
```kotlin
// Source: User decision "Cache state changes during processing are acceptable"
internal data class CacheStateSnapshot(
    val readyToShowIsEmpty: Boolean,
    val readyToShowMaxEcpm: Double,
    val rtbPayloadIsEmpty: Boolean,
    val rtbPayloadMaxEcpm: Double,
    val cachedDemandIds: Set<String>,
    val timestamp: Long = TtlConfig.now()
)

internal fun captureCacheState(): CacheStateSnapshot {
    return CacheStateSnapshot(
        readyToShowIsEmpty = ReadyToShowCache.isEmpty(),
        readyToShowMaxEcpm = ReadyToShowCache.getMaxEcpm(),
        rtbPayloadIsEmpty = RtbPayloadCache.isEmpty(),
        rtbPayloadMaxEcpm = RtbPayloadCache.getMaxEcpm(),
        cachedDemandIds = RtbPayloadCache.getCachedDemandIds()
    )
}
```

**Rationale:** Single snapshot prevents race conditions and inconsistent decisions. User accepted that cache may change during processing - we use state from auction start. Matches ParallelAuctionOrchestrator pattern of recording state before async execution.

### Anti-Patterns to Avoid

- **Re-validating cache state mid-auction:** Cache may change, causing inconsistent behavior. Capture once at start.
- **Background refresh on warm start:** User decision "No background refresh on warm start" - serve cached ad only.
- **Separate pricefloor for RTB vs CPM:** User decision "Merge into existing pricefloor request parameter" - single value.
- **Token collection for all adapters then filtering results:** Wastes time and battery. Filter before collection using skipDemandIds.
- **Using GlobalScope:** Violates SAFETY-03. Inject CoroutineScope for proper cancellation support.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Token collection with filtering | Custom parallel token fetcher | Extend GetTokensUseCaseImpl with skipDemandIds | Already implements timeout, supervisorScope, logging, regulation |
| Waterfall splitting | Custom BidType.RTB matching | Adapter.Bidding interface check | Single source of truth - adapter declares its type, matches GetTokensUseCaseImpl pattern |
| Auction request with dynamic pricefloor | New auction request builder | GetAuctionRequestUseCase with calculated pricefloor | Existing implementation handles all request fields, just pass modified pricefloor |
| Parallel RTB + CPM execution | Custom async coordination | ParallelAuctionOrchestrator from Phase 2 | Already implements supervisorScope, callback coordination, failure isolation |

**Key insight:** Phase 2 processors and Phase 1 caches provide complete building blocks. Coordination layer only needs state inspection, decision making, and parameter calculation. Don't duplicate auction execution logic.

## Common Pitfalls

### Pitfall 1: Warm Start Delays Callback

**What goes wrong:** Warm start path calls async functions or validates cache state, delaying onAdLoaded callback beyond 1 second.

**Why it happens:** Developer treats warm start like cold start with shortened auction, performing unnecessary checks.

**How to avoid:**
- Check `ReadyToShowCache.isEmpty()` first thing in coordinateAuction()
- If cache not empty, immediately call `callbackCoordinator.notifySuccess()` with best ad
- Return early, skip all token collection and auction logic
- No async operations in warm start path

**Warning signs:**
- `suspend fun` in warm start path (should be synchronous cache read)
- Token collection happening when cache not empty
- Logs showing "Starting parallel auction" for warm start

### Pitfall 2: Token Collection Race with Cache Updates

**What goes wrong:** Token collection starts, then cache is updated by parallel auction, leading to inconsistent skipDemandIds.

**Why it happens:** Capturing cached demand IDs too early or too late relative to cache mutations.

**How to avoid:**
- Capture `RtbPayloadCache.getCachedDemandIds()` once at auction start
- Pass snapshot to GetTokensUseCase, don't re-query cache
- Accept that cache may change during processing (user decision)
- Trust snapshot state for entire auction lifecycle

**Warning signs:**
- Multiple calls to `getCachedDemandIds()` in single auction
- Logs showing different skip counts between token collection and processor
- Token collected for adapter that has cached payload

### Pitfall 3: Pricefloor Recalculation During Auction

**What goes wrong:** Pricefloor recalculated after token collection when cache state changes, leading to inconsistent auction request.

**Why it happens:** Caches mutate during parallel processing, tempting to use "latest" values.

**How to avoid:**
- Calculate pricefloor once at auction start using cache snapshot
- Pass calculated value to GetAuctionRequestUseCase
- Don't re-query cache maxEcpm values after calculation
- Document in code comment: "Pricefloor calculated at auction start, not updated during processing"

**Warning signs:**
- Multiple calls to `getMaxEcpm()` in single auction flow
- Pricefloor variable reassigned after initial calculation
- Auction request and token collection using different pricefloor values

### Pitfall 4: Forgetting Safety Margin on Pricefloor

**What goes wrong:** Using cached eCPM directly as pricefloor blocks all auctions with slightly lower bids, preventing discovery of better ads.

**Why it happens:** Literal interpretation of "max eCPM as pricefloor" without considering auction competitiveness.

**How to avoid:**
- Apply 0.9 multiplier (90%) to cached eCPM before comparing with user pricefloor
- Document in code: "Safety margin allows slightly better bids to compete"
- Still respect user pricefloor if higher than cached value
- Log both cached eCPM and calculated pricefloor for debugging

**Warning signs:**
- Fill rate drops after implementing cache (ads can't compete with cached eCPM)
- All auction responses have zero AdUnits (pricefloor too high)
- Cached ads never replaced by better ads from auction

### Pitfall 5: Waterfall Splitting by BidType Instead of Adapter Interface

**What goes wrong:** Splitting AdUnits by `adUnit.bidType == BidType.RTB` instead of checking if adapter implements `Adapter.Bidding`.

**Why it happens:** AdUnit has bidType field, seems like obvious choice.

**How to avoid:**
- Check adapter type: `adaptersSource.adapters.filterIsInstance<Adapter.Bidding>()`
- User decision: "Determine RTB vs CPM by checking Adapter.Bidding interface"
- Build set of bidding demand IDs, then split AdUnits based on membership
- Matches GetTokensUseCaseImpl pattern

**Warning signs:**
- Using `adUnit.bidType` for waterfall split
- Mismatches between token collection and processor assignment (CPM adapter treated as RTB)
- Adapter not found errors in RTB processor for non-bidding adapters

## Code Examples

Verified patterns from existing codebase:

### Capturing Cache State for Warm Start Detection
```kotlin
// Source: ParallelAuctionOrchestrator.kt pattern
suspend fun coordinateAuction(
    adTypeParam: AdTypeParam,
    pricefloor: Double,
    onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    onFailure: (AuctionInfo?, Throwable) -> Unit
) {
    // Capture cache state BEFORE any async operations
    val readyToShowIsEmpty = ReadyToShowCache.isEmpty()
    val rtbPayloadCachedIds = RtbPayloadCache.getCachedDemandIds()

    // Warm start: immediate callback
    if (!readyToShowIsEmpty) {
        val bestAd = ReadyToShowCache.getBest()
        if (bestAd != null) {
            logInfo(TAG, "Warm start: serving cached ad (demandId=${bestAd.demandId}, ecpm=${bestAd.ecpm})")
            onSuccess(bestAd.value, createAuctionInfo(bestAd.auctionId))
            return
        }
    }

    // Cold start continues...
    logInfo(TAG, "Cold start: rtb_cached=${rtbPayloadCachedIds.size}")
}
```

### Token Collection with Skip Set
```kotlin
// Source: GetTokensUseCaseImpl.kt + skipDemandIds extension
val tokens: Map<String, TokenInfo> = getTokensUseCase(
    adTypeParam = adTypeParam,
    adaptersSource = adaptersSource,
    tokenTimeout = tokenTimeout,
    skipDemandIds = rtbPayloadCachedIds // Skip cached RTB adapters
)

// Log skipped tokens for debugging
rtbPayloadCachedIds.forEach { demandId ->
    logInfo(TAG, "Token collection skipped for demandId=$demandId (cached payload)")
}
```

### Dynamic Pricefloor with Safety Margin
```kotlin
// Source: User decision in CONTEXT.md
val dynamicPricefloor = calculateDynamicPricefloor(
    userPricefloor = pricefloor,
    readyToShowMaxEcpm = ReadyToShowCache.getMaxEcpm(),
    rtbPayloadMaxEcpm = RtbPayloadCache.getMaxEcpm()
)

fun calculateDynamicPricefloor(
    userPricefloor: Double,
    readyToShowMaxEcpm: Double,
    rtbPayloadMaxEcpm: Double
): Double {
    val maxCached = maxOf(readyToShowMaxEcpm, rtbPayloadMaxEcpm)
    val cachedWithMargin = maxCached * 0.9 // 10% margin
    return maxOf(userPricefloor, cachedWithMargin)
}
```

### Waterfall Splitting by Adapter Type
```kotlin
// Source: ExecuteAuctionUseCaseImpl.kt adapter lookup pattern
suspend fun splitWaterfall(
    adUnits: List<AdUnit>,
    adaptersSource: AdaptersSource
): SplitWaterfall {
    // Build set of bidding adapter IDs
    val biddingDemandIds = adaptersSource.adapters
        .filterIsInstance<Adapter.Bidding>()
        .map { it.demandId.demandId }
        .toSet()

    // Partition AdUnits
    val (rtbUnits, cpmUnits) = adUnits.partition { it.demandId in biddingDemandIds }

    return SplitWaterfall(
        rtbAdUnits = rtbUnits,
        cpmAdUnits = cpmUnits
    )
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Sequential waterfall (CPM only) | Parallel RTB + CPM with cache | Phase 2-3 implementation | Faster fill, <1s warm start |
| Fixed pricefloor from API | Dynamic pricefloor from cache | Phase 3 implementation | Protects cached ad value, prevents worse bids |
| Token collection for all RTB adapters | Skip cached adapters | Phase 3 implementation | Faster auction start (250-500ms saved per adapter) |
| Single cache for loaded ads | Dual cache (READY_TO_SHOW + RTB_PAYLOAD) | Phase 1 implementation | Enables token skipping optimization |
| Enum-based state tracking | Sealed class state machine | 2026 best practice | Exhaustive checking, type-safe state data |

**Deprecated/outdated:**
- `BidType.RTB` for adapter type detection - Use `Adapter.Bidding` interface check instead
- Global pricefloor for all auction calls - Use dynamic calculation based on cache
- Separate token collection and skip logic - Extend GetTokensUseCase with skipDemandIds parameter

## Open Questions

Things that couldn't be fully resolved:

1. **Cache race condition handling on warm start**
   - What we know: User decided "Cache state changes during processing are acceptable"
   - What's unclear: Should we check `isAdReadyToShow` on cached AdSource before callback?
   - Recommendation: Trust cache state at auction start. If ad expired between cache check and callback, lifecycle management (Phase 4) handles it via AdEvent.Expired. Document assumption in code comment.

2. **Statistics event structure for skipped tokens**
   - What we know: User decided "Log skipped tokens in stats (no separate SkippedTokens event)"
   - What's unclear: Exact field names and structure for /v2/stats request
   - Recommendation: Follow existing TokenInfo structure, add `skipped: boolean` field. Implementation detail for stats phase, not blocking for coordination layer.

3. **Re-sorting within RTB and CPM groups**
   - What we know: User decided "Allow re-sorting within RTB and CPM groups"
   - What's unclear: Optimal sorting algorithm (eCPM only? weight? both?)
   - Recommendation: Use existing AdUnit order from backend for RTB (highest eCPM first). For CPM, WeightModel.sortByWeightedScore() from Phase 2. Don't implement new sorting in coordination layer.

## Sources

### Primary (HIGH confidence)
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt` - Token collection pattern with `.filterIsInstance<Adapter.Bidding>()`
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/ParallelAuctionOrchestrator.kt` - Cache state snapshot pattern, supervisorScope usage
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/ReadyToShowCache.kt` - Cache APIs (isEmpty, getMaxEcpm, getBest)
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/RtbPayloadCache.kt` - RTB cache APIs (getCachedDemandIds, getMaxEcpm)
- `.planning/phases/03-coordination-layer/03-CONTEXT.md` - User decisions on warm start, token skipping, pricefloor calculation
- `.planning/REQUIREMENTS.md` - Requirements AUCTION-01 through AUCTION-06, INT-03, INT-04

### Secondary (MEDIUM confidence)
- [Kotlin sealed classes and interfaces | Kotlin Documentation](https://kotlinlang.org/docs/sealed-classes.html) - Sealed class exhaustive when expressions
- [Coordinator Pattern in Android with Kotlin Coroutines | Medium](https://medium.com/capital-one-tech/coordinator-pattern-in-android-with-kotlin-coroutines-fcdf79a089de) - Coroutine orchestration patterns
- [Best practices for coroutines in Android | Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) - supervisorScope, cancellation handling

### Tertiary (LOW confidence)
- RTB optimization web search results - General industry context, not SDK-specific

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Using existing SDK infrastructure (Kotlin Coroutines, ConcurrentHashMap) verified in codebase
- Architecture: HIGH - Patterns directly adapted from GetTokensUseCaseImpl, ParallelAuctionOrchestrator, ExecuteAuctionUseCaseImpl
- Pitfalls: HIGH - Derived from user decisions in CONTEXT.md and common coroutine mistakes documented in Android best practices

**Research date:** 2026-02-05
**Valid until:** 2026-03-05 (30 days - stable patterns, user decisions locked)
