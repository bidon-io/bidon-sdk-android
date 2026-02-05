# Ad Caching v2 — Performance & Load Testing Scenarios

> **Version:** 1.0
> **Date:** 2026-02-05
> **Related:** [TEST_SCENARIOS_FUNCTIONAL.md](./TEST_SCENARIOS_FUNCTIONAL.md)

## Цель документа

Тест-кейсы для проверки производительности, latency, throughput и нагрузочного тестирования.

---

## 1. Latency Benchmarks

### TC-PERF-001: Cold start latency measurement

**Цель:** Измерить время cold start.

**Target:** 5-7 секунд от loadAd() до onAdLoaded()

**Preconditions:**
- Fresh install OR очищенные кэши
- Stable network connection

**Steps:**
1. Засечь время T0 = System.currentTimeMillis()
2. Нажать "Load Ad"
3. Дождаться onAdLoaded callback
4. Засечь время T1 = System.currentTimeMillis()
5. Вычислить latency = T1 - T0

**Expected Result:**
```
Timing Breakdown:
  T=0s:      loadAd() called
  T=0-2s:    Token collection (5 RTB adapters parallel)
  T=2-3s:    POST /v2/auction/interstitial
  T=3-5s:    Parallel RTB + CPM loading
  T=5-7s:    onAdLoaded() callback ✓

Total Latency: 5000-7000ms ✓

Logs:
  [BidonCache] [TIMING] loadAd() called at T+0ms
  [BidonCache] [TIMING] Tokens collected at T+1800ms
  [BidonCache] [TIMING] Auction response received at T+3200ms
  [BidonCache] [TIMING] onAdLoaded() fired at T+6100ms

Final: 6100ms ✓ (within target)
```

**Validation:**
- ✅ Total latency 5-7 секунд
- ✅ Token collection <2 секунд
- ✅ Auction request <1 секунда
- ✅ Waterfall loading 2-4 секунды

**Priority:** 🔴 HIGH

---

### TC-PERF-002: Warm start latency measurement

**Цель:** Измерить время warm start (immediate onAdLoaded).

**Target:** <1 секунда (preferably <500ms)

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]

**Steps:**
1. Засечь время T0
2. Нажать "Load Ad"
3. Дождаться onAdLoaded
4. Засечь время T1
5. Вычислить latency = T1 - T0

**Expected Result:**
```
Timing Breakdown:
  T=0ms:     loadAd() called
  T=0-50ms:  Cache check (READY_TO_SHOW not empty)
  T=50-100ms: getBest() from cache
  T=100-300ms: onAdLoaded() callback ✓

Total Latency: 100-300ms ✓ (INSTANT!)

Logs:
  [BidonCache] [TIMING] loadAd() called at T+0ms
  [BidonCache] [TIMING] WarmStart detected at T+10ms
  [BidonCache] [TIMING] onAdLoaded() IMMEDIATE at T+120ms

Final: 120ms ✓ (target: <1000ms)
```

**Validation:**
- ✅ Total latency <1 секунда
- ✅ Preferably <500ms
- ✅ "IMMEDIATE" логи присутствуют
- ✅ Background auction продолжается параллельно

**Priority:** 🔴 HIGH (Main feature!)

---

### TC-PERF-003: Token collection optimization (warm start)

**Цель:** Измерить экономию времени при skip cached networks.

**Target:** Token collection time должно уменьшиться на 30-50%

**Preconditions:**
- RTB_PAYLOAD: [meta_an $3.00, bidmachine $2.50]
- Cold start baseline: 2 секунды token collection

**Steps:**
1. Вызвать loadAd() (warm start)
2. Измерить время token collection
3. Сравнить с baseline

**Expected Result:**
```
Cold Start (baseline):
  Token collection: 5 adapters
  Time: 2000ms

Warm Start (optimized):
  Token collection: 3 adapters (skip 2 cached)
  Time: 1200ms ✓

Improvement: 800ms saved (40% faster) ✓

Logs:
  [BidonCache] [TIMING] GetTokensUseCase: start
  [BidonCache] GetTokensUseCase: Skipped 2 adapters (cached)
  [BidonCache] [TIMING] GetTokensUseCase: completed in 1200ms
```

**Validation:**
- ✅ Token collection time <2 секунд
- ✅ Improvement 30-50% vs cold start
- ✅ Skipped adapters logged

**Priority:** 🟡 MEDIUM

