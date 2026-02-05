---
phase: 04-lifecycle
plan: 01
subsystem: lifecycle-management
tags: [kotlin-coroutines, periodic-sweep, cache-cleanup, supervisorjob]

# Dependency graph
requires:
  - phase: 01-foundation
    provides: "ReadyToShowCache and RtbPayloadCache singleton stores with TTL and expiration logic"
provides:
  - "AdInstanceScope: instance-scoped CoroutineScope with SupervisorJob for ad lifecycle management"
  - "PeriodicSweepJob: coroutine-based periodic cache sweep every 5 minutes"
  - "Public sweep() API on both cache stores"
affects: [04-02-cancellation, 04-03-cleanup, integration]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Instance-scoped CoroutineScope pattern with SupervisorJob for failure isolation"
    - "while(isActive) + delay() pattern for cooperative cancellation"
    - "Periodic job lifecycle tied to ad instance destruction"

key-files:
  created:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/AdInstanceScope.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/PeriodicSweepJob.kt
  modified:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/ReadyToShowCache.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/RtbPayloadCache.kt

key-decisions:
  - "SupervisorJob ensures sweep failures don't crash ad instance or cancel sibling coroutines"
  - "First sweep runs after 5 minutes (not immediately) to avoid startup overhead"
  - "while(isActive) + delay() pattern provides cooperative cancellation when scope is destroyed"
  - "sweep() methods return count of removed entries for monitoring/telemetry"

patterns-established:
  - "Instance-scoped lifecycle: Each ad instance gets its own AdInstanceScope, cancelled on destroy"
  - "Sweep job automatically stops when ad instance destroyed (no zombie background tasks)"
  - "Public sweep() API enables external triggering by periodic jobs"

# Metrics
duration: 2min
completed: 2026-02-05
---

# Phase 4 Plan 01: Periodic Sweep Infrastructure Summary

**Coroutine-based periodic cache sweep infrastructure with SupervisorJob isolation, 5-minute intervals, and instance-scoped lifecycle management**

## Performance

- **Duration:** 1m 47s
- **Started:** 2026-02-05T17:10:53Z
- **Completed:** 2026-02-05T17:12:40Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- Created AdInstanceScope for instance-scoped coroutine lifecycle management with SupervisorJob
- Implemented PeriodicSweepJob using while(isActive) + delay() pattern for cooperative cancellation
- Added public sweep() methods to ReadyToShowCache and RtbPayloadCache
- Established foundation for periodic 5-minute cache sweeps that automatically stop with ad instance

## Task Commits

Each task was committed atomically:

1. **Task 1: Add public sweep methods to cache stores** - `4b193471` (feat)
2. **Task 2: Create AdInstanceScope and PeriodicSweepJob** - `ac1042f7` (feat)

## Files Created/Modified

**Created:**
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/AdInstanceScope.kt` - Instance-scoped CoroutineScope with SupervisorJob for failure isolation
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/PeriodicSweepJob.kt` - Periodic sweep job with 5-minute intervals, calls sweep() on both caches

**Modified:**
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/ReadyToShowCache.kt` - Added public sweep() method returning count of removed entries
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/stores/RtbPayloadCache.kt` - Added public sweep() method returning count of removed entries

## Decisions Made

1. **SupervisorJob for failure isolation:** Sweep failures don't propagate to parent scope or cancel sibling coroutines (auction processing continues even if sweep crashes)

2. **Delay-first sweep pattern:** First sweep runs AFTER 5 minutes (not immediately on start) to avoid startup overhead and give caches time to populate

3. **Cooperative cancellation via while(isActive):** Loop checks isActive on each iteration, enabling clean shutdown when AdInstanceScope is cancelled

4. **Public sweep() API:** Cache stores expose sweep() methods instead of making evictExpired() public, providing clear separation between lazy eviction (internal) and periodic sweep (external trigger)

5. **Return sweep metrics:** sweep() returns count of removed entries for telemetry and monitoring capabilities

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - implementation followed established Kotlin Coroutines patterns from research phase.

## Next Phase Readiness

**Ready for:**
- Phase 04-02: showAd() cancellation (can use AdInstanceScope for cancelling auction jobs)
- Phase 04-03: Cleanup coordination (can integrate sweep with AdSource.destroy() cleanup)
- Integration: AdCacheImpl can instantiate AdInstanceScope and PeriodicSweepJob

**Blockers:**
None

**Notes:**
- Periodic sweep infrastructure is complete but not yet integrated into ad cache implementation
- Next plan (04-02) will wire AdInstanceScope into auction cancellation flow
- Phase 04 completion will require integration task to start sweep job on AdCache initialization

---
*Phase: 04-lifecycle*
*Completed: 2026-02-05*
