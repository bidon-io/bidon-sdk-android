# Roadmap: Bidon SDK — Ad Caching v2 Implementation

## Overview

This roadmap transforms the Bidon SDK's ad loading pipeline from a fully-blocking 3-15 second waterfall into a sub-second warm start system through two-level caching (READY_TO_SHOW + RTB_PAYLOAD) and parallel RTB/CPM processing. The implementation follows a bottom-up dependency chain: foundational cache stores with thread-safety and TTL management, parallel processors with coroutine orchestration, coordination layer for warm/cold start state machine, lifecycle management for periodic sweeps and cancellation, and final integration via the existing AdCache interface. Each phase delivers verifiable capabilities and addresses critical pitfalls (Activity context leaks, race conditions, coroutine cleanup failures) before building on top.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Foundation (Cache Stores)** - Thread-safe storage with TTL and eviction policies
- [x] **Phase 2: Parallel Processing** - RTB/CPM processors with coroutine orchestration
- [ ] **Phase 3: Coordination Layer** - Auction flow state machine and warm start optimization
- [ ] **Phase 4: Lifecycle Management** - Periodic sweeps and cancellation handling
- [ ] **Phase 5: Entry Point & Integration** - AdCache interface implementation and SDK integration

## Phase Details

### Phase 1: Foundation (Cache Stores)

**Goal**: Implement thread-safe cache storage layer with TTL expiration, duplicate handling, and memory-aware capacity limits

**Depends on**: Nothing (first phase)

**Requirements**: CACHE-01, CACHE-02, CACHE-03, CACHE-04, CACHE-05, CACHE-06, CACHE-07, CACHE-08, CACHE-09, CACHE-10, SAFETY-01, SAFETY-02

**Success Criteria** (what must be TRUE):
  1. ReadyToShowCache stores loaded ads with thread-safe operations (concurrent put/get/remove)
  2. RtbPayloadCache stores RTB bid responses with duplicate demandId detection (higher eCPM wins)
  3. Cache entries expire after 30 minutes using monotonic time source (SystemClock.elapsedRealtime)
  4. Lazy eviction removes expired entries on access without throwing exceptions
  5. Capacity limits prevent memory exhaustion (1-3 READY_TO_SHOW, 5-10 RTB_PAYLOAD)

**Plans**: 3 plans

Plans:
- [x] 01-01-PLAN.md — Cache entry model and TTL configuration with monotonic time
- [x] 01-02-PLAN.md — ReadyToShowCache singleton for loaded ads
- [x] 01-03-PLAN.md — RtbPayloadCache singleton with atomic duplicate detection

### Phase 2: Parallel Processing

**Goal**: Implement parallel RTB and CPM processors with exactly-once callback semantics and proper coroutine cancellation

**Depends on**: Phase 1 (requires cache stores to write results)

**Requirements**: RTB-01, RTB-02, RTB-03, RTB-04, RTB-05, CPM-01, CPM-02, CPM-03, CPM-04, PARALLEL-01, PARALLEL-02, PARALLEL-03, PARALLEL-04, SAFETY-03, SAFETY-04

**Success Criteria** (what must be TRUE):
  1. RTB processor loads first RTB adUnit and saves remaining payloads to cache
  2. CPM processor loads adUnits sequentially with basic weight model (fill rate sorting)
  3. RTB and CPM processing execute in parallel with independent failure domains
  4. onAdLoaded callback fires exactly once when first ad (RTB or CPM) fills successfully
  5. Invalid RTB payloads are removed from cache on load failure
  6. All AdSource instances are destroyed in finally blocks even when coroutines are cancelled

**Plans**: 5 plans

Plans:
- [x] 02-01-PLAN.md — WeightModel singleton for CPM fill rate tracking
- [x] 02-02-PLAN.md — RtbProcessor for RTB payload loading from cache
- [x] 02-03-PLAN.md — CpmProcessor for sequential CPM waterfall loading
- [x] 02-04-PLAN.md — CallbackCoordinator and ParallelAuctionOrchestrator
- [x] 02-05-PLAN.md — Gap closure: RtbProcessor retry logic and finally block cleanup

### Phase 3: Coordination Layer