---

### TC-PERF-004: Dynamic pricefloor calculation overhead

**Цель:** Измерить overhead pricefloor calculation.

**Target:** <10ms

**Preconditions:**
- READY_TO_SHOW: 3 entries
- RTB_PAYLOAD: 5 entries

**Steps:**
1. Засечь время T0
2. Вызвать PricefloorCalculator.calculate()
3. Засечь время T1
4. Вычислить overhead = T1 - T0

**Expected Result:**
```
Timing:
  T0 = 1000000ms
  T1 = 1000005ms
  Overhead = 5ms ✓ (<10ms target)

Logs:
  [BidonCache] [TIMING] PricefloorCalculator: start
  [BidonCache] PricefloorCalculator: READY_TO_SHOW.maxEcpm = $5.00
  [BidonCache] PricefloorCalculator: RTB_PAYLOAD.maxEcpm = $7.00
  [BidonCache] [TIMING] PricefloorCalculator: completed in 5ms
```

**Validation:**
- ✅ Overhead <10ms
- ✅ No blocking на main thread

**Priority:** 🟢 LOW

---

### TC-PERF-005: Cache operation overhead

**Цель:** Измерить overhead cache put/get operations.

**Target:** <5ms per operation

**Preconditions:**
- Empty cache

**Steps:**
1. Измерить put() operation
2. Измерить getBest() operation
3. Измерить remove() operation

**Expected Result:**
```
put() operation:
  Time: 2ms ✓

getBest() operation:
  Time: 1ms ✓

remove() operation:
  Time: 1ms ✓

Total overhead: <5ms per operation ✓

Logs:
  [BidonCache] [TIMING] ReadyToShowCache.put() in 2ms
  [BidonCache] [TIMING] ReadyToShowCache.getBest() in 1ms
  [BidonCache] [TIMING] ReadyToShowCache.remove() in 1ms
```

**Validation:**
- ✅ Each operation <5ms
- ✅ ConcurrentHashMap performance acceptable
- ✅ No blocking на main thread

**Priority:** 🟢 LOW

---

## 2. Throughput & Stress Testing

### TC-STRESS-001: 100 consecutive loadAd() calls

**Цель:** Проверить stability при high load.

**Preconditions:**
- Empty cache initially

**Steps:**
1. Loop 100 times:
   - Вызвать loadAd()
   - Дождаться onAdLoaded OR onAdLoadFailed
   - Record success/fail
2. Проверить memory usage
3. Проверить crash rate

**Expected Result:**
```
Results after 100 iterations:
  Success rate: 95-100% ✓
  Crash rate: 0% ✓
  Memory leak: 0 ✓

Memory Profile:
  Initial: 50MB
  After 100 iterations: 55MB ✓ (stable, no unbounded growth)

Logs:
  [BidonCache] Iteration 1: onAdLoaded ✓
  [BidonCache] Iteration 2: onAdLoaded ✓
  ...
  [BidonCache] Iteration 100: onAdLoaded ✓
```

**Validation:**
- ✅ Success rate >95%
- ✅ No crashes
- ✅ Memory stable (no leaks)
- ✅ Performance consistent (no degradation)

**Priority:** 🔴 HIGH

---

### TC-STRESS-002: Rapid loadAd() → showAd() cycles

**Цель:** Проверить rapid user interaction.

**Preconditions:**
- READY_TO_SHOW initially has [RTB $5.00]

**Steps:**
1. Loop 50 times:
   - loadAd() (warm start)
   - Подождать 100ms
   - showAd()
   - Подождать 100ms
2. Проверить consistency

**Expected Result:**
```
Results after 50 cycles:
  loadAd() success: 50/50 ✓
  showAd() success: 50/50 ✓
  No race conditions ✓
  No crashes ✓

Timing Consistency:
  Warm start latency: 100-300ms (consistent) ✓
  No performance degradation ✓

Logs:
  [BidonCache] Cycle 1: load(120ms) → show(OK) ✓
  [BidonCache] Cycle 2: load(150ms) → show(OK) ✓
  ...
  [BidonCache] Cycle 50: load(110ms) → show(OK) ✓
```

**Validation:**
- ✅ All cycles successful
- ✅ Performance consistent
- ✅ No memory leaks

**Priority:** 🔴 HIGH

---

### TC-STRESS-003: Multiple ad instances parallel loading

**Цель:** Проверить concurrent ad instances.

