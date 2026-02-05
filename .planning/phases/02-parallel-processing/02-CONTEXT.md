# Phase 2: Parallel Processing - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement parallel RTB and CPM ad loading processors with coroutine orchestration. RTB processor loads highest-eCPM cached payload while CPM processor sequentially loads waterfall using dynamic weight model. Both branches run independently with proper failure isolation, exactly-once semantics, and resource cleanup. Cache observation (not auction callbacks) drives the integration layer.

</domain>

<decisions>
## Implementation Decisions

### RTB Payload Handling
- Load only the highest eCPM RTB payload from cache (single attempt per auction)
- Remove payload from cache immediately on load failure (invalid/network error)
- Only the successfully loaded RTB ad goes to READY_TO_SHOW cache
- Remaining RTB payloads stay in RTB_PAYLOAD cache for future auctions
- TTL handles expiration for unused payloads

### CPM Weight Model
- Full weight model in Phase 2: initial sort by eCPM (high to low) + dynamic fill rate weighting
- Each demandId starts with weight = 10
- Successful fill: weight +1
- No fill: weight -1
- Weight storage: in-memory only (resets on app restart)
- Final sort order: Combined score from (eCPM × weight factor)

### Race Completion Semantics
- Cache observation drives callbacks, NOT auction lifecycle
- Both RTB and CPM always run to completion in background (never stop early)
- When cache reaches capacity (1-3 READY_TO_SHOW), continue loading and evict lowest eCPM
- On showAd(): Cancel ongoing auction (both RTB and CPM) ONLY if shown ad belongs to same auctionId
- Track auctionId per cached ad to enable auction-specific cancellation
- Critical distinction: Cache logic is decoupled from auction callbacks

### Failure Scenarios
- RTB failure (no payloads or load error): Log failure, rely on CPM silently
- CPM waterfall exhaustion (all networks fail): Log exhaustion for analytics
- Log each individual CPM network failure during sequential loading (verbose debugging)
- Both RTB and CPM fail: Fire onAdLoadFailed callback ONLY if cache was empty before auction
- If cache had existing ads, both-fail scenario is silent (cache still has value)

### Claude's Discretion
- Weight model bounds (recommend 1-20 range to prevent extreme values)
- eCPM × weight formula details (multiplicative vs additive scoring)
- Coroutine scope management and structured concurrency patterns
- AdSource destroy timing in finally blocks

</decisions>

<specifics>
## Specific Ideas

- "Cancel if same auction ad" — showAd() cancellation is conditional on auctionId match
- Weight model: "default 10 fill +1 no fill -1 or something (think about)" — iterative learning per demandId
- Cache transitions (empty → non-empty) trigger external observation, not auction completion

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-parallel-processing*
*Context gathered: 2026-02-05*
