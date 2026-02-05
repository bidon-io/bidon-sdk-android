# Project Research Summary

**Project:** Ad Caching v2 with Kotlin Coroutines for Bidon Android SDK
**Domain:** Mobile Ad Mediation - In-Memory Cache System with Parallel Auction Processing
**Researched:** 2026-02-05
**Confidence:** HIGH

## Executive Summary

The research confirms that Ad Caching v2 for the Bidon Android SDK requires a sophisticated two-level cache system (READY_TO_SHOW + RTB_PAYLOAD) with parallel RTB/CPM processing to achieve sub-second warm start latency. This is a unique differentiator in the ad mediation space - competitors like AdMob, AppLovin MAX, and Unity Ads do not offer explicit RTB payload reuse or warm start optimization with immediate callbacks. The recommended stack is lightweight and zero-dependency: Kotlin Coroutines 1.10.2, ConcurrentHashMap for storage, MutableStateFlow for observable state, and Mutex for critical sections. No external cache libraries are needed.

The architecture follows a clear separation of concerns across five phases: Foundation (cache stores with TTL), Processors (parallel RTB/CPM loading), Coordination (auction flow control), Lifecycle (periodic sweeps), and Entry Point (AdCache interface integration). The most complex components are warm start optimization (race conditions between immediate callback and background refresh) and parallel processing (coroutine orchestration with exactly-once callback semantics). Critical pitfalls include Activity context leaks from singleton caches, race conditions in callback firing, and coroutine cancellation cleanup failures - all of which have proven solutions but require disciplined implementation.

The key technical risk is thread-safety: concurrent auction loads, parallel processor writes, and warm start race conditions create multiple synchronization points that must be handled atomically. The mitigation strategy is proven: ConcurrentHashMap.compute() for duplicate detection, Mutex for compound operations, AtomicBoolean for callback flags, and NonCancellable context for cleanup in finally blocks. Memory management is straightforward with 30-minute TTL and periodic sweeps, but high-frequency loading requires bounded cache with LRU eviction (defer to Phase 3). Overall, this is a well-understood domain with established patterns - confidence is HIGH for stack/architecture/pitfalls, with the main execution risk being careful attention to coroutine synchronization and lifecycle management.

## Key Findings

### Recommended Stack

The stack is entirely based on Kotlin stdlib and coroutines - no external cache dependencies required. **Kotlin Coroutines 1.10.2** provides async operations with structured concurrency (upgrade from current 1.6.0 available). **ConcurrentHashMap** (Java stdlib) serves as the base storage with segmented locking for concurrent access. **MutableStateFlow** (coroutines-core) handles thread-safe cache state management with atomic updates. **Mutex** (coroutines-core) protects critical sections for compound cache operations. **Duration** (Kotlin 2.1.0) provides type-safe TTL values.

**Core technologies:**
- **Kotlin Coroutines 1.10.2**: Async operations, non-blocking cache operations — Latest stable, official Android recommendation, efficient thread usage
- **ConcurrentHashMap (stdlib)**: Cache storage — Zero-dependency, JVM-optimized for high-throughput concurrent access, segmented locking
- **MutableStateFlow (coroutines-core)**: Thread-safe state management — Built-in, atomic updates via compareAndSet, perfect for observable cache state
- **Mutex (coroutines-core)**: Critical section protection — Coroutine-friendly, non-blocking (suspends), better than synchronized for coroutine-heavy code

Supporting libraries for testing: kotlinx-coroutines-test (already in project), Mockk 1.13.5 (already in project), optional Turbine 1.1.0 for Flow testing.

### Expected Features

All table stakes features + key differentiators are included in v1 per PROJECT.md requirement. The feature set is ambitious but fully specified in the v2.0 spec document.

**Must have (table stakes):**
- **TTL-based expiration (30 min)** — Industry standard, ad policies require freshness, prevents stale inventory
- **Thread-safe cache operations** — Multiple concurrent auctions, race conditions = crashes
- **Cache invalidation on fail** — Remove invalid payloads to prevent retry loops
- **Memory-aware capacity limits (1-3 ads)** — OOM prevention on low-end devices
- **Lazy eviction + periodic sweep** — Combined approach (Redis pattern) for bounded memory
- **Duplicate detection (by demandId)** — Memory efficiency, prevents waste

