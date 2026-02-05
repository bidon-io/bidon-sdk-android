# Phase 4: Lifecycle Management - Context

**Gathered:** 2026-02-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement periodic cache sweeps and resource cleanup for the caching system. Ensures singleton caches don't leak memory or waste resources through expired entries, cancelled operations, and improper context retention. Monitoring dashboards or admin tools are separate phases.

</domain>

<decisions>
## Implementation Decisions

### Sweep Timing & Triggers
- Fixed 5-minute periodic sweep interval (roadmap default)
- Time-based only (no system event triggers like onLowMemory)
- Instance-scoped sweep jobs (each ad instance manages its own)
- Sweep job stops when ad instance is destroyed (no zombie background tasks)

### Cancellation Strategy
- Cancel all ongoing processing (RTB + CPM) when showAd() is called
- Use coroutine Job.cancel() for cancellation signaling
- Keep successfully loaded ads in cache even if auction cancelled mid-flight
- One cancel per auction (subsequent showAd() calls just pull from cache without re-cancelling)

### Context Lifecycle
- WeakReference everywhere for Activity/Context references in singleton caches
- Remove cache entry immediately when WeakReference becomes null
- WeakReference checks performed during periodic sweep (not on every cache access)

### Cleanup Guarantees
- Must complete even during cancellation:
  - AdSource destruction (release ad network resources)
  - Cache consistency (atomic put/remove operations)
  - Statistics reporting (send cancellation stats to /v2/stats)
- If cleanup fails (exception during destroy): log and continue (don't propagate)
- Parallel cleanup (launch destroy operations concurrently for speed)

### Claude's Discretion
- Exact NonCancellable context usage pattern for critical cleanup
- Adapter context validation strategy (trust vs log warnings)
- Additional critical cleanup operations beyond the three specified

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard Kotlin Coroutines patterns for lifecycle management.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-lifecycle*
*Context gathered: 2026-02-05*
