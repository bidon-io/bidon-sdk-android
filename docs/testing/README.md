# Ad Caching v2 — Testing Documentation

> **Version:** 1.0
> **Date:** 2026-02-05
> **Test Framework:** Manual Testing via claude-in-mobile + Android Emulator

## Обзор

Эта папка содержит полную документацию по тестированию ad caching v2 системы для Bidon Android SDK.

**Всего:** 121 тест-кейс, покрывающих функциональность, edge cases, lifecycle, performance, и callbacks.

---

## Документы

### 1. [TEST_CHECKLIST.md](./TEST_CHECKLIST.md) — Основной чеклист
**Статус:** ✅ Complete
**Назначение:** Главный документ для тестирования

Структурированный чеклист для выполнения тестов с checkboxes, метриками, и полями для записи результатов.

**Содержит:**
- Pre-testing setup инструкции
- 90 тест-кейсов с checkboxes
- Performance metrics таблица
- Issue tracking template
- Sign-off раздел

**Использование:**
```bash
# Распечатать или использовать как tracking sheet
# Отмечать ✓ по мере выполнения тестов
# Записывать результаты и notes
```

---

### 2. [TEST_SCENARIOS_FUNCTIONAL.md](./TEST_SCENARIOS_FUNCTIONAL.md) — Функциональные тесты
**Статус:** ✅ Complete
**Тест-кейсов:** 25

Покрывает основную функциональность ad caching системы.

**Разделы:**
1. **Cold Start (4 tests)** — Первый аукцион без кэша
2. **Warm Start (5 tests)** — Повторный аукцион с кэшем ⭐ MAIN FEATURE
3. **showAd() (5 tests)** — Показ рекламы из кэша
4. **RTB Processing (4 tests)** — Обработка RTB waterfall
5. **CPM Processing (3 tests)** — Обработка CPM waterfall

**Приоритет:** 🔴 HIGH (64%), 🟡 MEDIUM (32%), 🟢 LOW (4%)

**Ключевые тесты:**
- TC-COLD-001: Pure cold start flow
- TC-WARM-001: Немедленный onAdLoaded (<1s) ⭐
- TC-WARM-002: Dynamic pricefloor calculation
- TC-SHOW-001: getBest() logic
- TC-RTB-001: RTB payload caching

---

### 3. [TEST_SCENARIOS_EDGE_CASES.md](./TEST_SCENARIOS_EDGE_CASES.md) — Граничные случаи
**Статус:** ✅ Complete
**Тест-кейсов:** 26

Проверяет обработку ошибок, race conditions, и нестандартные сценарии.

**Разделы:**
1. **Race Conditions (5 tests)** — Concurrent calls, duplicates
2. **TTL & Expiration (4 tests)** — Expired entries, periodic sweep
3. **Network Errors (4 tests)** — Timeouts, 500 errors, invalid JSON
4. **Adapter Failures (3 tests)** — All fail, crash handling, null safety
5. **Memory Management (3 tests)** — Activity leaks, low memory
6. **Edge Cases (5 tests)** — Empty waterfall, RTB-only, CPM-only

**Приоритет:** 🔴 HIGH (50%), 🟡 MEDIUM (38%), 🟢 LOW (12%)

**Ключевые тесты:**
- TC-RACE-001: Concurrent loadAd() protection
- TC-RACE-004-005: Duplicate demandId handling
- TC-TTL-001: Expired ad removal
- TC-TTL-003: Periodic sweep execution
- TC-ADAPTER-001-002: Graceful degradation

---

### 4. [TEST_SCENARIOS_LIFECYCLE.md](./TEST_SCENARIOS_LIFECYCLE.md) — Lifecycle управление
**Статус:** ✅ Complete
**Тест-кейсов:** 20

Проверяет lifecycle management, memory management, и cleanup процессы.

**Разделы:**
1. **Periodic Sweep (4 tests)** — Фоновая очистка expired entries
2. **WeakContextValidator (3 tests)** — Memory leak prevention ⭐ CRITICAL
3. **Cancellation (3 tests)** — Auction cancellation при showAd()/destroyAd()
4. **Cleanup (4 tests)** — AdSource.destroy() вызовы
5. **Coroutine Scope (3 tests)** — Scope management, SupervisorJob

