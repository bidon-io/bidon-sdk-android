---
phase: 03-coordination-layer
plan: 03
subsystem: auction
tags: [kotlin, coroutines, waterfall-splitting, auction-orchestration, bidding, cpm]

# Dependency graph
requires:
  - phase: 03-coordination-layer
    plan: 01
    provides: CoordinationLayer foundation with state detection and pricefloor calculation
  - phase: 03-coordination-layer
    plan: 02
    provides: GetTokensUseCase with skipDemandIds parameter
  - phase: 02-parallel-processing
    provides: ParallelAuctionOrchestrator for RTB + CPM execution
affects: [03-04]

# Tech tracking
tech-stack:
  added: []
  patterns: [sealed-class-return-types, adapter-interface-inspection, file-private-extensions]

key-files:
  created:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/WaterfallSplitter.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/AuctionCompletionType.kt
  modified:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt

key-decisions:
  - "Split waterfall using filterIsInstance<Adapter.Bidding>() pattern from GetTokensUseCaseImpl"
  - "Return AuctionCompletionType from coordinateAuction() to signal warm start (caller MUST NOT start another auction)"
  - "Dynamic pricefloor wired via file-private withPricefloor() extension function"
  - "Pass only CPM adUnits to ParallelAuctionOrchestrator (RTB handled via cache lookup)"
  - "Warm start uses cached auctionId from original ad load (no new auction metadata)"

patterns-established:
  - "Sealed class return types for state signaling (WarmStartServed contract)"
  - "File-private extension functions for sealed class manipulation (withPricefloor)"
  - "Adapter interface inspection for runtime type checking (Adapter.Bidding)"

# Metrics
duration: 4min
completed: 2026-02-05
---

# Phase 3 Plan 03: Waterfall Splitting & Full Orchestration Summary

**CoordinationLayer now orchestrates complete auction flow: warm start callback, cold start with waterfall splitting by adapter type, dynamic pricefloor wiring, and parallel RTB+CPM execution**

## Performance

- **Duration:** 4 min 6 sec
- **Started:** 2026-02-05T16:35:09Z
- **Completed:** 2026-02-05T16:39:15Z
- **Tasks:** 2
- **Files created:** 2
- **Files modified:** 1

## Accomplishments

- Created WaterfallSplitter for RTB/CPM waterfall splitting based on Adapter.Bidding interface
- Created AuctionCompletionType sealed class to signal warm start completion
- Completed CoordinationLayer with full coordinateAuction() flow
- Wired dynamic pricefloor to auction request via withPricefloor() extension
- Integrated WaterfallSplitter and ParallelAuctionOrchestrator
- Warm start path fires callback immediately and returns WarmStartServed

## Task Commits

Each task was committed atomically:

1. **Task 1: Create WaterfallSplitter** - `6626ac53` (feat)
2. **Task 2: Create AuctionCompletionType and update CoordinationLayer** - `62efa917` (feat)

## Files Created/Modified

### Created
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/WaterfallSplitter.kt` - Splits AdUnits into RTB and CPM groups using Adapter.Bidding interface check
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/AuctionCompletionType.kt` - Sealed class signaling warm start completion (caller MUST NOT start another auction)

### Modified
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt` - Added coordinateAuction() with warm/cold start handling, waterfall splitting, and parallel orchestration

## Decisions Made

**1. Waterfall splitting by adapter interface inspection**
- Use filterIsInstance<Adapter.Bidding>() to identify RTB adapters
- Pattern matches GetTokensUseCaseImpl for consistency
- AdUnits with bidding adapters go to RTB group, others to CPM group
- Decision: "Determine RTB vs CPM by checking Adapter.Bidding interface"

**2. Sealed class return type for warm start signaling**
- AuctionCompletionType.WarmStartServed signals caller MUST NOT start another auction
- Enforces decision: "No background refresh on warm start"
- Compile-time safety with exhaustive when expressions
- Pattern: sealed class for state machine returns

**3. Dynamic pricefloor wiring via file-private extension**
- Created withPricefloor() as file-private extension function
- Recreates sealed AdTypeParam subtypes with new pricefloor value
- Passed to getAuctionRequest.request() ensuring backend receives dynamic pricefloor
- Decision: "Dynamic pricefloor must be passed to auction request"

**4. CPM-only waterfall to ParallelAuctionOrchestrator**
- Only split.cpmAdUnits passed to orchestrator
- RTB handled separately via RtbPayloadCache lookup flag
- Clear separation of concerns between RTB cache and CPM waterfall
- Pattern: waterfall splitting before parallel execution

**5. Warm start metadata construction**
- Use cached auctionId from original ad load
- Set auctionConfigurationId/Uid to null (not stored in cache)
- Use cached eCPM as auctionPricefloor
- Decision: cached ad sufficient for warm start callback

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

**AuctionResult vs AuctionInfo confusion**
- Issue: Initial implementation assumed AuctionResult contained full auction metadata
- Reality: AuctionResult contains AdSource, not auction configuration
- Resolution: Construct AuctionInfo from CacheEntry fields (auctionId, ecpm)
- Impact: Warm start AuctionInfo has null config fields (acceptable, ad is cached)

**Nullable AuctionResponse fields**
- Issue: AuctionResponse.adUnits is nullable, required null-safe handling
- Resolution: Use `response.adUnits ?: emptyList()` before splitting
- Pattern: defensive programming for auction response data

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

**Ready for Phase 4 (SDK Integration):**
- ✅ CoordinationLayer.coordinateAuction() is complete entry point
- ✅ Returns AuctionCompletionType to signal warm start (no background auction)
- ✅ Dynamic pricefloor flows from cache state to auction request
- ✅ Waterfall splitting separates RTB and CPM groups correctly
- ✅ ParallelAuctionOrchestrator integrated with split waterfall
- ✅ Token collection skips cached adapters via skipDemandIds

**Integration requirements for Phase 4:**
- Wire CoordinationLayer to AdCache.cache() public API
- Instantiate CoordinationLayer with dependencies (adaptersSource, getTokens, getAuctionRequest, orchestrator)
- Handle AuctionCompletionType return (prevent double auction on warm start)
- Pass through user callbacks (onSuccess, onFailure) to coordinateAuction()

**Verification needed in Phase 4:**
- Confirm warm start fires callback within <1s
- Confirm WarmStartServed prevents background auction
- Confirm dynamic pricefloor protects cached ad value
- Confirm waterfall splitting routes adapters correctly (RTB vs CPM)
- Monitor logs for split counts and skipped token collection

**Phase 3 Completion Status:**
- Plan 01: ✅ CoordinationLayer foundation (state detection, pricefloor calculation)
- Plan 02: ✅ Token collection skip (skipDemandIds parameter)
- Plan 03: ✅ Waterfall splitting and full orchestration (THIS PLAN)
- Plan 04: ⬜ Pending - Factory integration for AdCache.cache() wiring

---
*Phase: 03-coordination-layer*
*Completed: 2026-02-05*
