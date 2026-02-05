# Phase 5: Entry Point & Integration - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Integrate the v2 cache implementation into the SDK by implementing the AdCache interface, connecting all Phase 1-4 components (cache stores, processors, coordination layer, lifecycle management) to the existing SDK entry points. Enable version selection through factory pattern with hardcoded switch for initial deployment.

</domain>

<decisions>
## Implementation Decisions

### Factory selection behavior
- Factory has switch between old and v2 implementations (both coexist)
- Version selection uses existing AdCacheVersion.fromInt() mechanism via demandAd extras
- No new switch logic needed - existing mechanism works
- Structure allows future config-based selection without refactoring

### pop() as primary method (no separate getBest)
- pop() returns ad with highest eCPM from READY_TO_SHOW cache
- Automatic removal on pop (pop semantics, not peek)
- Skip expired ads automatically - return next best valid ad if winner expired
- Non-blocking operation - returns immediately from current cache state (doesn't wait for ongoing auctions)

### AdCache method mapping
- peek() - returns best ad WITHOUT removal (peek semantics)
- pop() - returns best ad WITH removal (highest eCPM selection + automatic removal)
- poll() - suspending version of pop() that throws NoSuchElementException if cache empty (matches V1 behavior pattern, adapted for V2 cache structure)
- clear() - NO-OP (cache clears only through expired mechanism, not manual)
- cache() - identical signature and flow to existing implementation

### Warm start and auction cancellation
- When warm start serves immediately, cancel the cold start auction
- Expired ads are skipped - treat as cold start if all cached ads expired
- destroyAd() only stops lifecycle jobs (sweep, auctions), doesn't clear application-wide caches per LIFE-03

### Statistics tracking
- NO new stats events - work with existing event types only
- AuctionId tracking uses original auctionId from when ad was loaded (entry.auctionId)
- Expired ads: log only, no events (silent eviction except logs)

### Claude's Discretion
- Log level for cache hits (warm start serving) - choose between INFO/DEBUG based on production visibility needs
- Exact structure of factory switch implementation
- Error handling for edge cases (concurrent pop() calls, cache corruption)
- Internal thread-safety mechanisms for pop() operation

</decisions>

<specifics>
## Specific Ideas

- Factory switch location: AdCacheFactory companion object with `private const val USE_V2 = true`
- Phase 1-4 components are complete and ready to wire: ReadyToShowCache, RtbPayloadCache, CoordinationLayer, LifecycleManager
- Existing AdCache interface must remain unchanged (backward compatibility)
- pop() implements highest eCPM selection logic directly (no separate getBest method needed)

</specifics>

<deferred>
## Deferred Ideas

None - discussion stayed within phase scope

</deferred>

---

*Phase: 05-entry-point-integration*
*Context gathered: 2026-02-05*