**Приоритет:** 🔴 HIGH (75%), 🟡 MEDIUM (25%)

**Ключевые тесты:**
- TC-SWEEP-001-002: Periodic sweep working correctly
- TC-WEAK-001-002: No memory leaks ⭐ PRODUCTION BLOCKER
- TC-CANCEL-001-002: Proper cancellation
- TC-CLEANUP-001-004: Resource cleanup
- TC-SCOPE-002-003: Failure isolation, cleanup не cancellable

---

### 5. [TEST_SCENARIOS_PERFORMANCE.md](./TEST_SCENARIOS_PERFORMANCE.md) — Производительность
**Статус:** ✅ Complete
**Тест-кейсов:** 19

Измеряет latency, throughput, memory usage, и stress testing.

**Разделы:**
1. **Latency Benchmarks (5 tests)** — Cold start, warm start, overhead
2. **Stress Testing (4 tests)** — 100 loads, rapid cycles, 24h simulation
3. **Memory Benchmarks (2 tests)** — Footprint, pressure handling
4. **Network Performance (2 tests)** — Parallel speedup, bandwidth
5. **Battery Impact (1 test)** — Long-term battery drain

**Приоритет:** 🔴 HIGH (37%), 🟡 MEDIUM (32%), 🟢 LOW (31%)

**Performance Targets:**
- Cold Start: 5-7s
- Warm Start: <1s (preferably <500ms) ⭐
- Token Collection Improvement: 30-50%
- Memory Overhead: <5MB
- Parallel Speedup: 30-50%
- Success Rate: >95%
- Crash Rate: 0

**Ключевые тесты:**
- TC-PERF-001: Cold start latency measurement
- TC-PERF-002: Warm start latency ⭐ MAIN FEATURE
- TC-STRESS-001-002: Stability under load
- TC-NET-PERF-001: Parallel processing speedup

---

### 6. [TEST_SCENARIOS_CALLBACKS.md](./TEST_SCENARIOS_CALLBACKS.md) — Колбэки пользователя
**Статус:** ✅ Complete
**Тест-кейсов:** 31

Проверяет все callback методы, которые возвращаются пользователю SDK. Гарантирует что все события жизненного цикла рекламы корректно передаются в user callbacks.

**Разделы:**
1. **Show & Display Callbacks (3 tests)** — onAdShown при показе
2. **Close Callbacks (4 tests)** — onAdClosed при закрытии ⭐ CRITICAL
3. **Click Callbacks (3 tests)** — onAdClicked при клике
4. **Expired Callbacks (3 tests)** — onAdExpired при expiration
5. **Revenue Callbacks (3 tests)** — onRevenuePaid revenue tracking
6. **Rewarded Callbacks (3 tests)** — onUserRewarded для rewarded ads
7. **Callback Order & Timing (3 tests)** — Последовательность и thread safety
8. **Edge Cases (3 tests)** — Error handling, null listener, exceptions

**Приоритет:** 🔴 HIGH (58%), 🟡 MEDIUM (39%), 🟢 LOW (3%)

**Все колбэки:**
- `onAdLoaded` / `onAdLoadFailed` — загрузка (покрыто в FUNCTIONAL)
- `onAdShown` / `onAdShowFailed` — показ
- `onAdClosed` — закрытие ⭐
- `onAdClicked` — клик
- `onAdExpired` — expiration
- `onRevenuePaid` — revenue
- `onUserRewarded` — reward (только rewarded ads)

**Ключевые тесты:**
- TC-CB-CLOSE-001-002: onAdClosed при закрытии ⭐ CRITICAL
- TC-CB-SHOW-001: onAdShown при показе
- TC-CB-CLICK-001: onAdClicked при клике
- TC-CB-ORDER-001-003: Правильный порядок и thread safety
- TC-CB-EDGE-002-003: Error handling
- TC-CB-REWARD-001-002: onUserRewarded для rewarded ads

---

## Quick Start

### Тестирование через claude-in-mobile (рекомендуется)

