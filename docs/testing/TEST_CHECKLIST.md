# Ad Caching v2 — Testing Checklist

> **Version:** 1.0
> **Date:** 2026-02-05
> **Test App:** https://github.com/AlexGladkov/claude-in-mobile

## Цель документа

Структурированный чеклист для мануального и автоматического тестирования ad caching v2 системы на Android эмуляторе.

---

## Pre-Testing Setup

### Environment Configuration

- [ ] Android Emulator запущен (API 26+, Pixel 5)
- [ ] `claude-in-mobile` установлен и работает
- [ ] MCP сервер подключён: `claude mcp add --transport stdio mobile -- npx -y claude-in-mobile`
- [ ] Placement key `1O16GQT380000` готов для использования
- [ ] Bidon SDK v2 (с ad caching) скомпилирован и установлен
- [ ] AdCacheFactory настроен на v2: `AdCacheFactory.setVersion(AdCacheVersion.V2_DENIS)`

### Logcat Filters

```bash
# Основной фильтр
adb logcat -s BidonCache:D

# Только координация
adb logcat -s BidonCache:D | grep "CoordinationLayer"

# Только кэш операции
adb logcat -s BidonCache:D | grep -E "(READY_TO_SHOW|RTB_PAYLOAD)"

# Только lifecycle
adb logcat -s BidonCache:D | grep -E "(PeriodicSweepJob|WeakContextValidator)"
```

### Test Data Recording

```
Test Session: _______________
Date: _______________
Tester: _______________
Device: _______________
App Version: _______________
SDK Version: _______________
```

---

## 1. Functional Tests (25 test cases)

### 1.1 Cold Start (4 tests)

- [ ] **TC-COLD-001**: Первый loadAd() без кэша
  - Status: ⬜ Pass / ⬜ Fail
  - Time: 5-7s? ⬜ Yes / ⬜ No
  - Logs: "PureColdStart"? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-COLD-002**: Cold Start с user pricefloor
  - Status: ⬜ Pass / ⬜ Fail
  - Pricefloor sent? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-COLD-003**: Cold Start с медленным token collection
  - Status: ⬜ Pass / ⬜ Fail
  - Timeout handled? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-COLD-004**: Cold Start с network error
  - Status: ⬜ Pass / ⬜ Fail
  - onAdLoadFailed? ⬜ Yes / ⬜ No
  - Notes: _______________

### 1.2 Warm Start (5 tests)

- [ ] **TC-WARM-001**: Немедленный onAdLoaded из READY_TO_SHOW ⭐ **КРИТИЧНО**
  - Status: ⬜ Pass / ⬜ Fail
  - Time: <1s? ⬜ Yes / ⬜ No
  - Logs: "IMMEDIATE"? ⬜ Yes / ⬜ No
  - Background auction? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-WARM-002**: Warm Start с динамическим pricefloor
  - Status: ⬜ Pass / ⬜ Fail
  - Dynamic pricefloor calculated? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-WARM-003**: Warm Start с user pricefloor выше dynamic
  - Status: ⬜ Pass / ⬜ Fail
  - User pricefloor wins? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-WARM-004**: Warm Start с пустым waterfall ответом
  - Status: ⬜ Pass / ⬜ Fail
  - No error? ⬜ Yes / ⬜ No
  - Cache unchanged? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-WARM-005**: Token collection skip для cached networks
  - Status: ⬜ Pass / ⬜ Fail
  - Skipped adapters? ⬜ Yes / ⬜ No
  - Time saved? ⬜ Yes / ⬜ No
  - Notes: _______________

### 1.3 showAd() (5 tests)

- [ ] **TC-SHOW-001**: showAd() выбирает ad с максимальным eCPM
  - Status: ⬜ Pass / ⬜ Fail
  - Highest eCPM shown? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-SHOW-002**: showAd() без предварительного loadAd()
  - Status: ⬜ Pass / ⬜ Fail
  - Used cache? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-SHOW-003**: showAd() с пустым кэшем
  - Status: ⬜ Pass / ⬜ Fail
  - onAdShowFailed? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-SHOW-004**: showAd() отменяет ongoing auction
  - Status: ⬜ Pass / ⬜ Fail
  - Auction cancelled? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-SHOW-005**: Повторный showAd() после первого
  - Status: ⬜ Pass / ⬜ Fail
  - Order correct? ⬜ Yes / ⬜ No
  - Notes: _______________