**Preconditions:**
- 5 InterstitialAd instances created

**Steps:**
1. Вызвать loadAd() на всех 5 instances одновременно
2. Дождаться всех callbacks
3. Проверить results

**Expected Result:**
```
Results:
  Instance 1: onAdLoaded ✓
  Instance 2: onAdLoaded ✓
  Instance 3: onAdLoaded ✓
  Instance 4: onAdLoaded ✓
  Instance 5: onAdLoaded ✓

Cache State:
  READY_TO_SHOW: [RTB $5, CPM $4.5, ...]  ← shared
  All instances share same cache ✓

Logs:
  [BidonCache] Instance(id=1): loadAd()
  [BidonCache] Instance(id=2): loadAd()
  [BidonCache] Instance(id=3): loadAd()
  [BidonCache] Instance(id=4): loadAd()
  [BidonCache] Instance(id=5): loadAd()
  [BidonCache] All auctions running in parallel ✓
```

**Validation:**
- ✅ All instances успешно загружаются
- ✅ Cache shared корректно
- ✅ No race conditions
- ✅ No deadlocks

**Priority:** 🟡 MEDIUM

---

### TC-STRESS-004: Long-running app (24 hour simulation)

**Цель:** Проверить long-term stability.

**Preconditions:**
- App запущен

**Steps:**
1. Loop for 24 hours:
   - Каждые 5 минут: loadAd() → showAd()
   - Periodic sweep каждые 5 минут
2. Мониторить memory usage
3. Проверить stability

**Expected Result:**
```
After 24 hours (288 load cycles):
  Success rate: >95% ✓
  Crashes: 0 ✓
  Memory leaks: 0 ✓

Memory Profile:
  Initial: 50MB
  After 6 hours: 55MB
  After 12 hours: 55MB
  After 24 hours: 55MB ✓ (stable)

Logs:
  [BidonCache] Hour 1: 12/12 cycles successful
  [BidonCache] Hour 6: 72/72 cycles successful
  [BidonCache] Hour 12: 144/144 cycles successful
  [BidonCache] Hour 24: 288/288 cycles successful ✓
```

**Validation:**
- ✅ Stable memory footprint
- ✅ No memory leaks
- ✅ High success rate maintained
- ✅ Periodic sweep working correctly

**Priority:** 🟡 MEDIUM

---

## 3. Memory Benchmarks

### TC-MEM-PERF-001: Memory footprint measurement

**Цель:** Измерить memory usage ad caching system.

**Target:** <5MB overhead

**Preconditions:**
- App запущен
- Baseline memory measured

**Steps:**
1. Measure baseline memory (M0)
2. Вызвать loadAd() x5 (fill cache)
3. Measure memory (M1)
4. Calculate overhead = M1 - M0

**Expected Result:**
```
Memory Measurement:
  M0 (baseline): 50MB
  M1 (after cache fill): 53MB
  Overhead: 3MB ✓ (<5MB target)

Cache State:
  READY_TO_SHOW: 3 entries (~1MB)
  RTB_PAYLOAD: 5 entries (~2MB)

Breakdown:
  Cache objects: 2MB
  AdSource objects: 1MB
  Total overhead: 3MB ✓
```

**Validation:**
- ✅ Total overhead <5MB
- ✅ Memory usage predictable
- ✅ No unbounded growth

**Priority:** 🟡 MEDIUM

---

### TC-MEM-PERF-002: Memory pressure handling

**Цель:** Проверить поведение при low memory.

**Preconditions:**
- Cache filled
- Trigger onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)

**Steps:**
1. Measure memory before (M0)
2. Trigger low memory callback
3. Measure memory after (M1)
4. Calculate memory freed = M0 - M1

**Expected Result:**
```
Memory Freed:
  M0 (before): 55MB
  M1 (after): 52MB
  Freed: 3MB ✓

Cache State After:
  READY_TO_SHOW: kept (critical)
  RTB_PAYLOAD: cleared ✓

Logs:
  [BidonCache] onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)
  [BidonCache] RtbPayloadCache: Clearing (freed 2MB)
  [BidonCache] ReadyToShowCache: Keeping (critical for UX)
```

**Validation:**
- ✅ Memory freed при low memory
- ✅ RTB_PAYLOAD cleared
- ✅ READY_TO_SHOW kept (priority)

**Priority:** 🟢 LOW

---

## 4. Network Performance