```bash
# 1. Setup MCP server
claude mcp add --transport stdio mobile -- npx -y claude-in-mobile

# 2. Запустить Android Emulator (API 26+)
emulator -avd Pixel_5_API_33

# 3. Установить claude-in-mobile app
# (See https://github.com/AlexGladkov/claude-in-mobile)

# 4. Configure Bidon SDK v2
# В коде SDK:
# AdCacheFactory.setVersion(AdCacheVersion.V2_DENIS)

# 5. Open checklist
open docs/testing/TEST_CHECKLIST.md

# 6. Execute tests following checklist
# Placement key: 1O16GQT380000
```

### Logcat Monitoring

```bash
# Основные логи
adb logcat -s BidonCache:D

# Specific filters
adb logcat -s BidonCache:D | grep "CoordinationLayer"  # Coordination
adb logcat -s BidonCache:D | grep "READY_TO_SHOW"      # Cache ops
adb logcat -s BidonCache:D | grep "PeriodicSweepJob"   # Lifecycle
```

---

## Test Execution Plan

### Phase 1: Smoke Tests (1-2 hours)
**Goal:** Verify основная функциональность работает

- [ ] TC-COLD-001: Pure cold start
- [ ] TC-WARM-001: Warm start <1s ⭐
- [ ] TC-SHOW-001: showAd() works
- [ ] TC-PERF-001: Cold start latency target
- [ ] TC-PERF-002: Warm start latency target ⭐

**Success Criteria:** All 5 tests pass

---

### Phase 2: Core Functionality (3-4 hours)
**Goal:** Full functional coverage

- [ ] All Cold Start tests (4)
- [ ] All Warm Start tests (5)
- [ ] All showAd() tests (5)
- [ ] All RTB Processing tests (4)
- [ ] All CPM Processing tests (3)

**Success Criteria:** >95% pass rate

---

### Phase 3: Edge Cases & Lifecycle (2-3 hours)
**Goal:** Stability и correctness

- [ ] All Race Conditions tests (5)
- [ ] All TTL & Expiration tests (4)
- [ ] All Periodic Sweep tests (4)
- [ ] All WeakContextValidator tests (3) ⭐ CRITICAL
- [ ] All Cancellation tests (3)
- [ ] All Cleanup tests (4)

**Success Criteria:** All memory leak tests pass ⭐

---

### Phase 4: Performance & Stress (2-3 hours)
**Goal:** Performance targets met

- [ ] All Latency Benchmarks (5)
- [ ] All Stress Testing (4)
- [ ] All Memory Benchmarks (2)
- [ ] All Network Performance (2)

**Success Criteria:** All targets met

---

### Phase 5: Callbacks (2-3 hours)
**Goal:** Все callback события работают корректно

- [ ] All Show & Display Callbacks (3)
- [ ] All Close Callbacks (4) ⭐ CRITICAL
- [ ] All Click Callbacks (3)
- [ ] All Expired Callbacks (3)
- [ ] All Revenue Callbacks (3)
- [ ] All Rewarded Callbacks (3)
- [ ] All Callback Order & Timing (3)
- [ ] All Edge Cases (3)

**Success Criteria:** All critical callbacks fire correctly, thread-safe

---

## Test Coverage Summary

| Category | Test Cases | Priority HIGH | Priority MEDIUM | Priority LOW |
|----------|------------|---------------|-----------------|--------------|
| Functional | 25 | 16 (64%) | 8 (32%) | 1 (4%) |
| Edge Cases | 26 | 13 (50%) | 10 (38%) | 3 (12%) |
| Lifecycle | 20 | 15 (75%) | 5 (25%) | 0 (0%) |
| Performance | 19 | 7 (37%) | 6 (32%) | 6 (31%) |
| Callbacks | 31 | 18 (58%) | 12 (39%) | 1 (3%) |
| **Total** | **121** | **69 (57%)** | **41 (34%)** | **11 (9%)** |

---

## Critical Tests (Must Pass for Production)

### Blocking Issues (Cannot Release Without)

1. **TC-WARM-001**: Warm start <1s ⭐ **MAIN FEATURE**
2. **TC-WEAK-001**: No Activity memory leaks ⭐ **PRODUCTION BLOCKER**
3. **TC-WEAK-002**: Activity rotation no leaks ⭐ **PRODUCTION BLOCKER**
4. **TC-PERF-001**: Cold start 5-7s
5. **TC-PERF-002**: Warm start <1s ⭐
6. **TC-STRESS-001**: 100 loads stable
7. **TC-STRESS-002**: Rapid cycles stable