### 1.4 RTB Processing (4 tests)

- [ ] **TC-RTB-001**: RTB[0] success, RTB[1..N] → payload cache
  - Status: ⬜ Pass / ⬜ Fail
  - Only RTB[0] loaded? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RTB-002**: RTB[0] fail, fallback на RTB[1]
  - Status: ⬜ Pass / ⬜ Fail
  - Fallback worked? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RTB-003**: Все RTB fail
  - Status: ⬜ Pass / ⬜ Fail
  - CPM continued? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RTB-004**: Invalid payload в RTB_PAYLOAD cache
  - Status: ⬜ Pass / ⬜ Fail
  - Payload removed? ⬜ Yes / ⬜ No
  - Notes: _______________

### 1.5 CPM Processing (3 tests)

- [ ] **TC-CPM-001**: Последовательная загрузка CPM
  - Status: ⬜ Pass / ⬜ Fail
  - Sequential order? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CPM-002**: CPM fail → skip, продолжить следующий
  - Status: ⬜ Pass / ⬜ Fail
  - Skip worked? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CPM-003**: Weight Model сортировка
  - Status: ⬜ Pass / ⬜ Fail
  - Sorting applied? ⬜ Yes / ⬜ No
  - Notes: _______________

---

## 2. Edge Cases (26 test cases)

### 2.1 Race Conditions (5 tests)

- [ ] **TC-RACE-001**: Concurrent loadAd() calls ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Second blocked? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RACE-002**: loadAd() + showAd() одновременно
  - Status: ⬜ Pass / ⬜ Fail
  - No deadlock? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RACE-003**: Multiple showAd() rapid fire
  - Status: ⬜ Pass / ⬜ Fail
  - Only one shown? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RACE-004**: Duplicate demandId (higher eCPM) ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Replaced? ⬜ Yes / ⬜ No
  - destroy() called? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-RACE-005**: Duplicate demandId (lower eCPM)
  - Status: ⬜ Pass / ⬜ Fail
  - Kept old? ⬜ Yes / ⬜ No
  - Notes: _______________

### 2.2 TTL & Expiration (4 tests)

- [ ] **TC-TTL-001**: Expired ad в READY_TO_SHOW при getBest() ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Removed? ⬜ Yes / ⬜ No
  - destroy() called? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-TTL-002**: Expired RTB_PAYLOAD при warm start
  - Status: ⬜ Pass / ⬜ Fail
  - Payload removed? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-TTL-003**: Periodic sweep очистка ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Sweep executed? ⬜ Yes / ⬜ No
  - destroy() called? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-TTL-004**: Mixed expired + valid entries
  - Status: ⬜ Pass / ⬜ Fail
  - Partial cleanup? ⬜ Yes / ⬜ No
  - Notes: _______________

### 2.3 Network Errors (4 tests)

- [ ] **TC-NET-001**: Network timeout при token collection
- [ ] **TC-NET-002**: Backend 500 error
- [ ] **TC-NET-003**: Backend возвращает invalid JSON
- [ ] **TC-NET-004**: Slow network (high latency)

### 2.4 Adapter Failures (3 tests)

- [ ] **TC-ADAPTER-001**: Все адаптеры fail (no fill) ⭐
- [ ] **TC-ADAPTER-002**: Adapter crash при load() ⭐
- [ ] **TC-ADAPTER-003**: Adapter возвращает null AdSource

### 2.5 Memory Management (3 tests)

- [ ] **TC-MEM-001**: Activity destroyed во время auction ⭐
- [ ] **TC-MEM-002**: Большое количество ads в READY_TO_SHOW
- [ ] **TC-MEM-003**: Memory warning от OS

### 2.6 Edge Cases (5 tests)

- [ ] **TC-EDGE-001**: Empty waterfall от backend
- [ ] **TC-EDGE-002**: Только RTB в waterfall (no CPM)
- [ ] **TC-EDGE-003**: Только CPM в waterfall (no RTB)
- [ ] **TC-EDGE-004**: Ad с eCPM = 0
- [ ] **TC-EDGE-005**: destroyAd() во время auction ⭐

---

## 3. Lifecycle Tests (20 test cases)

### 3.1 Periodic Sweep (4 tests)