**Goal**: Orchestrate auction flow with cold/warm start detection, dynamic pricefloor, and token collection optimization

**Depends on**: Phase 1 (requires cache stores for state inspection), Phase 2 (requires processors for execution)

**Requirements**: AUCTION-01, AUCTION-02, AUCTION-03, AUCTION-04, AUCTION-05, AUCTION-06, INT-03, INT-04

**Success Criteria** (what must be TRUE):
  1. Warm start delivers immediate onAdLoaded callback (<1s) when READY_TO_SHOW cache is not empty
  2. Cold start executes full token collection, auction request, and waterfall processing
  3. Token collection skips ad networks with valid RTB_PAYLOAD cache entries
  4. Dynamic pricefloor is calculated as max(READY_TO_SHOW.maxEcpm, RTB_PAYLOAD.maxEcpm, userPricefloor)
  5. Waterfall is split into RTB group and CPM group before parallel processing
  6. Existing SDK adapters work without modifications (AdSource interface compatibility)

**Plans**: 3 plans

Plans:
- [ ] 03-01-PLAN.md — Core coordination layer with cold/warm start state machine and pricefloor calculation
- [ ] 03-02-PLAN.md — GetTokensUseCase extension with skipDemandIds parameter
- [ ] 03-03-PLAN.md — Waterfall splitting and full auction flow integration

### Phase 4: Lifecycle Management

**Goal**: Implement periodic cache sweeps and showAd-triggered cancellation for resource cleanup

**Depends on**: Phase 1 (requires cache stores for sweep operations)

**Requirements**: LIFE-03, LIFE-04, LIFE-05, LIFE-06, LIFE-07, CACHE-06 (periodic sweep component)

**Success Criteria** (what must be TRUE):
  1. Periodic sweep job runs every 5 minutes to remove expired cache entries
  2. Sweep job stops when ad instance is destroyed (no zombie background tasks)
  3. showAd() cancels ongoing CPM processing to avoid wasted network requests
  4. Cleanup code in finally blocks completes even when coroutines are cancelled (NonCancellable context)
  5. Activity context references are weak to prevent memory leaks from singleton caches

**Plans**: 5 plans

Plans:
- [x] 04-01-PLAN.md — Periodic sweep infrastructure (AdInstanceScope, PeriodicSweepJob)
- [x] 04-02-PLAN.md — Cancellation manager for showAd()-triggered cancellation
- [x] 04-03-PLAN.md — NonCancellable cleanup with CleanupCoordinator
- [x] 04-04-PLAN.md — WeakReference validation for Activity context leak prevention
- [ ] 04-05-PLAN.md — Gap closure: Wire lifecycle components into CoordinationLayer

### Phase 5: Entry Point & Integration

**Goal**: Integrate v2 cache implementation via AdCache interface and factory pattern for version selection

**Depends on**: Phase 1 (cache stores), Phase 2 (processors), Phase 3 (coordinator), Phase 4 (lifecycle)

**Requirements**: LIFE-01, LIFE-02, INT-01, INT-02, INT-05, STAT-01, STAT-02, STAT-03

**Success Criteria** (what must be TRUE):
  1. AdCacheDenisImpl implements AdCache interface with all required methods (cache, peek, pop, poll, clear)
  2. AdCacheFactory allows selection between old and v2 implementations
  3. getBest() returns ad with highest eCPM from READY_TO_SHOW cache on showAd()
  4. Shown ad is removed from READY_TO_SHOW cache after display
  5. New statistics statuses (CachedPayload, CachedReady, Expired, SkippedTokens, CancelledByShow) are sent to /v2/stats
  6. AuctionId tracking uses the winning ad's auctionId (not the most recent auction)
  7. destroyAd() does not clear application-wide caches (only instance-scoped cleanup)

**Plans**: TBD

Plans:
- [ ] TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation (Cache Stores) | 3/3 | Complete | 2026-02-05 |
| 2. Parallel Processing | 5/5 | Complete | 2026-02-05 |
| 3. Coordination Layer | 0/3 | Not started | - |
| 4. Lifecycle Management | 4/5 | Gap closure in progress | 2026-02-05 |
| 5. Entry Point & Integration | 0/TBD | Not started | - |
