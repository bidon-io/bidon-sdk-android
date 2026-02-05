# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-05)

**Core value:** Быстрый onAdLoaded callback (<1-3 сек вместо 3-15 сек) при сохранении или повышении revenue за счет умного переиспользования кэшированных bid responses и параллельной загрузки рекламы
**Current focus:** Phase 2 - Parallel Processing

## Current Position

Phase: 2 of 5 (Parallel Processing)
Plan: 5 of 5 in current phase
Status: Phase complete — verified and passed
Last activity: 2026-02-05 — Completed gap closure plan 02-05, phase verified

Progress: [████████░░] 82%

## Performance Metrics

**Velocity:**
- Total plans completed: 8
- Average duration: 1.6 min
- Total execution time: 0.2 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Foundation (Cache Stores) | 3 | 4 min | 1.3 min |
| 2. Parallel Processing | 5 | 13 min | 2.6 min |

**Recent Trend:**
- Last 5 plans: 02-02 (4 min), 02-03 (2 min), 02-04 (4 min), 02-05 (2 min)
- Trend: Consistent fast execution (2-4 min per plan)

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Отдельный пакет .denis вместо .v2 для экспериментальной реализации (легко откатить)
- Singleton objects для кэшей (простота, thread-safety out of the box)
- Factory pattern для интеграции (легко переключаться между версиями)
- Kotlin Coroutines вместо RxJava (современный подход в Android)
- TTL 30 минут фиксированный (баланс между актуальностью и fill rate)
- Atomic compute() for duplicate detection (prevents race conditions, SAFETY-02)
- Higher eCPM always wins in duplicate scenarios (CACHE-07)
- Default capacity 10 for RTB_PAYLOAD cache (upper bound of 5-10 range)
- Weight bounds 1-20 with default 10 for predictable behavior (02-01)
- Multiplicative scoring (eCPM × weight/10) instead of additive for intuitive scaling (02-01)
- In-memory only weight storage (resets on app restart) - no persistence needed (02-01)
- Load only highest-eCPM RTB payload per auction (single attempt, not waterfall) (02-02)
- Remove payload from cache only on failure to prevent retry of broken bids (02-02)
- AdSource destroyed only on failure - success stores in cache for later show (02-02)
- Continue entire CPM waterfall (don't stop on first success) to fill ReadyToShowCache with multiple ads (02-03)
- Record fill/no-fill for every CPM attempt (builds weight model for future optimizations) (02-03)
- Sequential CPM loading (one at a time) to maintain waterfall ordering discipline (02-03)
- AtomicBoolean for exactly-once semantics (lock-free, no contention) (02-04)
- supervisorScope isolates failures between RTB and CPM branches (02-04)
- Callback fires when cache transitions empty -> non-empty (first ad cached) (02-04)
- Failure callback only fires if cache was empty AND both branches failed (02-04)
- Both branches always run to completion (no early termination) (02-04)
- loadSuccess flag pattern for conditional AdSource cleanup (prevent destroying successfully loaded ads) (02-05)
- Remove RTB payload only when load is attempted (not on early failures like adapter not found) (02-05)
- RTB retry: iterate all payloads until success or exhaustion (not single-attempt) (02-05)

### Pending Todos

None yet.

### Blockers/Concerns

**Phase 2 Verification Gaps (Closed by 02-05):**
- ✅ Truth #6: AdSource cleanup in finally block (was scattered across early returns)
- ✅ RTB-02: Retry next payload on failure (was single-attempt only)
- ✅ RTB-03: Save remaining valid payloads (was not implementing retry logic)

**Phase 1 Considerations:**
- Adapter-specific context requirements must be validated (WeakReference pattern vs ApplicationContext)
- Cache size limits (1-3 READY_TO_SHOW, 5-10 RTB_PAYLOAD) to be validated during integration testing
- Coroutine upgrade from 1.6.0 to 1.10.2 should be validated for existing SDK compatibility

**Known Critical Pitfalls (from research):**
- Activity context retained by singleton cache (WeakReference pattern required)
- Race condition between put + notify operations (Mutex.withLock for compound operations)
- Coroutine cancellation cleanup failures (NonCancellable context in finally blocks)
- TTL clock skew with System.currentTimeMillis (use SystemClock.elapsedRealtime)
- Duplicate demandId detection not atomic (ConcurrentHashMap.compute() required)

**Phase 3 Critical Dependency:**
- 🛑 ParallelAuctionOrchestrator is completely orphaned (no SDK integration)
- 🛑 RtbProcessor and CpmProcessor are unreachable until orchestrator is wired to SDK
- Phase 2 delivers zero value until Phase 3 integration completes

## Session Continuity

Last session: 2026-02-05 15:39:45 UTC
Stopped at: Completed 02-05-PLAN.md (RTB Cleanup & Retry) - Phase 2 gap closure complete
Resume file: None
