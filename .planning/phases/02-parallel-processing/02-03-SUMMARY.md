---
phase: 02-parallel-processing
plan: 03
subsystem: ad-loading
tags: [kotlin-coroutines, cpm-waterfall, weight-model, sequential-loading, cache-integration]

# Dependency graph
requires:
  - phase: 01-foundation-cache-stores
    provides: ReadyToShowCache with TTL and thread-safety patterns
  - phase: 02-01
    provides: WeightModel for dynamic CPM waterfall sorting
provides:
  - CpmProcessor for sequential CPM waterfall loading
  - Suspend function loadWaterfall() with proper coroutine cancellation
  - Integration with WeightModel for fill/no-fill feedback loop
  - Cache integration storing all successful loads in ReadyToShowCache
affects: [02-04-parallel-orchestration, 03-integration]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Sequential waterfall loading (one adUnit at a time)"
    - "Continue-through-entire-waterfall pattern (don't stop on first success)"
    - "Fill/no-fill feedback loop with WeightModel"

key-files:
  created:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/processors/CpmProcessor.kt
  modified: []

key-decisions:
  - "Continue entire waterfall (don't stop on first success) to fill ReadyToShowCache with multiple ads"
  - "Record fill/no-fill for every attempt (builds weight model for future optimizations)"
  - "Sequential loading (one at a time) to maintain waterfall ordering discipline"

patterns-established:
  - "Pattern: Continue-through-entire-waterfall - all adUnits loaded even after first success"
  - "Pattern: Fill/no-fill feedback loop - WeightModel.recordFill/recordNoFill after each attempt"
  - "Pattern: AdSource cleanup in finally block with isAdReadyToShow check"

# Metrics
duration: 2min
completed: 2026-02-05
---

# Phase 02 Plan 03: CpmProcessor Summary

**Sequential CPM waterfall loading with WeightModel-based sorting, fill/no-fill feedback loop, and continue-through-entire-waterfall pattern for cache filling**

## Performance

- **Duration:** 2 min
- **Started:** 2026-02-05T16:07:48Z
- **Completed:** 2026-02-05T16:10:40Z
- **Tasks:** 1 (combined implementation)
- **Files modified:** 1

## Accomplishments
- CpmProcessor with sequential waterfall loading (one adUnit at a time)
- WeightModel integration for dynamic sorting by eCPM × (weight/10) score
- Fill/no-fill feedback loop updating WeightModel after each attempt
- Continue-through-entire-waterfall pattern - all adUnits loaded even after first success
- Successful loads stored in ReadyToShowCache with proper type handling
- Proper coroutine cancellation support with ensureActive()
- AdSource cleanup in finally blocks on all failure paths

## Task Commits

Each task was committed atomically:

1. **Task 1: Create CpmProcessor with loadWaterfall function** - `1feb7648` (feat)
   - Note: Implementation included Task 2 requirements (loadSingleAdUnit helper with cleanup)

## Files Created/Modified
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/processors/CpmProcessor.kt` - Sequential CPM waterfall processor with WeightModel integration

## Decisions Made

**1. Continue entire waterfall (don't stop on first success)**
- Rationale: Fills ReadyToShowCache with multiple ads for warm start optimization
- Alternative: Stop after first success considered but rejected - reduces cache fill rate

**2. Record fill/no-fill for every attempt**
- Rationale: Builds WeightModel with every auction for faster convergence to optimal ordering
- Implementation: recordFill/recordNoFill called in success/failure branches

**3. Sequential loading (one at a time)**
- Rationale: Maintains waterfall ordering discipline, prevents race conditions in weight updates
- Pattern matches CPM industry standard (waterfall = sequential by definition)

**4. Type-safe cache entry storage**
- Rationale: CacheEntry requires AuctionResult (interface), not AuctionResult.Network (subtype)
- Solution: Explicit type annotation `val auctionResult: AuctionResult = AuctionResult.Network(...)`

## Deviations from Plan

None - plan executed exactly as written.

Note: Task 2 requirements (loadSingleAdUnit helper, cleanup, logging) were implemented in Task 1 as they were natural parts of a complete implementation.

## Issues Encountered

**1. Compilation errors with ensureActive() and BidonError types**
- Issue: Direct `ensureActive()` call failed (requires coroutine context)
- Solution: Wrapped in `coroutineScope { ensureActive() }` for proper context
- Issue: `BidonError.UnknownAdapter` doesn't exist
- Solution: Used `BidonError.NoFill(DemandId(...))` matching RtbProcessor pattern

**2. Type variance issue with CacheEntry**
- Issue: `CacheEntry<AuctionResult.Network>` not assignable to `CacheEntry<AuctionResult>`
- Solution: Explicit type annotation forces correct inference
- Root cause: Generic type T in CacheEntry is invariant, not covariant

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for Plan 02-04 (Parallel Orchestration):**
- CpmProcessor.loadWaterfall() ready for async launch alongside RtbProcessor
- CpmWaterfallResult provides success/failure counts for analytics
- WeightModel feedback loop operational for iterative learning
- Cache integration complete with proper type handling

**Integration notes:**
- CpmProcessor is internal class, not exposed outside .denis package
- Uses existing AdaptersSource, Regulation from SDK core (injected via constructor)
- Compatible with all existing adapter implementations (follows ExecuteAuctionUseCaseImpl patterns)
- Sequential loading ensures no adapter state corruption from concurrent access

**No blockers identified**

---
*Phase: 02-parallel-processing*
*Completed: 2026-02-05*
