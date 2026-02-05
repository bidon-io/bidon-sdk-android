# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-05)

**Core value:** Быстрый onAdLoaded callback (<1-3 сек вместо 3-15 сек) при сохранении или повышении revenue за счет умного переиспользования кэшированных bid responses и параллельной загрузки рекламы
**Current focus:** Phase 5 - Entry Point & Integration

## Current Position

Phase: 5 of 5 (Entry Point & Integration)
Plan: 4 of 5 in current phase
Status: In progress
Last activity: 2026-02-05 — Completed 05-04-PLAN.md (GetTokensUseCase interface isolation)

Progress: [████████████] 96%

## Performance Metrics

**Velocity:**
- Total plans completed: 20
- Average duration: 2.5 min
- Total execution time: 0.8 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Foundation (Cache Stores) | 3 | 4 min | 1.3 min |
| 2. Parallel Processing | 5 | 13 min | 2.6 min |
| 3. Coordination Layer | 3 | 8 min | 2.7 min |
| 4. Lifecycle Management | 5 | 13 min | 2.6 min |
| 5. Entry Point & Integration | 4 | 16 min | 4.0 min |

**Recent Trend:**
- Last 5 plans: 05-01 (8 min), 05-02 (2 min), 05-03 (2 min), 05-04 (4 min)
- Trend: Gap closure plans maintain consistent 2-4 min duration

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Отдельный пакет .denis вместо .v2 для экспериментальной реализации (легко откатить)
- Singleton objects для кэшей (простота, thread-safety out of the box)
- Factory pattern для интеграции (легко переключаться между версиями)
- Kotlin Coroutines вместо RxJava (современный подход в Android)
- TTL 30 минут фиксированный (баланс между актуальностью и fill rate)
- Atomic compute() for duplicate detection (prevents race conditions, SAFETY-02)
- Higher eCPM always wins in duplicate scenarios (CACHE-07)
- Default capacity 10 for RTB_PAYLOAD cache (upper bound of 5-10 range)
- Weight bounds 1-20 with default 10 for predictable behavior (02-01)
- Multiplicative scoring (eCPM × weight/10) instead of additive for intuitive scaling (02-01)
- In-memory only weight storage (resets on app restart) - no persistence needed (02-01)
- Load only highest-eCPM RTB payload per auction (single attempt, not waterfall) (02-02)
- Remove payload from cache only on failure to prevent retry of broken bids (02-02)
- AdSource destroyed only on failure - success stores in cache for later show (02-02)
- Continue entire CPM waterfall (don't stop on first success) to fill ReadyToShowCache with multiple ads (02-03)
- Record fill/no-fill for every CPM attempt (builds weight model for future optimizations) (02-03)
- Sequential CPM loading (one at a time) to maintain waterfall ordering discipline (02-03)
- AtomicBoolean for exactly-once semantics (lock-free, no contention) (02-04)
- supervisorScope isolates failures between RTB and CPM branches (02-04)
- Callback fires when cache transitions empty -> non-empty (first ad cached) (02-04)
- Failure callback only fires if cache was empty AND both branches failed (02-04)
- Both branches always run to completion (no early termination) (02-04)
- loadSuccess flag pattern for conditional AdSource cleanup (prevent destroying successfully loaded ads) (02-05)
- Remove RTB payload only when load is attempted (not on early failures like adapter not found) (02-05)
- RTB retry: iterate all payloads until success or exhaustion (not single-attempt) (02-05)
- Sealed class hierarchy for WarmStart, ColdStartWithCache, PureColdStart states (03-01)
- Single cache state snapshot at auction start (no re-validation during processing) (03-01)
- 0.9 safety margin allows slightly better bids while protecting cached value (03-01)
- CoordinationLayer returns Pair(state, snapshot) for pricefloor calculation (03-01)
- Default emptySet() parameter for skipDemandIds ensures backward compatibility (03-02)
- Split filtering logic: all bidding → filter cached → apply regulation (03-02)
- Waterfall splitting by filterIsInstance<Adapter.Bidding>() interface check (03-03)
- AuctionCompletionType.WarmStartServed signals caller MUST NOT start another auction (03-03)
- Dynamic pricefloor wired via file-private withPricefloor() extension function (03-03)
- Pass only CPM adUnits to ParallelAuctionOrchestrator (RTB via cache lookup) (03-03)
- SupervisorJob for periodic sweep: failures don't crash ad instance (04-01)
- Delay-first sweep pattern: first sweep after 5 minutes, not immediately (04-01)
- while(isActive) + delay() for cooperative cancellation on scope destroy (04-01)
- Public sweep() API on caches returns removal count for telemetry (04-01)
- AuctionId matching prevents accidentally cancelling unrelated auctions (04-02)
- Idempotent cancellation via state clearing (safe to call cancelIfMatching twice) (04-02)
- Synchronized blocks for thread-safe atomic state updates (04-02)
- Job.cancel() for coroutine cancellation signaling (04-02)
- withContext(NonCancellable) for all cleanup operations (guaranteed completion) (04-03)
- Log failures but don't propagate exceptions from cleanup (resilience) (04-03)
- Parallel AdSource destruction with coroutineScope + launch for speed (04-03)
- Keep loadSuccess flag pattern from Phase 2 (separation of concerns) (04-03)
- LifecycleManager is instance-scoped, not singleton (one per ad instance) (04-05)
- Auction job launched on lifecycleManager.getScope() for cancellation support (04-05)
- AuctionId generated before job launch for proper tracking (04-05)
- onAuctionCompleted() in finally block guarantees state cleanup (04-05)
- AdCacheDenisImpl acts as facade over Phase 1-4 components (05-01)
- resolver parameter kept but unused in V2 for API compatibility (05-01)
- clear() is NO-OP in V2 (TTL-based eviction only) (05-01)
- CoordinationLayer and LifecycleManager created per ad instance in factory (05-01)
- CallbackCoordinator created with no-op callbacks (temporary limitation) (05-01)
- GetAuctionRequestUseCase registration belongs in FlavoredDI, not main DI.kt (05-02)
- Duplicate DI registrations break build variants with different constructors (05-02)
- AdCacheDenisFactory is object (not class) - stateless factory (05-03)
- poll() uses delay-based loop (100ms) for V1 suspending semantics (05-03)
- withSettings() is NO-OP in V2 (no global singleton mutation) (05-03)
- Factory delegation keeps AdCacheFactoryImpl constructor unchanged (05-03)
- Wrapper pattern for V2 skip logic isolation (GetTokensWithSkipUseCase) (05-04)
- FilteredAdaptersSource created as private class in wrapper file (05-04)
- Wrapper created locally in factory, not via DI (V2-specific component) (05-04)
- GetTokensUseCase reverted to original 3-param interface (no V2 pollution) (05-04)

### Pending Todos

None yet.

### Blockers/Concerns

**Phase 2 Verification Gaps (Closed by 02-05):**
- ✅ Truth #6: AdSource cleanup in finally block (was scattered across early returns)
- ✅ RTB-02: Retry next payload on failure (was single-attempt only)
- ✅ RTB-03: Save remaining valid payloads (was not implementing retry logic)

**Phase 1 Considerations:**
- Adapter-specific context requirements must be validated (WeakReference pattern vs ApplicationContext)
- Cache size limits (1-3 READY_TO_SHOW, 5-10 RTB_PAYLOAD) to be validated during integration testing
- Coroutine upgrade from 1.6.0 to 1.10.2 should be validated for existing SDK compatibility

**Known Critical Pitfalls (from research):**
- Activity context retained by singleton cache (WeakReference pattern consideration)
- Race condition between put + notify operations (Mutex.withLock for compound operations)
- ✅ Coroutine cancellation cleanup failures (NonCancellable context implemented 04-03)
- TTL clock skew with System.currentTimeMillis (use SystemClock.elapsedRealtime)
- Duplicate demandId detection not atomic (ConcurrentHashMap.compute() required)

**Phase 3 Progress:**
- ✅ CoordinationLayer foundation: state detection, pricefloor calculation (03-01)
- ✅ Token collection skip: skipDemandIds parameter added to GetTokensUseCase (03-02)
- ✅ Full orchestration: waterfall splitting, coordinateAuction() complete (03-03)
- ⬜ Factory integration: wire CoordinationLayer to AdCache.cache() API (03-04 pending)

**Phase 3 Critical Dependency:**
- 🛑 CoordinationLayer complete but still orphaned (no SDK integration)
- 🛑 RtbProcessor and CpmProcessor are unreachable until 03-04 factory wiring
- Phase 2 + 3 deliver zero value until factory integration completes

**Phase 4 Complete:**
- ✅ Periodic sweep infrastructure: AdInstanceScope + PeriodicSweepJob (04-01)
- ✅ Cancellation management: CancellationManager with Job.cancel() handling (04-02)
- ✅ Cleanup coordination: NonCancellable context + parallel AdSource.destroy() (04-03)
- ✅ WeakReference validation: WeakContextValidator for Activity context cleanup (04-04)
- ✅ Lifecycle integration: LifecycleManager facade wired into CoordinationLayer (04-05)

**Phase 4 Verified:**
All lifecycle infrastructure complete and functional:
- ✅ Periodic sweep job runs every 5 minutes (lifecycleManager.start() called)
- ✅ Cleanup in finally blocks uses NonCancellable (CleanupCoordinator integrated)
- ✅ Activity context uses WeakReference (WeakContextValidator called from sweep)
- ⏳ Sweep job stop on destroyAd() (requires Phase 5 AdCache.destroyAd())
- ⏳ showAd() cancels auction (requires Phase 5 AdCache.showAd())

**Phase 5 Progress:**
- ✅ AdCacheDenisImpl entry point: Complete facade implementation (05-01)
- ✅ DI wiring: GetAuctionRequestUseCase registered, factory dependencies injected (05-01)
- ✅ Factory integration: AdCacheFactoryImpl creates fully-wired V2 instances (05-02)
- ✅ Build validation: Both production and serverless variants compile successfully (05-02)
- ✅ Factory isolation: AdCacheDenisFactory extracts V2 logic from AdCacheFactoryImpl (05-03)
- ✅ API contract fixes: poll() suspends (V1 semantics), withSettings() is NO-OP (05-03)
- ✅ Interface isolation: GetTokensUseCase reverted, V2 skip logic in wrapper (05-04)

**Phase 5 Known Issues:**
- 🔴 CRITICAL: CallbackCoordinator created with no-op callbacks (shared orchestrator pattern broken)
  - **Impact:** Multiple cache() calls won't fire callbacks correctly
  - **Root cause:** Orchestrator is instance-scoped but callbacks are request-scoped
  - **Solution needed:** Create orchestrator per-auction with actual callbacks
  - **Workaround:** V2 works for single auction per instance (warm start bypasses orchestrator)
  - **Priority:** HIGH - blocks multi-auction scenarios

## Session Continuity

Last session: 2026-02-05 22:50:49 UTC
Stopped at: Completed 05-04-PLAN.md - GetTokensUseCase interface isolation via wrapper pattern
Resume file: None