- [ ] **TC-SWEEP-001**: Periodic sweep запуск и выполнение ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Executed at T+5min? ⬜ Yes / ⬜ No
  - destroy() called? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-SWEEP-002**: Sweep не трогает valid entries ⭐
- [ ] **TC-SWEEP-003**: Sweep останавливается при destroyAd()
- [ ] **TC-SWEEP-004**: Multiple ad instances с отдельными sweep jobs

### 3.2 WeakContextValidator (3 tests)

- [ ] **TC-WEAK-001**: Activity destroyed, context не leaked ⭐ **КРИТИЧНО**
  - Status: ⬜ Pass / ⬜ Fail
  - LeakCanary clean? ⬜ Yes / ⬜ No
  - Memory Profiler clean? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-WEAK-002**: Multiple Activity rotations ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Only 1 Activity? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-WEAK-003**: Sweep удаляет ads с leaked context

### 3.3 Cancellation (3 tests)

- [ ] **TC-CANCEL-001**: showAd() отменяет ongoing CPM ⭐
- [ ] **TC-CANCEL-002**: destroyAd() отменяет auction ⭐
- [ ] **TC-CANCEL-003**: Multiple auction cancellation

### 3.4 Cleanup (4 tests)

- [ ] **TC-CLEANUP-001**: destroy() для expired ads ⭐
- [ ] **TC-CLEANUP-002**: destroy() при duplicate replacement ⭐
- [ ] **TC-CLEANUP-003**: destroy() после show ⭐
- [ ] **TC-CLEANUP-004**: destroyAd() не очищает кэши ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Caches unchanged? ⬜ Yes / ⬜ No
  - Notes: _______________

### 3.5 Coroutine Scope (3 tests)

- [ ] **TC-SCOPE-001**: AdInstanceScope создаётся при initialize
- [ ] **TC-SCOPE-002**: SupervisorJob изолирует failures ⭐
- [ ] **TC-SCOPE-003**: NonCancellable для cleanup ⭐

---

## 4. Performance Tests (19 test cases)

### 4.1 Latency Benchmarks (5 tests)

- [ ] **TC-PERF-001**: Cold start latency ⭐ **КРИТИЧНО**
  - Status: ⬜ Pass / ⬜ Fail
  - Time: _____ ms (target: 5000-7000ms)
  - Within target? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-PERF-002**: Warm start latency ⭐ **КРИТИЧНО** (Main Feature!)
  - Status: ⬜ Pass / ⬜ Fail
  - Time: _____ ms (target: <1000ms, prefer <500ms)
  - Within target? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-PERF-003**: Token collection optimization (warm)
  - Status: ⬜ Pass / ⬜ Fail
  - Improvement: _____ % (target: 30-50%)
  - Notes: _______________

- [ ] **TC-PERF-004**: Dynamic pricefloor overhead
- [ ] **TC-PERF-005**: Cache operation overhead

### 4.2 Stress Testing (4 tests)

- [ ] **TC-STRESS-001**: 100 consecutive loadAd() ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Success rate: _____ % (target: >95%)
  - Crashes: _____ (target: 0)
  - Memory stable? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-STRESS-002**: Rapid loadAd() → showAd() cycles ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - All successful? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-STRESS-003**: Multiple ad instances parallel
- [ ] **TC-STRESS-004**: Long-running app (24h simulation)

### 4.3 Memory (2 tests)

- [ ] **TC-MEM-PERF-001**: Memory footprint
  - Status: ⬜ Pass / ⬜ Fail
  - Overhead: _____ MB (target: <5MB)
  - Notes: _______________

- [ ] **TC-MEM-PERF-002**: Memory pressure handling

### 4.4 Network (2 tests)

- [ ] **TC-NET-PERF-001**: Parallel loading speedup ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Speedup: _____ % (target: 30-50%)
  - Notes: _______________

- [ ] **TC-NET-PERF-002**: Bandwidth usage

### 4.5 Battery (1 test)

- [ ] **TC-BATTERY-001**: Battery consumption (24h)

---

## 5. Callback Tests (31 test cases)

> **Related Document:** [TEST_SCENARIOS_CALLBACKS.md](./TEST_SCENARIOS_CALLBACKS.md)

### 5.1 Show & Display Callbacks (3 tests)

