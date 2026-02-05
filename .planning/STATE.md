# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-05)

**Core value:** Быстрый onAdLoaded callback (<1-3 сек вместо 3-15 сек) при сохранении или повышении revenue за счет умного переиспользования кэшированных bid responses и параллельной загрузки рекламы
**Current focus:** Phase 2 - Parallel Processing

## Current Position

Phase: 2 of 5 (Parallel Processing)
Plan: 1 of 4 in current phase
Status: In progress
Last activity: 2026-02-05 — Completed 02-01-PLAN.md (WeightModel)

Progress: [█████░░░░░] 57%

## Performance Metrics

**Velocity:**
- Total plans completed: 4
- Average duration: 1.3 min
- Total execution time: 0.1 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Foundation (Cache Stores) | 3 | 4 min | 1.3 min |
| 2. Parallel Processing | 1 | 1 min | 1.0 min |

**Recent Trend:**
- Last 5 plans: 01-01 (1 min), 01-02 (2 min), 01-03 (1 min), 02-01 (1 min)
- Trend: Consistent fast execution (1-2 min per plan)

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

### Pending Todos

None yet.

### Blockers/Concerns

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

## Session Continuity

Last session: 2026-02-05 14:53:09 UTC
Stopped at: Completed 02-01-PLAN.md (WeightModel implementation)
Resume file: None