**Should have (competitive advantage):**
- **Warm start optimization** — Core differentiator, sub-second response vs 3-15s cold start
- **Two-level cache (READY_TO_SHOW + RTB_PAYLOAD)** — Unique to Bidon v2, RTB payload reuse across auctions
- **Dynamic pricefloor from cache** — Prevents underpricing when higher-value ad is cached
- **Parallel RTB + CPM processing** — 30-50% latency reduction, concurrent network calls
- **Best-pick on show** — Revenue optimization, choose highest eCPM at show time
- **Application-wide cache scope** — Singleton pattern, share cache across ad instances

**Defer (v2+):**
- Advanced weight model with ML (needs 30 days data)
- Chunked parallel CPM loading (2 at a time)
- Adaptive TTL based on ad format
- Cache warming on app resume
- Cross-session persistent cache (disk I/O complexity)

### Architecture Approach

The architecture is a layered system with clear component boundaries. **AdCacheDenisImpl** serves as the entry point implementing the AdCache interface. **AuctionCoordinator** determines auction type (cold/warm), coordinates parallel processing, and manages callbacks. **CacheManager** provides a facade for both cache stores with TTL and eviction handling. **Singleton stores** (ReadyToShowStore, RtbPayloadStore) use ConcurrentHashMap for thread-safe storage. **Parallel Processor** splits waterfall into RTB/CPM groups and launches concurrent loading via RtbProcessor and CpmProcessor. Integration with existing SDK is clean - reuses GetTokensUseCase, GetAuctionRequestUseCase, ExecuteAuctionUseCase, and AuctionResolver.

**Major components:**
1. **Cache Stores (ReadyToShowStore, RtbPayloadStore)** — Singleton objects with ConcurrentHashMap storage, TTL checking, thread-safe operations
2. **Coordination Layer (AuctionCoordinator)** — State machine for cold/warm start, dynamic pricefloor calculation, token collection with cache skip, callback management
3. **Parallel Processors (RtbProcessor, CpmProcessor)** — Async coroutines for concurrent waterfall processing, atomic cache updates, exactly-once callback semantics
4. **Lifecycle Management (PeriodicSweeper, CancellationHandler)** — Background TTL sweep every 5 minutes, CPM cancellation on showAd()
5. **Integration (AdCacheDenisImpl, AdCacheFactoryImpl)** — Implements AdCache interface, factory version selection, delegation to coordinator

Key patterns: Two-level cache (L1/L2), parallel processing with first-response callback, application-wide singleton stores, warm start optimization, dynamic pricefloor adjustment. The architecture cleanly isolates v2 implementation in a `denis/` package for easy rollback if needed.

### Critical Pitfalls

Research identified 10 critical pitfalls with proven solutions. The top 5 that will impact implementation:

1. **Activity Context Retained by Singleton Cache** — Application-wide caches hold AdSource instances that may retain strong Activity references, causing massive memory leaks (entire Activity hierarchy retained for 30 minutes). **Prevention:** Use WeakReference pattern for Activity, ApplicationContext where possible, mandatory destroy() in periodic sweep, LeakCanary checks for AdSource → Activity chains.

2. **Race Condition Between notifyAdLoadedIfNeeded() and showAd()** — ConcurrentHashMap guarantees atomic individual operations BUT compound operations (put + notify, getBest + isEmpty) are NOT atomic. Result: callback fires with wrong ad or never fires. **Prevention:** Use Mutex.withLock for compound operations, synchronized blocks for putAndNotify and getBestForShow patterns.

3. **Coroutine Cancellation Doesn't Clean Up Finally Blocks** — When showAd() cancels CPM coroutines, suspending calls in finally blocks throw CancellationException and abort cleanup mid-execution, leaking AdSource instances. **Prevention:** ALL cleanup in finally blocks MUST use `withContext(NonCancellable)` to guarantee completion.