- [ ] **TC-CB-SHOW-001**: onAdShown вызывается при успешном показе ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Callback fired? ⬜ Yes / ⬜ No
  - Timing correct? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-SHOW-002**: onAdShown НЕ вызывается при show failure
  - Status: ⬜ Pass / ⬜ Fail
  - Only onAdShowFailed? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-SHOW-003**: onAdShown timing (после фактического показа)
  - Status: ⬜ Pass / ⬜ Fail
  - After render? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.2 Close Callbacks (4 tests)

- [ ] **TC-CB-CLOSE-001**: onAdClosed вызывается при закрытии ⭐ **КРИТИЧНО**
  - Status: ⬜ Pass / ⬜ Fail
  - Callback fired? ⬜ Yes / ⬜ No
  - Only once? ⬜ Yes / ⬜ No
  - Cleanup done? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-CLOSE-002**: onAdClosed при back button ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Back button works? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-CLOSE-003**: onAdClosed НЕ вызывается без show
  - Status: ⬜ Pass / ⬜ Fail
  - No callback? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-CLOSE-004**: Multiple onAdClosed (не должно происходить)
  - Status: ⬜ Pass / ⬜ Fail
  - Only once? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.3 Click Callbacks (3 tests)

- [ ] **TC-CB-CLICK-001**: onAdClicked вызывается при клике ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Callback fired? ⬜ Yes / ⬜ No
  - sendClickImpression? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-CLICK-002**: onAdClicked может быть несколько раз
  - Status: ⬜ Pass / ⬜ Fail
  - Multiple clicks tracked? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-CLICK-003**: onAdClicked НЕ вызывается без клика
  - Status: ⬜ Pass / ⬜ Fail
  - No callback? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.4 Expired Callbacks (3 tests)

- [ ] **TC-CB-EXPIRE-001**: onAdExpired при TTL expiration
  - Status: ⬜ Pass / ⬜ Fail
  - Callback fired? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-EXPIRE-002**: onAdExpired при show expired ad
  - Status: ⬜ Pass / ⬜ Fail
  - onAdShowFailed? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-EXPIRE-003**: onAdExpired НЕ для shown ads
  - Status: ⬜ Pass / ⬜ Fail
  - No callback? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.5 Revenue Callbacks (3 tests)

- [ ] **TC-CB-REVENUE-001**: onRevenuePaid при успешном показе
  - Status: ⬜ Pass / ⬜ Fail
  - Callback fired? ⬜ Yes / ⬜ No
  - AdValue correct? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-REVENUE-002**: onRevenuePaid опциональный
  - Status: ⬜ Pass / ⬜ Fail
  - Works without? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-REVENUE-003**: Different precision types
  - Status: ⬜ Pass / ⬜ Fail
  - All types work? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.6 Rewarded Callbacks (3 tests)

- [ ] **TC-CB-REWARD-001**: onUserRewarded для rewarded ads ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Callback fired? ⬜ Yes / ⬜ No
  - Reward data correct? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-REWARD-002**: onUserRewarded НЕ при раннем закрытии ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - No callback? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-REWARD-003**: onUserRewarded только для RewardedAd
  - Status: ⬜ Pass / ⬜ Fail
  - Type safety? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.7 Callback Order & Timing (3 tests)

- [ ] **TC-CB-ORDER-001**: Правильная последовательность callbacks ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Order correct? ⬜ Yes / ⬜ No
  - No missing? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-ORDER-002**: Callbacks при failure scenarios ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - Failures handled? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-ORDER-003**: Thread safety (Main thread) ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - All Main thread? ⬜ Yes / ⬜ No
  - Notes: _______________

### 5.8 Edge Cases (3 tests)

- [ ] **TC-CB-EDGE-001**: destroyAd() во время показа
  - Status: ⬜ Pass / ⬜ Fail
  - No crash? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-EDGE-002**: Listener = null (no crash) ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - No NPE? ⬜ Yes / ⬜ No
  - Notes: _______________

- [ ] **TC-CB-EDGE-003**: User exception в callback ⭐
  - Status: ⬜ Pass / ⬜ Fail
  - SDK continues? ⬜ Yes / ⬜ No
  - Notes: _______________

---

## 6. Integration Tests

### 5.1 AdCacheFactory

- [ ] Factory pattern переключение v1 ↔ v2
- [ ] Version selection через config
- [ ] Fallback на v1 при ошибках

### 5.2 Existing Components Compatibility

