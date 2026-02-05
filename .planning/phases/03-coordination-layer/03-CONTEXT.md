# Phase 3: Coordination Layer - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Orchestrate auction flow with cold/warm start detection, dynamic pricefloor calculation, and token collection optimization. This phase sits between parallel processors (Phase 2) and lifecycle management (Phase 4), acting as the "brain" that decides whether to serve a cached ad immediately (warm start <1s) or run a full auction (cold start).

</domain>

<decisions>
## Implementation Decisions

### Warm vs Cold Start Decision
- Always serve immediately if READY_TO_SHOW cache is not empty (prioritize speed over eCPM threshold)
- No background refresh on warm start (serve cached ad only, no async auction to replenish)
- RTB_PAYLOAD cache counts as warm start (skip token collection for cached adapters, faster auction)
- Cache state changes during processing are acceptable (no re-validation before callback)

### Token Collection Skipping
- Skip token collection for ALL adapters with valid RTB_PAYLOAD cache entries (any entry within 30min TTL is fresh enough)
- Log skipped tokens in stats (no separate SkippedTokens event sent to /v2/stats)
- Token collection failure cannot happen for adapters with cached payloads (skip means skip - no collectToken call)
- Trust cache state from auction start (no validation against waterfall response)

### Dynamic Pricefloor Calculation
- Calculate pricefloor BEFORE token collection (once at auction start based on cache state)
- Use user pricefloor if both caches are empty (respect publisher's minimum eCPM even on cold start)
- Apply safety margin: pricefloor = 0.9 * max(READY_TO_SHOW.maxEcpm, RTB_PAYLOAD.maxEcpm, userPricefloor)
- Merge into existing pricefloor request parameter (backend sees single pricefloor value)

### Waterfall Splitting Strategy
- Determine RTB vs CPM by checking `Adapter.Bidding` interface (reference: GetTokensUseCaseImpl.filterIsInstance<Adapter.Bidding>)
- If adapter implements Adapter.Bidding, treat as RTB only (ignore CPM config even if present)
- Allow re-sorting within RTB and CPM groups (coordination layer can optimize by eCPM, weight, etc.)
- Pass filtered lists to processors (RtbProcessor gets RTB adapters only, CpmProcessor gets CPM adapters only)

### Claude's Discretion
- Cache race condition handling (decide between no-op vs re-validation)
- Exact re-sorting algorithm for RTB and CPM groups
- Statistics event structure for token skipping
- Error handling for pricefloor calculation edge cases

</decisions>

<specifics>
## Specific Ideas

- Token skipping logic should mirror GetTokensUseCaseImpl's `.filterIsInstance<Adapter.Bidding>()` pattern for consistency
- Safety margin of 0.9 (90%) allows slightly better bids to compete while still protecting cached value
- No background work on warm start keeps implementation simple and battery-friendly

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-coordination-layer*
*Context gathered: 2026-02-05*