### TC-NET-PERF-001: Parallel RTB + CPM loading speedup

**Цель:** Измерить speedup от parallel processing.

**Target:** 30-50% faster vs sequential

**Preconditions:**
- Waterfall: [RTB $5, RTB $3, CPM $4.5, CPM $2.5, CPM $1]

**Steps:**
1. Measure sequential loading time (baseline)
2. Measure parallel loading time (v2)
3. Calculate speedup

**Expected Result:**
```
Sequential (baseline, old system):
  RTB[0]: 1s
  RTB[1]: 1s
  CPM[0]: 1s
  CPM[1]: 1s
  CPM[2]: 1s
  Total: 5s ✓

Parallel (v2):
  RTB group (async): 2s (RTB[0] + RTB[1] payload save)
  CPM group (async): 3s (CPM[0..2] sequential)
  Total: 3s ✓ (parallel max)

Speedup: 5s → 3s = 40% faster ✓

Logs:
  [BidonCache] RtbProcessor: Starting (async)
  [BidonCache] CpmProcessor: Starting (async)
  [BidonCache] RtbProcessor: Completed in 2s
  [BidonCache] CpmProcessor: Completed in 3s
  [BidonCache] Total time: 3s (40% speedup)
```

**Validation:**
- ✅ Parallel processing 30-50% faster
- ✅ RTB и CPM run concurrently
- ✅ First fill callback <3 секунд

**Priority:** 🔴 HIGH

---

### TC-NET-PERF-002: Bandwidth usage measurement

**Цель:** Измерить network traffic overhead.

**Preconditions:**
- Fresh install

**Steps:**
1. Measure network traffic for cold start
2. Measure network traffic for warm start
3. Compare

**Expected Result:**
```
Cold Start:
  Token collection: ~10KB
  Auction request: ~5KB
  Waterfall loading: ~100KB (5 ads)
  Total: ~115KB

Warm Start:
  Token collection: ~6KB (3 adapters, skip 2)
  Auction request: ~5KB
  Waterfall loading: ~60KB (3 ads)
  Total: ~71KB

Bandwidth Saved: 44KB (38% reduction) ✓

Logs:
  [BidonCache] Network traffic (cold): 115KB
  [BidonCache] Network traffic (warm): 71KB
  [BidonCache] Savings: 44KB (38%)
```

**Validation:**
- ✅ Warm start uses less bandwidth
- ✅ Token skip optimization works
- ✅ Savings 30-50%

**Priority:** 🟢 LOW

---

## 5. Battery Impact

### TC-BATTERY-001: Battery consumption measurement (24h)

**Цель:** Измерить battery impact periodic sweep.

**Preconditions:**
- App в background
- Periodic sweep enabled (5 min interval)

**Steps:**
1. Measure battery level at T0
2. Run app 24 hours в background
3. Measure battery level at T24
4. Calculate battery drain

**Expected Result:**
```
Battery Drain:
  T0: 100%
  T24: 95%
  Drain: 5% over 24 hours ✓

Breakdown:
  Periodic sweep (288 executions): 0.5%
  Other background tasks: 4.5%

Logs:
  [BidonCache] PeriodicSweepJob: 288 executions in 24h
  [BidonCache] Avg sweep time: 50ms
  [BidonCache] Battery impact: negligible (<1%)
```

**Validation:**
- ✅ Battery drain <1% от sweep
- ✅ Sweep time <100ms per execution
- ✅ No wake locks held

**Priority:** 🟢 LOW

---

## Summary

**Total Performance Tests:** 19
**Priority Distribution:**
- 🔴 HIGH: 7 (37%)
- 🟡 MEDIUM: 6 (32%)
- 🟢 LOW: 6 (31%)

**Benchmarks:**
- Cold Start Target: 5-7s
- Warm Start Target: <1s (preferably <500ms)
- Token Collection Improvement: 30-50% faster
- Memory Overhead Target: <5MB
- Parallel Processing Speedup: 30-50%

**Critical Tests:**
- TC-PERF-001: Cold start latency
- TC-PERF-002: Warm start latency (MAIN FEATURE!)
- TC-STRESS-001: 100 consecutive loads
- TC-STRESS-002: Rapid cycles
- TC-NET-PERF-001: Parallel speedup

---

**Document Status:** Complete
**Last Updated:** 2026-02-05
**Testing Tools:** Android Profiler, LeakCanary, Battery Historian, Network Profiler
