# Phase 5: Entry Point & Integration - Research

**Researched:** 2026-02-05
**Domain:** SDK Integration / Factory Pattern / Interface Implementation
**Confidence:** HIGH

## Summary

Phase 5 integrates the v2 cache implementation (Phases 1-4) into the SDK by implementing the AdCache interface in AdCacheDenisImpl and connecting all components through the existing factory pattern. The implementation must wire together:

1. **ReadyToShowCache** and **RtbPayloadCache** (Phase 1) - singleton caches for ads and payloads
2. **RtbProcessor** and **CpmProcessor** (Phase 2) - parallel ad loading
3. **CoordinationLayer** (Phase 3) - auction orchestration and warm/cold start logic
4. **LifecycleManager** (Phase 4) - sweep jobs and cancellation coordination

The existing AdCacheFactoryImpl already routes to AdCacheDenisImpl based on `cache_size` extra from DemandAd. The factory uses version-based selection, currently mapping V2 to Denis implementation.

**Primary recommendation:** Implement AdCacheDenisImpl by creating and wiring CoordinationLayer with all Phase 1-4 dependencies via DI, delegating cache()/peek()/pop()/poll()/clear() to CoordinationLayer methods.

## Standard Stack

### Core (Already in Codebase)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin Coroutines | 1.7+ | Async processing, cancellation | Project standard, used throughout |
| ConcurrentHashMap | JDK | Thread-safe cache stores | Phase 1 decision, lock-free |
| AtomicBoolean | JDK | Exactly-once callback semantics | Phase 2 decision |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| bidon-sdk DI | Internal | Dependency injection | Resolve GetTokensUseCase, GetAuctionRequestUseCase, AdaptersSource |
| SdkDispatchers | Internal | Coroutine dispatchers | Main dispatcher for callbacks |

### No New Dependencies Required

Phase 5 exclusively wires existing components. No external libraries needed.

## Architecture Patterns

### Recommended Project Structure

```
bidon/src/main/java/org/bidon/sdk/ads/cache/
├── AdCache.kt                    # Interface (unchanged)
├── AdCacheFactory.kt             # Factory interface (unchanged)
├── AdCacheVersion.kt             # Version enum (unchanged)
├── Cacheable.kt                  # Settings interface (unchanged)
├── impl/
│   ├── AdCacheFactoryImpl.kt     # Factory implementation (minor changes)
│   └── AdCacheDenisImpl.kt       # V2 implementation (Phase 5 focus)
└── denis/                        # V2 components (Phases 1-4)
    ├── stores/                   # ReadyToShowCache, RtbPayloadCache
    ├── processors/               # RtbProcessor, CpmProcessor
    ├── orchestration/            # CoordinationLayer, ParallelAuctionOrchestrator
    └── lifecycle/                # LifecycleManager, sweep, cancellation
```

### Pattern 1: Facade Pattern for AdCacheDenisImpl

**What:** AdCacheDenisImpl acts as a facade, delegating all operations to CoordinationLayer
**When to use:** When integrating multiple subsystems through a single interface
**Example:**

```kotlin
// Source: Existing AdCache interface contract
internal class AdCacheDenisImpl(
    override val demandAd: DemandAd,
    private val coordinationLayer: CoordinationLayer, // Injected facade
    private val lifecycleManager: LifecycleManager,   // For cleanup
) : AdCache {

    override fun cache(
        adTypeParam: AdTypeParam,
        onSuccess: (AuctionResult, AuctionInfo) -> Unit,
        onFailure: (AuctionInfo?, Throwable) -> Unit,
    ) {
        // Delegate to CoordinationLayer.coordinateAuction()
        // Launch on lifecycleManager.getScope()
    }

    override fun peek(): AuctionResult? {
        // Delegate to ReadyToShowCache.peekBest()
    }

    override fun pop(): AuctionResult? {
        // Delegate to ReadyToShowCache.popBest()?.value
        // Cancel ongoing auction if warm start
    }
}
```

