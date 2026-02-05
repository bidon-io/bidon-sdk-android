---
phase: 05-entry-point-integration
plan: 05
subsystem: orchestration
tags: [kotlin, coroutines, callbacks, parallel-processing]

# Dependency graph
requires:
  - phase: 05-04
    provides: GetTokensWithSkipUseCase wrapper for V2 skip logic
  - phase: 05-01
    provides: AdCacheDenisImpl entry point with known callback issue
  - phase: 02-parallel-processing
    provides: CallbackCoordinator and ParallelAuctionOrchestrator
provides:
  - Per-auction orchestrator creation with actual callbacks (not shared no-ops)
  - Correct callback semantics for multiple cache() calls
  - Factory simplified (processors-only, orchestrator created per-auction)
affects: [testing, UAT, production-readiness]

# Tech tracking
tech-stack:
  added: []
  patterns: [per-auction orchestrator pattern, callback closure pattern]

key-files:
  created: []
  modified:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/AdCacheDenisFactory.kt

key-decisions:
  - "CallbackCoordinator created per-auction inside handleColdStart() with actual onSuccess/onFailure"
  - "ParallelAuctionOrchestrator created per-auction with fresh CallbackCoordinator"
  - "Factory creates only processors (shared) and passes to CoordinationLayer"
  - "Orchestrator field removed from CoordinationLayer (local variable in handleColdStart)"

patterns-established:
  - "Per-auction orchestrator pattern: Create orchestrator inside request handler with actual callbacks from request scope"
  - "Callback closure pattern: Lambda captures actual callbacks from coordinateAuction() parameters"

# Metrics
duration: 2min
completed: 2026-02-05
---

# Phase 05 Plan 05: Callback Architecture Fix Summary

**Per-auction orchestrator creation with actual callbacks eliminates shared no-op pattern and enables correct callback semantics for multiple cache() calls**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-05T21:55:38Z
- **Completed:** 2026-02-05T21:57:38Z
- **Tasks:** 1
- **Files modified:** 2

## Accomplishments
- CallbackCoordinator now created per-auction with actual onSuccess/onFailure callbacks
- ParallelAuctionOrchestrator created per-auction with fresh coordinator
- Factory simplified to only create processors (shared across auctions)
- Multiple cache() calls fire their own callbacks correctly (CRITICAL issue resolved)

## Task Commits

Each task was committed atomically:

1. **Task 1: Refactor CoordinationLayer to create orchestrator per-auction** - `6b61fe1b` (refactor)

## Files Created/Modified
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt` - Changed constructor to receive processors instead of orchestrator; creates per-auction orchestrator in handleColdStart() with actual callbacks
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/AdCacheDenisFactory.kt` - Removed CallbackCoordinator and ParallelAuctionOrchestrator creation; passes processors directly to CoordinationLayer

## Decisions Made

**1. Per-auction orchestrator creation**
- **Decision:** Create CallbackCoordinator and ParallelAuctionOrchestrator inside handleColdStart() where actual callbacks are available
- **Rationale:** Orchestrator was instance-scoped but callbacks are request-scoped. Shared orchestrator with no-op callbacks meant multiple cache() calls couldn't fire their own callbacks
- **Alternative considered:** Pass callbacks to orchestrator.executeParallelAuction() - rejected because orchestrator already had callbackCoordinator field
- **Impact:** Each auction has its own orchestrator with correct callbacks. Multiple cache() calls work correctly

**2. Factory simplification**
- **Decision:** Factory creates only processors (shared) and passes to CoordinationLayer
- **Rationale:** Processors are stateless and can be shared. Orchestrator needs per-auction state
- **Impact:** Cleaner separation of concerns. Factory handles shared dependencies, CoordinationLayer handles per-auction state

**3. CoordinationLayer constructor change**
- **Decision:** Replace `orchestrator` parameter with `rtbProcessor` and `cpmProcessor`
- **Rationale:** CoordinationLayer needs processors to create orchestrator per-auction
- **Impact:** Makes dependency graph explicit. CoordinationLayer owns orchestrator lifecycle

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - refactoring was straightforward with clear ownership changes.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**All Phase 5 plans complete:**
- ✅ 05-01: AdCacheDenisImpl entry point
- ✅ 05-02: DI wiring and factory integration
- ✅ 05-03: Factory isolation and API contract fixes
- ✅ 05-04: GetTokensUseCase interface isolation
- ✅ 05-05: Callback architecture fix (this plan)

**Known CRITICAL issue from 05-01-SUMMARY RESOLVED:**
The callback architecture issue (CallbackCoordinator with no-op callbacks) is now fixed. Multiple cache() calls will fire their own callbacks correctly.

**Ready for:**
- E2E testing with multiple cache() calls
- Production integration
- Performance validation (<1-3s onAdLoaded requirement)

**No blockers remaining.**

## Self-Check: PASSED

All files and commits verified to exist.

---
*Phase: 05-entry-point-integration*
*Completed: 2026-02-05*