- [ ] GetTokensUseCase integration (skipDemandIds)
- [ ] GetAuctionRequestUseCase integration (dynamic pricefloor)
- [ ] AdSource интерфейс compatibility (no changes required)
- [ ] AuctionResolver integration
- [ ] Stats tracking (новые статусы: CachedPayload, etc.)

### 6.3 Backend Integration

- [ ] Dynamic pricefloor accepted by /v2/auction
- [ ] skipDemandIds filters tokens correctly
- [ ] New statistics statuses accepted by /v2/stats
- [ ] AuctionId tracking корректный

---

## Success Criteria Summary

### Must Pass (Blocking Issues)

All tests marked with ⭐ MUST pass before production release.

**Critical Functional:**
- [ ] TC-WARM-001: Warm start <1s (MAIN FEATURE!)
- [ ] TC-SHOW-001: getBest() logic
- [ ] TC-RACE-001: Concurrent loadAd() protection
- [ ] TC-RACE-004: Duplicate handling
- [ ] TC-ADAPTER-001-002: Adapter failure handling

**Critical Lifecycle:**
- [ ] TC-WEAK-001-002: No memory leaks (PRODUCTION BLOCKER!)
- [ ] TC-SWEEP-001-002: Periodic sweep working
- [ ] TC-CANCEL-001-002: Cancellation working
- [ ] TC-CLEANUP-001-004: Proper cleanup

**Critical Performance:**
- [ ] TC-PERF-001-002: Latency targets met
- [ ] TC-STRESS-001-002: Stability under load
- [ ] TC-NET-PERF-001: Parallel speedup

**Critical Callbacks:**
- [ ] TC-CB-CLOSE-001-002: onAdClosed при закрытии (ОСНОВНОЙ LIFECYCLE!)
- [ ] TC-CB-SHOW-001: onAdShown при показе
- [ ] TC-CB-CLICK-001: onAdClicked при клике
- [ ] TC-CB-ORDER-001-003: Правильный порядок и thread safety
- [ ] TC-CB-EDGE-002-003: Error handling (null listener, exceptions)
- [ ] TC-CB-REWARD-001-002: onUserRewarded для rewarded ads

### Performance Targets

| Metric | Target | Measured | Pass? |
|--------|--------|----------|-------|
| Cold Start Latency | 5-7s | _____ s | ⬜ |
| Warm Start Latency | <1s | _____ ms | ⬜ |
| Token Collection Improvement | 30-50% | _____ % | ⬜ |
| Memory Overhead | <5MB | _____ MB | ⬜ |
| Parallel Speedup | 30-50% | _____ % | ⬜ |
| Success Rate (100 loads) | >95% | _____ % | ⬜ |
| Crash Rate | 0 | _____ | ⬜ |

---

## Test Execution Log

### Session 1
- **Date:** _______________
- **Duration:** _______________
- **Tests Run:** _____ / 121
- **Passed:** _____
- **Failed:** _____
- **Blocked:** _____
- **Notes:** _______________

### Session 2
- **Date:** _______________
- **Duration:** _______________
- **Tests Run:** _____ / 121
- **Passed:** _____
- **Failed:** _____
- **Blocked:** _____
- **Notes:** _______________

---

## Issues Found

### Issue #1
- **Test Case:** _______________
- **Severity:** ⬜ Blocker / ⬜ Critical / ⬜ Major / ⬜ Minor
- **Description:** _______________
- **Steps to Reproduce:** _______________
- **Expected:** _______________
- **Actual:** _______________
- **Logs:** _______________
- **Status:** ⬜ Open / ⬜ In Progress / ⬜ Fixed / ⬜ Closed

### Issue #2
- **Test Case:** _______________
- **Severity:** ⬜ Blocker / ⬜ Critical / ⬜ Major / ⬜ Minor
- **Description:** _______________

---

## Sign-off

### Test Lead
- **Name:** _______________
- **Date:** _______________
- **Signature:** _______________
- **Overall Status:** ⬜ Pass / ⬜ Fail / ⬜ Conditional Pass

### Notes:
_______________________________________________
_______________________________________________
_______________________________________________

---

**Document Status:** Ready for Testing
**Last Updated:** 2026-02-06
**Total Test Cases:** 121 (25 Functional + 26 Edge Cases + 20 Lifecycle + 19 Performance + 31 Callbacks)
**Estimated Testing Time:** 10-15 hours for full manual execution