4. **TTL Clock Skew and Negative TTL Issues** — `System.currentTimeMillis()` is wall-clock time, susceptible to user changing device time. Result: entries instantly "expired" or "negative age" entries never expire. **Prevention:** Use `SystemClock.elapsedRealtime()` (Android monotonic time) instead of currentTimeMillis for all TTL calculations.

5. **Duplicate DemandId Policy Not Enforced Atomically** — Check-then-act pattern (contains → put) creates race: two threads load same demandId simultaneously → both check "not exists" → both put → cache has duplicates. **Prevention:** Use ConcurrentHashMap.compute() for atomic compare-and-swap with eCPM comparison and destroy() of replaced entry.

Additional pitfalls: Periodic sweep lifecycle leaks (Phase 1), RTB payload invalidation not propagated (Phase 2), onAdLoaded callback fired twice in warm start (Phase 2), unbounded cache growth with high-frequency loading (Phase 3), showAd() cancels CPM but not RTB saving (Phase 2).

## Implications for Roadmap

Based on research, the implementation should follow a five-phase build order driven by component dependencies and risk mitigation:

### Phase 1: Foundation (Cache Stores)
**Rationale:** Lowest-level components with no dependencies on other v2 code. Thread-safety is critical and must be validated early. TTL implementation must use monotonic time from the start (Pitfall 4).
**Delivers:** ReadyToShowStore, RtbPayloadStore, CacheManager facade, EvictionPolicy with TTL checking, data classes (ReadyToShowEntry, RtbPayloadEntry)
**Addresses:** Thread-safe cache operations (table stakes), TTL-based expiration (table stakes), duplicate detection (table stakes), memory-aware capacity limits (table stakes)
**Avoids:** Activity context leaks (Pitfall 1 - WeakReference patterns), clock skew issues (Pitfall 4 - elapsedRealtime), duplicate demandId races (Pitfall 5 - atomic compute), periodic sweep lifecycle leaks (Pitfall 6 - injected scope)
**Stack:** ConcurrentHashMap, Mutex, SystemClock.elapsedRealtime, Duration
**Validation:** Unit tests for concurrent access (1000 parallel puts), TTL expiration with time mocking, duplicate policy with concurrent same-demandId loads

### Phase 2: Parallel Processing
**Rationale:** Depends on stores from Phase 1 to write results. Processing logic can be built against existing AdSource interface. Most complex phase with coroutine orchestration and cancellation handling.
**Delivers:** WaterfallSplitter, RtbProcessor, CpmProcessor, ParallelProcessor, CallbackManager (exactly-once semantics)
**Addresses:** Parallel RTB + CPM processing (differentiator), cache invalidation on fail (table stakes), lazy eviction (table stakes)
**Avoids:** Race in callback firing (Pitfall 2 - Mutex protection), coroutine cleanup aborted (Pitfall 3 - NonCancellable in finally), RTB payload invalidation loop (Pitfall 7 - track invalidated demands), onAdLoaded fired twice (Pitfall 8 - atomic callback flag), showAd() doesn't cancel RTB (Pitfall 10 - cancel entire auctionScope)
**Stack:** Coroutine async/await, CallbackManager with AtomicBoolean, NonCancellable context
**Validation:** Mock adapters with success/failure, verify parallel execution timing, cancellation test (all AdSources destroyed), concurrent auction test (single callback)

### Phase 3: Coordination Layer
**Rationale:** Orchestrates stores (Phase 1) and processors (Phase 2). Integrates with existing SDK use cases. Complex state machine benefits from stable foundation.
**Delivers:** AuctionCoordinator (cold/warm start state machine), AuctionType enum, dynamic pricefloor calculation, token collection with cache skip
**Addresses:** Warm start optimization (core differentiator), dynamic pricefloor (differentiator), graceful degradation on cache miss (table stakes)
**Avoids:** onAdLoaded fired twice in warm start (Pitfall 8 - single flow or Mutex)
**Stack:** Reuses GetTokensUseCase, GetAuctionRequestUseCase, AuctionResolver from existing SDK
**Validation:** End-to-end cold start flow (tokens → auction → waterfall → cache), warm start flow (immediate callback + background refresh), dynamic pricefloor calculation test

