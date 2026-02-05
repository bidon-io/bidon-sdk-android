---
phase: 03-coordination-layer
plan: 01
subsystem: auction-orchestration
tags: [kotlin, coroutines, sealed-classes, cache-optimization, state-machine]

# Dependency graph
requires:
  - phase: 01-foundation
    provides: ReadyToShowCache and RtbPayloadCache with isEmpty(), getMaxEcpm(), getBest(), getCachedDemandIds() APIs
  - phase: 02-parallel-processing
    provides: CallbackCoordinator for exactly-once callback semantics
provides:
  - AuctionStartState sealed class for exhaustive cold/warm start decisions
  - CacheStateSnapshot for immutable cache state capture at auction start
  - PricefloorCalculator with 0.9 safety margin for dynamic pricefloor calculation
  - CoordinationLayer entry point for auction orchestration
affects: [03-02-token-skipping, 03-03-waterfall-splitting, integration]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Sealed class state machine for exhaustive type-safe decisions
    - Immutable snapshot pattern for cache state at auction start
    - Safety margin (0.9) for dynamic pricefloor calculation

key-files:
  created:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/AuctionStartState.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CacheStateSnapshot.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/PricefloorCalculator.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt
  modified:
    - bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt

key-decisions:
  - "Sealed class hierarchy for WarmStart, ColdStartWithCache, PureColdStart states"
  - "Single cache state snapshot at auction start (no re-validation during processing)"
  - "0.9 safety margin allows slightly better bids while protecting cached value"
  - "CoordinationLayer returns Pair(state, snapshot) for pricefloor calculation"

patterns-established:
  - "Sealed class state machine: Exhaustive when expressions with compile-time safety"
  - "Snapshot pattern: Capture once, use throughout lifecycle (prevent race conditions)"
  - "Safety margin calculation: max(userPricefloor, 0.9 * maxCachedEcpm)"

# Metrics
duration: 3min
completed: 2026-02-05
---

# Phase 03 Plan 01: Coordination Layer Foundation Summary

**Sealed class state machine for warm/cold start detection, immutable cache snapshot with 0.9 safety margin pricefloor calculator**

## Performance

- **Duration:** 3 min
- **Started:** 2026-02-05T16:27:52Z
- **Completed:** 2026-02-05T16:31:03Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- AuctionStartState sealed class enables exhaustive cold/warm start decisions with type safety
- CacheStateSnapshot captures cache state once at auction start to prevent race conditions
- PricefloorCalculator applies 0.9 safety margin to cached eCPM for dynamic pricefloor calculation
- CoordinationLayer provides entry point with determineStartState() and calculatePricefloor() methods

## Task Commits

Each task was committed atomically:

1. **Task 1: Create AuctionStartState sealed class** - `a6bd953d` (feat)
   - WarmStart, ColdStartWithCache, PureColdStart states
   - Each state carries necessary data for subsequent processing

2. **Task 2: Create CacheStateSnapshot and PricefloorCalculator** - `79ab8e81` (feat)
   - Immutable snapshot with companion capture() function
   - Dynamic pricefloor with 0.9 safety margin and logging

3. **Task 3: Create CoordinationLayer entry point** - `fa3eb7a6` (feat)
   - determineStartState() decides warm vs cold start
   - calculatePricefloor() delegates to PricefloorCalculator
   - Returns Pair(state, snapshot) for pricefloor calculation

## Files Created/Modified

**Created:**
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/AuctionStartState.kt` - Sealed class for warm/cold start states
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CacheStateSnapshot.kt` - Immutable cache state snapshot at auction start
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/PricefloorCalculator.kt` - Dynamic pricefloor calculation with safety margin
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt` - Entry point for auction orchestration

**Modified:**
- `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt` - Fixed parameter signature (removed redundant default value)

## Decisions Made

1. **Sealed class state machine:** Enables exhaustive when expressions with compile-time safety. Each state carries only the data needed for that path (bestAd for warm, cachedDemandIds for cold-with-cache, userPricefloor for pure cold).

2. **Single snapshot at auction start:** User decision "Cache state changes during processing are acceptable" - capture once, use throughout. Prevents inconsistent decisions and race conditions.

3. **0.9 safety margin:** User decision "Apply safety margin: pricefloor = 0.9 * max(...)" allows bids within 10% of cached value to compete while protecting against significantly worse bids.

4. **Pair return type:** CoordinationLayer returns (state, snapshot) so caller has snapshot for pricefloor calculation without re-querying caches.

5. **Edge case handling:** When cache reports non-empty but getBest() returns null (race condition), fall back to PureColdStart to maintain correctness.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed GetTokensUseCaseImpl parameter signature**
- **Found during:** Task 3 compilation
- **Issue:** GetTokensUseCaseImpl had `skipDemandIds: Set<String>` parameter but Kotlin disallows overriding functions from specifying default values. Interface has default, implementation tried to duplicate it.
- **Fix:** Removed default value from implementation (kept in interface only)
- **Files modified:** bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt
- **Verification:** BUILD SUCCESSFUL, no compilation errors
- **Committed in:** fa3eb7a6 (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking issue)
**Impact on plan:** Blocking issue was pre-existing (interface change from planning phase not reflected in implementation). Fixed to unblock compilation.

## Issues Encountered

None - all tasks executed as planned after blocking issue resolved.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for plan 03-02 (Token Collection Optimization):**
- AuctionStartState provides warm/cold start decision for token skipping logic
- CacheStateSnapshot provides cachedDemandIds for skipDemandIds parameter
- PricefloorCalculator ready for integration into auction request flow

**Ready for plan 03-03 (Waterfall Splitting):**
- CoordinationLayer provides entry point for auction orchestration
- Sealed class pattern enables adding waterfall splitting logic to cold start paths

**No blockers:** All coordination layer components compile and follow research patterns.

---
*Phase: 03-coordination-layer*
*Completed: 2026-02-05*