### Pattern 2: Version-Based Factory Selection

**What:** Factory creates different implementations based on version flag/config
**When to use:** A/B testing implementations, gradual rollout
**Example:**

```kotlin
// Source: Existing AdCacheFactoryImpl pattern
internal class AdCacheFactoryImpl(
    private val resolver: AuctionResolver,
    // Add dependencies for v2
) : AdCacheFactory {

    override fun create(demandAd: DemandAd): AdCache {
        val version = AdCacheVersion.fromInt(demandAd.getExtras()["cache_size"] as? Int)
        return when (version) {
            AdCacheVersion.V1 -> AdCacheImpl(...)
            AdCacheVersion.V2 -> AdCacheDenisImpl(...)
            // ... other versions
        }
    }
}
```

### Pattern 3: CoordinationLayer as Orchestration Entry Point

**What:** CoordinationLayer determines warm/cold start and orchestrates auction
**When to use:** Complex decision logic with multiple subsystems
**Example:**

```kotlin
// Source: Existing CoordinationLayer.coordinateAuction()
suspend fun coordinateAuction(...): AuctionCompletionType {
    lifecycleManager.start()
    val (startState, snapshot) = determineStartState(userPricefloor)

    return when (startState) {
        is AuctionStartState.WarmStart -> {
            handleWarmStart(startState.bestAd, onSuccess)
            AuctionCompletionType.WarmStartServed
        }
        is AuctionStartState.ColdStartWithCache,
        is AuctionStartState.PureColdStart -> {
            val auctionId = UUID.randomUUID().toString()
            val job = lifecycleManager.getScope().launch { handleColdStart(...) }
            lifecycleManager.registerAuction(auctionId, job)
            AuctionCompletionType.ColdStartInProgress
        }
    }
}
```

### Anti-Patterns to Avoid

- **Direct Cache Access:** Don't bypass CoordinationLayer to access caches directly from AdCacheDenisImpl. CoordinationLayer encapsulates the warm/cold start logic.
- **GlobalScope Usage:** Always use lifecycleManager.getScope() for coroutines. GlobalScope prevents cancellation.
- **Blocking Main Thread:** cache()/peek()/pop() are called from main thread. pop() should be non-blocking (ReadyToShowCache operations are fast).
- **Clearing Application-Wide Caches:** clear()/destroyAd() must NOT clear ReadyToShowCache or RtbPayloadCache (LIFE-03 requirement). Only stop lifecycle jobs.

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Highest eCPM selection | Manual iteration | ReadyToShowCache.popBest() | Already implements lazy expiration + eCPM comparison |
| Thread-safe callback firing | Manual synchronization | CallbackCoordinator | AtomicBoolean exactly-once semantics |
| Auction cancellation | Custom Job tracking | LifecycleManager.cancelAuction() | Already wired to CancellationManager |
| Warm/cold start decision | Manual cache checks | CoordinationLayer.determineStartState() | Encapsulates all cache state logic |
| Pricefloor calculation | Manual max() | CoordinationLayer.calculatePricefloor() | Uses 0.9 safety margin formula |

**Key insight:** All the complex logic exists in Phase 1-4 components. Phase 5 is primarily wiring, not logic implementation.

## Common Pitfalls

### Pitfall 1: Not Wiring LifecycleManager Correctly

**What goes wrong:** Sweep jobs don't run, cancellation doesn't work, zombie coroutines
**Why it happens:** LifecycleManager is instance-scoped (one per ad instance), not singleton
**How to avoid:** Create LifecycleManager in AdCacheFactoryImpl when constructing AdCacheDenisImpl
**Warning signs:** No "Starting periodic sweep" logs, memory leaks in profiler

### Pitfall 2: Blocking on warm start callback

**What goes wrong:** ANR when cache() immediately fires onSuccess
**Why it happens:** Warm start fires callback synchronously from cache() call
**How to avoid:** CoordinationLayer.handleWarmStart() fires callback directly - this is intentional. Caller (InterstitialImpl) handles via scope.launch
**Warning signs:** Logcat warnings about blocking main thread