### Phase 4: Lifecycle Management
**Rationale:** Operates on stores from Phase 1. Non-critical path (can be added after core flows work). Time-based logic easier to test once core is stable.
**Delivers:** PeriodicSweeper (background TTL job every 5 minutes), CancellationHandler (CPM cancellation on showAd)
**Addresses:** Periodic sweep (table stakes for memory management), auction-aware cancellation (differentiator)
**Avoids:** Periodic sweep lifecycle leaks (Pitfall 6 - explicit cancel in clear())
**Stack:** Coroutine launch with delay loop, isActive checks, NonCancellable for cleanup
**Validation:** Time-accelerated tests for TTL sweep (advanceTimeBy), sweep lifecycle test (destroy → logs stop), cancellation signal test (showAd → jobs cancelled < 100ms)

### Phase 5: Entry Point & Integration
**Rationale:** Implements existing AdCache interface, requires all v2 components ready. Final integration point with SDK.
**Delivers:** AdCacheDenisImpl (implements AdCache interface), updated AdCacheFactoryImpl (version selection), full end-to-end integration
**Addresses:** Application-wide cache scope (differentiator), best-pick on show (differentiator)
**Avoids:** N/A (all pitfalls addressed in prior phases)
**Stack:** Delegates to AuctionCoordinator, implements cache/peek/pop/poll/clear
**Validation:** Full integration test via BidonSdk public API (loadAd/showAd), warm start latency measurement (< 1s), cache hit rate validation (40-60% after warmup)

### Phase Ordering Rationale

- **Bottom-up dependency chain:** Phase 1 (stores) → Phase 2 (processors use stores) → Phase 3 (coordinator uses processors) → Phase 4 (lifecycle uses stores) → Phase 5 (entry point uses all)
- **Risk-first approach:** Phase 1 addresses 4 critical pitfalls (Activity leaks, clock skew, duplicate detection, sweep lifecycle) before parallel processing complexity
- **Testability:** Each phase can be validated independently with clear success criteria before moving to next phase
- **Rollback safety:** v2 implementation isolated in `denis/` package, factory pattern allows quick switch back to old implementation if Phase 5 integration fails
- **Critical path prioritization:** Core differentiators (warm start, parallel processing, two-level cache) completed by Phase 3, lifecycle optimizations (periodic sweep, cancellation) deferred to Phase 4

### Research Flags

**Phases NOT needing research-phase** (established patterns, clear documentation):
- **Phase 1 (Foundation):** ConcurrentHashMap, Mutex, TTL patterns are well-documented with official Android/Kotlin sources
- **Phase 2 (Parallel Processing):** Coroutine async/await patterns, cancellation handling documented in official Kotlin docs
- **Phase 3 (Coordination):** State machine patterns, integration with existing SDK (codebase already mapped)
- **Phase 4 (Lifecycle):** Periodic coroutine patterns, delay loops documented in Kotlin guides
- **Phase 5 (Entry Point):** Interface implementation, factory pattern (existing code as reference)

**All phases use standard patterns with HIGH confidence research.** No additional research needed during execution.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Official Android/Kotlin documentation, verified patterns, no external dependencies |
| Features | HIGH | Based on existing codebase analysis + production SDK documentation + spec v2.0-final |
| Architecture | HIGH | Clear component boundaries, proven patterns (L1/L2 cache, parallel processing), existing SDK integration points mapped |
| Pitfalls | HIGH | Verified from official Kotlin docs, community experience reports, specific solutions documented |

**Overall confidence:** HIGH

### Gaps to Address

The research is comprehensive with no major gaps. Minor validation points during implementation:

- **Adapter-specific context requirements:** While research recommends WeakReference pattern for Activity, some ad network SDKs may have specific context requirements. Validation: Audit AdMob, Meta, BidMachine, Unity adapters during Phase 1 to confirm ApplicationContext compatibility or document exceptions.