### High Priority (Should Pass)

8. TC-RACE-001: Concurrent loadAd() protection
9. TC-RACE-004: Duplicate handling
10. TC-TTL-001: Expired ad removal
11. TC-TTL-003: Periodic sweep working
12. TC-SHOW-001: getBest() logic
13. TC-ADAPTER-001-002: Adapter failures handled
14. TC-CANCEL-001-002: Cancellation working
15. TC-CLEANUP-001-004: Proper cleanup

### Callback Critical (Must Pass)

16. **TC-CB-CLOSE-001**: onAdClosed при закрытии ⭐ **MAIN LIFECYCLE EVENT**
17. **TC-CB-CLOSE-002**: onAdClosed при back button ⭐
18. TC-CB-SHOW-001: onAdShown при показе
19. TC-CB-CLICK-001: onAdClicked при клике
20. TC-CB-ORDER-001: Правильная последовательность callbacks
21. TC-CB-ORDER-003: Thread safety (Main thread)
22. TC-CB-EDGE-002: Listener = null (no crash)
23. TC-CB-EDGE-003: User exception в callback
24. TC-CB-REWARD-001-002: onUserRewarded для rewarded ads

**Total Critical Tests:** 24 из 121 (20%)

---

## Testing Tools

### Required
- **Android Studio** — Emulator, Memory Profiler, Network Profiler
- **claude-in-mobile** — UI testing через Claude MCP
- **adb** — Logcat monitoring

### Recommended
- **LeakCanary** — Memory leak detection (install in test app)
- **Battery Historian** — Battery impact analysis
- **Charles Proxy** — Network traffic inspection

### Optional
- **Espresso** — Automated UI tests (future)
- **JUnit** — Unit tests (deferred to v2.1)

---

## Known Limitations (v1)

### Out of Scope (Explicitly Excluded)
- ❌ Fallback из кэша при failedToShow
- ❌ Win/Loss notifications при каждом showAd()
- ❌ cachedAdUnits field в /auction request
- ❌ Автоматическая замена winner ad
- ❌ Chunked parallel CPM loading (по 2)
- ❌ Advanced Weight Model с ML
- ❌ Unit tests (отложено на v2.1)

### Deferred to v2
- Adaptive TTL based on ad format
- Cache warming on app resume
- Per-placement cache configuration
- Cross-session persistent cache (disk)
- Predictive pre-caching
- Multi-tier cache (L1/L2)
- Cache analytics dashboard

---

## Test Results Template

### Test Session: _____________
**Date:** _______________
**Tester:** _______________
**Device:** Pixel 5 Emulator (API 33)
**App Version:** _______________
**SDK Version:** _______________

### Results
- **Tests Run:** _____ / 121
- **Passed:** _____ (_____ %)
- **Failed:** _____
- **Blocked:** _____

### Performance Metrics
- Cold Start: _____ s (target: 5-7s)
- Warm Start: _____ ms (target: <1000ms)
- Token Improvement: _____ % (target: 30-50%)
- Memory Overhead: _____ MB (target: <5MB)
- Success Rate: _____ % (target: >95%)

### Issues Found: _____

### Sign-off: ⬜ PASS / ⬜ FAIL / ⬜ CONDITIONAL

---

## Next Steps After Testing

### If All Tests Pass ✅
1. Generate test report
2. Update CHANGELOG.md
3. Create release candidate (RC)
4. Beta testing с реальными пользователями
5. Production release

### If Critical Tests Fail ❌
1. Log issues with details
2. Fix blocking issues
3. Re-run test suite
4. Repeat until all critical tests pass

### If Performance Targets Not Met ⚠️
1. Profile с Android Profiler
2. Identify bottlenecks
3. Optimize hotspots
4. Re-run performance tests

---

## Contact & Support

**Questions?** Open issue on GitHub or contact team leads.

**Bug Reports:** Include test case ID, logs, screenshots, and reproduction steps.

**Test Feedback:** Suggest improvements to test scenarios.

---

**Document Status:** Complete ✅
**Last Updated:** 2026-02-06
**Total Documentation:** 6 files, ~4000 lines, 121 test cases
**Ready for:** Manual Testing Execution