### Pitfall 3: Wrong Thread for Callbacks

**What goes wrong:** onSuccess/onFailure callbacks fire on wrong thread
**Why it happens:** coordinateAuction() runs on lifecycle scope, callbacks need main thread
**How to avoid:** Wrap callback invocation in withContext(Dispatchers.Main) or use scope with main dispatcher
**Warning signs:** "CalledFromWrongThreadException", UI updates not visible

### Pitfall 4: Clearing Caches on destroyAd()

**What goes wrong:** Ad instance destruction clears application-wide caches
**Why it happens:** Confusion between instance-scoped cleanup and global cache
**How to avoid:** clear() should be NO-OP per CONTEXT.md decision. destroyAd() calls lifecycleManager.stop() only
**Warning signs:** Other ad instances suddenly have no cached ads

### Pitfall 5: Missing Auction Cancellation on showAd()

**What goes wrong:** Cold start auction continues running after warm start served ad
**Why it happens:** pop() doesn't cancel ongoing auction
**How to avoid:** After popBest(), call lifecycleManager.cancelAuction() with the shown ad's auctionId
**Warning signs:** Wasted network requests, "Auction completed" logs after show

## Code Examples

Verified patterns from existing codebase:

### AdCacheDenisImpl Constructor with Dependencies

```kotlin
// Pattern: Constructor injection matching existing factory
internal class AdCacheDenisImpl(
    override val demandAd: DemandAd,
    private val coordinationLayer: CoordinationLayer,
    private val lifecycleManager: LifecycleManager,
    private val scope: CoroutineScope = CoroutineScope(SdkDispatchers.Main),
) : AdCache
```

### cache() Implementation Pattern

```kotlin
// Pattern from existing AdCacheImpl
override fun cache(
    adTypeParam: AdTypeParam,
    onSuccess: (AuctionResult, AuctionInfo) -> Unit,
    onFailure: (AuctionInfo?, Throwable) -> Unit,
) {
    scope.launch {
        try {
            val completionType = coordinationLayer.coordinateAuction(
                adTypeParam = adTypeParam,
                demandAd = demandAd,
                tokenTimeout = BiddingConfig.getTokenTimeout(),
                onSuccess = { result, info ->
                    scope.launch(Dispatchers.Main) { onSuccess(result, info) }
                },
                onFailure = { info, error ->
                    scope.launch(Dispatchers.Main) { onFailure(info, error) }
                }
            )
            // WarmStartServed means callback already fired
            // ColdStartInProgress means callbacks will fire later
        } catch (e: Exception) {
            onFailure(null, e)
        }
    }
}
```

### pop() with Auction Cancellation

```kotlin
// Pattern: highest eCPM selection + removal + cancellation
override fun pop(): AuctionResult? {
    val entry = ReadyToShowCache.popBest() ?: return null

    // Cancel ongoing auction when serving cached ad (warm start logic)
    lifecycleManager.cancelAuction(entry.auctionId)

    logInfo(TAG, "pop: served cached ad demandId=${entry.demandId}, ecpm=${entry.ecpm}")
    return entry.value
}
```

### peek() Without Removal

```kotlin
// Pattern: non-destructive read
override fun peek(): AuctionResult? {
    return ReadyToShowCache.peekBest()
}
```

### poll() as Non-Blocking Operation

```kotlin
// Decision: poll() same as peek() (non-blocking)
// Note: Old impl used suspend poll() for waiting - v2 doesn't wait
override suspend fun poll(): AuctionResult {
    return peek() ?: throw NoSuchElementException("Cache is empty")
}
```

### clear() as NO-OP

```kotlin
// Decision from CONTEXT.md: clear() is NO-OP
override fun clear() {
    logInfo(TAG, "clear() called - NO-OP per design (caches clear via expiration only)")
}
```

### withSettings() Implementation