- **Cache size limits for production:** Research recommends 1-3 ads for READY_TO_SHOW, 5-10 payloads for RTB_PAYLOAD. Validation: Monitor memory usage in Phase 5 integration testing to confirm limits are appropriate for target devices (Android API 23+).

- **Coroutine upgrade impact:** Upgrading from coroutines 1.6.0 to 1.10.2 should be low-risk (Flow API additions only) but validate existing SDK code still compiles after upgrade in Phase 1.

- **Exactly-once callback semantics:** While AtomicBoolean + Mutex pattern is proven, warm start + parallel processing creates multiple code paths to onAdLoaded(). Validation: Add integration test in Phase 3 with instrumented callback counting across all scenarios (cold start, warm start, concurrent loads).

All gaps have clear validation strategies and don't block execution. Proceed with high confidence.

## Sources

### High Confidence (Official/Authoritative)

**Kotlin & Android Official:**
- [Kotlin Coroutines Best Practices | Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) — Thread-safety, Mutex vs synchronized
- [StateFlow and SharedFlow | Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) — Thread-safety guarantees
- [Kotlin Shared Mutable State and Concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html) — Official Mutex patterns
- [Kotlin Coroutines Releases](https://github.com/Kotlin/kotlinx.coroutines/releases) — Version 1.10.2 release notes

**Ad Mediation Industry:**
- [Optimize initialization and ad loading | Android | Google Developers](https://developers.google.com/admob/android/optimize-initialization) — AdMob patterns
- [MAX | Getting started | Axon by AppLovin | Support Center](https://support.axon.ai/en/max/getting-started/) — AppLovin MAX architecture
- [Unity LevelPlay mediation best practices](https://developers.is.com/ironsource-mobile/air/best-practices-waterfall-management-ironsource-mediation/) — IronSource patterns

### Medium Confidence (Verified Community Sources)

**Cache Patterns:**
- [cache4k - ReactiveCircus](https://github.com/ReactiveCircus/cache4k) — Kotlin Multiplatform cache, TTL patterns
- [Cache Eviction Strategies | Redis](https://redis.io/blog/cache-eviction-strategies/) — TTL + LRU hybrid approach
- [Kotlin Mutex: Thread-Safe Concurrency Guide](https://carrion.dev/en/posts/kotlin-mutex-concurrency-guide/) — Performance benchmarks

**Coroutine Patterns:**
- [Implement Scheduler/Timer with Kotlin Coroutine | Baeldung](https://www.baeldung.com/kotlin/coroutine-timer-scheduler) — Periodic tasks
- [Internal Mechanism of Coroutine Cancellation](https://medium.com/@mahesh31.ambekar/internal-mechanism-of-coroutine-cancellation-in-kotlin-b239188f87a7) — NonCancellable context
- [Cancellation in coroutines | Android Developers](https://medium.com/androiddevelopers/cancellation-in-coroutines-aa6b90163629) — Official guidance

**Thread-Safety:**
- [ConcurrentHashMap in Kotlin — Thread-Safe and Smart](https://medium.com/@ys.yogendra22/concurrenthashmap-in-kotlin-thread-safe-and-smart-4f513806bde7) — Segmented locking
- [VNA03-J. Do not assume that a group of calls to independently atomic methods is atomic - SEI CERT](https://wiki.sei.cmu.edu/confluence/display/java/VNA03-J.+Do+not+assume+that+a+group+of+calls+to+independently+atomic+methods+is+atomic) — Atomic operation patterns

### Project Context

- Bidon SDK existing codebase at `/Users/glavatskikh/StudioProjects/bidon-sdk-android`
- Architecture documentation: `.planning/codebase/ARCHITECTURE.md`
- Specification: `docs/AD_CACHING_SPEC.md` (v2.0-final)
- Current auction flow: `docs/AUCTION_ARCHITECTURE.md`

---
*Research completed: 2026-02-05*
*Ready for roadmap: yes*