```kotlin
// Pattern: delegate to cache capacity configuration
override fun withSettings(settings: Cacheable.Settings) {
    ReadyToShowCache.setCapacity(settings.cacheCapacity)
    logInfo(TAG, "Cache settings applied: capacity=${settings.cacheCapacity}")
}
```

### Factory Dependency Injection

```kotlin
// Pattern: Create CoordinationLayer with all dependencies
AdCacheVersion.V2 -> {
    val adaptersSource: AdaptersSource = get()
    val getTokens: GetTokensUseCase = get()
    val getAuctionRequest: GetAuctionRequestUseCase = get()

    val lifecycleManager = LifecycleManager()
    val callbackCoordinator = CallbackCoordinator(
        onAdLoaded = { result, info -> /* forwarded from cache() */ },
        onAdLoadFailed = { info, error -> /* forwarded from cache() */ }
    )
    val rtbProcessor = RtbProcessor(adaptersSource)
    val cpmProcessor = CpmProcessor(adaptersSource)
    val orchestrator = ParallelAuctionOrchestrator(rtbProcessor, cpmProcessor, callbackCoordinator)

    val coordinationLayer = CoordinationLayer(
        adaptersSource = adaptersSource,
        getTokens = getTokens,
        getAuctionRequest = getAuctionRequest,
        orchestrator = orchestrator,
        lifecycleManager = lifecycleManager,
    )

    AdCacheDenisImpl(
        demandAd = demandAd,
        coordinationLayer = coordinationLayer,
        lifecycleManager = lifecycleManager,
    )
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Single cache entry | Multi-entry ReadyToShowCache | Phase 1 | Enables warm start optimization |
| Sequential auction | Parallel RTB+CPM | Phase 2 | Faster cold start |
| Simple pop() | eCPM-based popBest() | Phase 1 | Returns highest value ad |
| Manual cancellation | LifecycleManager | Phase 4 | Proper resource cleanup |

**Deprecated/outdated:**
- N/A - This is new implementation

## Open Questions

Things that couldn't be fully resolved:

1. **CallbackCoordinator Wiring**
   - What we know: CallbackCoordinator needs onAdLoaded/onAdLoadFailed callbacks at construction
   - What's unclear: How to wire these to the callbacks passed to cache() since they arrive later
   - Recommendation: Create CallbackCoordinator per cache() call OR use mutable callback holders. The latter matches existing pattern where coordinationLayer receives callbacks.

2. **tokenTimeout Source**
   - What we know: CoordinationLayer.coordinateAuction() requires tokenTimeout parameter
   - What's unclear: Where to get BiddingConfig.getTokenTimeout() value
   - Recommendation: Inject BiddingConfig or use existing singleton access via `get<BiddingConfig>()`

3. **Error Type Conversion**
   - What we know: onFailure expects Throwable, CoordinationLayer uses BidonError
   - What's unclear: Whether to wrap BidonError in Throwable
   - Recommendation: BidonError extends Throwable, so direct pass-through works

## Sources

### Primary (HIGH confidence)

- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/AdCache.kt` - Interface definition
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheFactoryImpl.kt` - Factory pattern reference
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheImpl.kt` - V1 implementation pattern
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt` - Orchestration logic
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/LifecycleManager.kt` - Lifecycle facade
- `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/ReadyToShowCache.kt` - Cache store with popBest()

### Secondary (MEDIUM confidence)

- `.planning/phases/05-entry-point-integration/05-CONTEXT.md` - User decisions
- `.planning/REQUIREMENTS.md` - Phase 5 requirements mapping

### Tertiary (LOW confidence)

- None - all findings from direct codebase analysis

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - verified from existing codebase patterns
- Architecture: HIGH - existing components well-documented
- Pitfalls: MEDIUM - inferred from code analysis, not runtime testing
- Wiring: HIGH - all dependencies clearly defined in existing code

**Research date:** 2026-02-05
**Valid until:** 2026-03-05 (30 days - stable internal codebase)
