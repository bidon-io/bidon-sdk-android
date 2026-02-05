# Ad Caching v2 — Edge Cases & Error Handling Test Scenarios

> **Version:** 1.0
> **Date:** 2026-02-05
> **Related:** [TEST_SCENARIOS_FUNCTIONAL.md](./TEST_SCENARIOS_FUNCTIONAL.md)

## Цель документа

Тест-кейсы для проверки граничных случаев, обработки ошибок и нестандартных сценариев.

---

## 1. Race Conditions & Concurrency

### TC-RACE-001: Concurrent loadAd() calls

**Цель:** Проверить что второй loadAd() блокируется.

**Preconditions:**
- READY_TO_SHOW пуст

**Steps:**
1. Нажать "Load Ad"
2. **СРАЗУ** (через 100ms) нажать "Load Ad" СНОВА
3. Проверить логи

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: loadAd() called (auctionId=A)
  [BidonCache] CoordinationLayer: Starting auction A
  [BidonCache] AdCacheDenisImpl: loadAd() called (auctionId=B)
  [BidonCache] AdCacheDenisImpl: Auction already in progress, ignoring loadAd()
  [BidonCache] CallbackCoordinator: onAdLoaded() fired (auction A) ← ТОЛЬКО ОДИН РАЗ

Callbacks:
  onAdLoaded() × 1  ← ТОЛЬКО ОДИН callback
```

**Validation:**
- ✅ Второй loadAd() игнорируется
- ✅ Только ОДИН auction выполняется
- ✅ onAdLoaded срабатывает ОДИН раз
- ✅ Логи показывают "Auction already in progress"

**Priority:** 🔴 HIGH

---

### TC-RACE-002: loadAd() + showAd() одновременно

**Цель:** Проверить race condition между load и show.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]

**Steps:**
1. Нажать "Load Ad"
2. **Сразу** нажать "Show Ad" (через 10ms)
3. Проверить результат

**Expected Result:**
```
Scenario A: showAd() before auction starts
  → showAd() shows RTB $5.00 from cache
  → loadAd() continues in background

Scenario B: showAd() during auction
  → showAd() shows RTB $5.00 from cache
  → loadAd() cancelled

Logs:
  [BidonCache] AdCacheDenisImpl: loadAd() called
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $5.00
  [BidonCache] AdCacheDenisImpl: show SUCCESS
  [BidonCache] CancellationManager: Cancelling ongoing auction
```

**Validation:**
- ✅ showAd() не блокируется loadAd()
- ✅ Реклама показывается корректно
- ✅ Auction cancellation работает
- ✅ Нет deadlock

**Priority:** 🔴 HIGH

---

### TC-RACE-003: Multiple showAd() calls rapid fire

**Цель:** Проверить защиту от rapid clicks.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]

**Steps:**
1. Быстро нажать "Show Ad" 10 раз подряд (100ms интервал)
2. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called (1)
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $5.00
  [BidonCache] AdCacheDenisImpl: show() starting
  [BidonCache] AdCacheDenisImpl: showAd() called (2)
  [BidonCache] AdCacheDenisImpl: Ad is showing, ignoring showAd()
  [BidonCache] AdCacheDenisImpl: showAd() called (3-10)
  [BidonCache] AdCacheDenisImpl: Ad is showing, ignoring showAd() × 8

Result:
  → Показана только ОДНА реклама
```

**Validation:**
- ✅ Только первый showAd() выполняется
- ✅ Последующие calls игнорируются
- ✅ Логи показывают "Ad is showing"
- ✅ Нет multiple impressions

**Priority:** 🔴 HIGH

---

### TC-RACE-004: Duplicate demandId в READY_TO_SHOW

**Цель:** Проверить duplicate policy.

**Preconditions:**
- READY_TO_SHOW: [meta_an $5.00]
- Новый auction возвращает [meta_an $7.00]

**Steps:**
1. Вызвать loadAd() (warm start)
2. Дождаться завершения
3. Проверить кэш

**Expected Result:**
```
Logs:
  [BidonCache] ReadyToShowCache: Attempting to add meta_an $7.00
  [BidonCache] ReadyToShowCache: Found existing meta_an $5.00
  [BidonCache] ReadyToShowCache: New eCPM ($7.00) > old eCPM ($5.00)
  [BidonCache] ReadyToShowCache: Replacing old entry
  [BidonCache] ReadyToShowCache: AdSource.destroy() called for old ad
  [BidonCache] ReadyToShowCache: New entry added

Cache State:
  READY_TO_SHOW: [meta_an $7.00]  ← replaced
```

**Validation:**
- ✅ Старая запись удаляется
- ✅ Новая запись добавляется
- ✅ AdSource.destroy() вызывается для старого ad
- ✅ Только ОДНА запись meta_an в кэше

**Priority:** 🔴 HIGH

---

### TC-RACE-005: Duplicate demandId с меньшим eCPM

**Цель:** Проверить что меньший eCPM не заменяет больший.

**Preconditions:**
- READY_TO_SHOW: [meta_an $7.00]
- Новый auction возвращает [meta_an $5.00]

**Steps:**
1. Вызвать loadAd()
2. Проверить кэш

**Expected Result:**
```
Logs:
  [BidonCache] ReadyToShowCache: Attempting to add meta_an $5.00
  [BidonCache] ReadyToShowCache: Found existing meta_an $7.00
  [BidonCache] ReadyToShowCache: New eCPM ($5.00) <= old eCPM ($7.00)
  [BidonCache] ReadyToShowCache: Keeping old entry, discarding new
  [BidonCache] ReadyToShowCache: AdSource.destroy() called for NEW ad

Cache State:
  READY_TO_SHOW: [meta_an $7.00]  ← unchanged
```

**Validation:**
- ✅ Старая запись сохраняется
- ✅ Новая запись отбрасывается
- ✅ AdSource.destroy() вызывается для НОВОГО (отброшенного) ad
- ✅ Кэш не деградирует

**Priority:** 🔴 HIGH

---

## 2. TTL & Expiration

### TC-TTL-001: Expired ad в READY_TO_SHOW при getBest()

**Цель:** Проверить lazy eviction.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00] created 31 минуту назад
- TTL = 30 минут

**Steps:**
1. Подождать 31 минуту (или изменить TTL на 1 минуту для теста)
2. Нажать "Show Ad"
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] ReadyToShowCache: getBest() checking TTL
  [BidonCache] ReadyToShowCache: Entry RTB $5.00 expired (31 min > 30 min)
  [BidonCache] ReadyToShowCache: Removing expired entry
  [BidonCache] ReadyToShowCache: AdSource.destroy() called
  [BidonCache] AdCacheDenisImpl: getBest() → null (cache empty after expiry)
  [BidonCache] AdCacheDenisImpl: onAdShowFailed(NO_FILL)

Callback:
  onAdShowFailed(cause = NO_FILL)
```

**Validation:**
- ✅ Expired entry удаляется
- ✅ AdSource.destroy() вызывается
- ✅ onAdShowFailed корректно
- ✅ Никакие expired ads не показываются

**Priority:** 🔴 HIGH

---

### TC-TTL-002: Expired RTB_PAYLOAD при warm start

**Цель:** Проверить очистку expired payload.

**Preconditions:**
- RTB_PAYLOAD: [meta_an $3.00] created 31 минуту назад

**Steps:**
1. Подождать 31 минуту
2. Вызвать loadAd() (warm start)
3. Проверить token collection

**Expected Result:**
```
Logs:
  [BidonCache] RtbPayloadCache: getCachedDemandIds() checking TTL
  [BidonCache] RtbPayloadCache: Entry meta_an expired (31 min > 30 min)
  [BidonCache] RtbPayloadCache: Removing expired entry
  [BidonCache] GetTokensUseCase: Cached demand IDs: []  ← empty
  [BidonCache] GetTokensUseCase: Collecting tokens from ALL adapters (no skip)

Token Collection:
  → meta_an token collected ✓ (не skipped)
```

**Validation:**
- ✅ Expired payload удаляется
- ✅ meta_an НЕ в skipList
- ✅ Token collection полная (не оптимизированная)

**Priority:** 🟡 MEDIUM

---

### TC-TTL-003: Periodic sweep очистка

**Цель:** Проверить фоновую очистку.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00, CPM $4.50]
- RTB_PAYLOAD: [RTB $3.00]
- TTL = 2 минуты (для теста)

**Steps:**
1. Загрузить ads
2. Подождать 5 минут (первый sweep)
3. Проверить логи

**Expected Result:**
```
Logs:
  T=0min:   Cache filled
  T=5min:   [BidonCache] PeriodicSweepJob: Sweep started
            [BidonCache] ReadyToShowCache: sweep() found 2 expired entries
            [BidonCache] ReadyToShowCache: Destroying AdSource for RTB $5.00
            [BidonCache] ReadyToShowCache: Destroying AdSource for CPM $4.50
            [BidonCache] ReadyToShowCache: sweep() removed 2 entries
            [BidonCache] RtbPayloadCache: sweep() found 1 expired entry
            [BidonCache] RtbPayloadCache: sweep() removed 1 entry
            [BidonCache] WeakContextValidator: validateAndCleanup() → 0 leaks
            [BidonCache] PeriodicSweepJob: Sweep completed (removed 3 total)

Cache State After:
  READY_TO_SHOW: []  ← empty
  RTB_PAYLOAD:   []  ← empty
```

**Validation:**
- ✅ Sweep job запускается через 5 минут
- ✅ Expired entries удаляются
- ✅ AdSource.destroy() вызывается для каждого ad
- ✅ Следующий loadAd() будет cold start

**Priority:** 🔴 HIGH

---

### TC-TTL-004: Mixed expired + valid entries в кэше

**Цель:** Проверить частичную очистку.

**Preconditions:**
- READY_TO_SHOW:
  - RTB $5.00 (created 31 min ago) ← expired
  - CPM $4.50 (created 10 min ago) ← valid

**Steps:**
1. Вызвать showAd()
2. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] ReadyToShowCache: getBest() checking entries
  [BidonCache] ReadyToShowCache: RTB $5.00 expired → removing
  [BidonCache] ReadyToShowCache: CPM $4.50 valid → keeping
  [BidonCache] AdCacheDenisImpl: getBest() → CPM $4.50
  [BidonCache] AdCacheDenisImpl: show SUCCESS

Cache State After:
  READY_TO_SHOW: []  ← CPM $4.50 удалён после show
```

**Validation:**
- ✅ Expired entry удаляется
- ✅ Valid entry используется для show
- ✅ getBest() возвращает valid ad

**Priority:** 🟡 MEDIUM

---

## 3. Network & Backend Errors

### TC-NET-001: Network timeout при token collection

**Цель:** Проверить timeout handling.

**Preconditions:**
- Один adapter очень медленный (10+ секунд)

**Steps:**
1. Mock медленный adapter
2. Вызвать loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] GetTokensUseCase: Collecting tokens (timeout=5s)
  [BidonCache] GetTokensUseCase: Adapter 'meta_an' timeout
  [BidonCache] GetTokensUseCase: Collected 4/5 tokens in 5.1s
  [BidonCache] AuctionRequest: POST with 4 tokens

Result:
  → Auction продолжается с частичными tokens ✓
```

**Validation:**
- ✅ Timeout не блокирует весь процесс
- ✅ Auction работает с частичными tokens
- ✅ onAdLoaded срабатывает

**Priority:** 🟡 MEDIUM

---

### TC-NET-002: Backend 500 error

**Цель:** Проверить server error handling.

**Preconditions:**
- Backend возвращает HTTP 500

**Steps:**
1. Mock backend 500 response
2. Вызвать loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AuctionRequest: POST /v2/auction → 500 INTERNAL_SERVER_ERROR
  [BidonCache] CallbackCoordinator: onAdLoadFailed(SERVER_ERROR)

Callback:
  onAdLoadFailed(cause = SERVER_ERROR)
```

**Validation:**
- ✅ onAdLoadFailed вызывается
- ✅ Error code корректный
- ✅ Приложение не крашится

**Priority:** 🟡 MEDIUM

---

### TC-NET-003: Backend возвращает invalid JSON

**Цель:** Проверить parsing error handling.

**Preconditions:**
- Backend возвращает 200 но invalid JSON

**Steps:**
1. Mock invalid JSON response
2. Вызвать loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AuctionRequest: Response parsing failed (invalid JSON)
  [BidonCache] CallbackCoordinator: onAdLoadFailed(PARSE_ERROR)

Callback:
  onAdLoadFailed(cause = PARSE_ERROR)
```

**Validation:**
- ✅ Parsing error обрабатывается
- ✅ onAdLoadFailed корректный
- ✅ Нет crash

**Priority:** 🟢 LOW

---

### TC-NET-004: Slow network (high latency)

**Цель:** Проверить поведение на медленной сети.

**Preconditions:**
- Network latency = 5 секунд

**Steps:**
1. Throttle network speed
2. Вызвать loadAd()
3. Измерить время

**Expected Result:**
```
Timeline:
  T=0s:     loadAd() called
  T=0-2s:   Token collection (local)
  T=2-7s:   POST /v2/auction (5s latency)
  T=7-12s:  Waterfall loading (5s per request)
  T=12s:    onAdLoaded() ✓

Result:
  → Медленно, но работает ✓
```

**Validation:**
- ✅ Auction завершается успешно
- ✅ Нет timeout (если латентность в пределах timeout)
- ✅ onAdLoaded срабатывает

**Priority:** 🟢 LOW

---

## 4. Adapter Failures

### TC-ADAPTER-001: Все адаптеры fail (no fill)

**Цель:** Проверить полный failure scenario.

**Preconditions:**
- Все RTB adapters fail
- Все CPM adapters fail

**Steps:**
1. Mock все adapter failures
2. Вызвать loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: All RTB failed
  [BidonCache] CpmProcessor: All CPM failed
  [BidonCache] CallbackCoordinator: No fills from any adapter
  [BidonCache] CallbackCoordinator: onAdLoadFailed(NO_FILL)

Callback:
  onAdLoadFailed(cause = NO_FILL)

Cache State:
  READY_TO_SHOW: []  ← empty
  RTB_PAYLOAD:   []  ← empty
```

**Validation:**
- ✅ onAdLoadFailed вызывается
- ✅ Cause = NO_FILL
- ✅ Кэши остаются пустыми

**Priority:** 🔴 HIGH

---

### TC-ADAPTER-002: Adapter crash при load()

**Цель:** Проверить exception handling.

**Preconditions:**
- Один adapter throws exception при load()

**Steps:**
1. Mock adapter exception
2. Вызвать loadAd()
3. Проверить что другие adapters продолжают работать

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: Loading RTB[0]
  [BidonCache] RtbProcessor: Exception caught: NullPointerException
  [BidonCache] RtbProcessor: Treating as FAILED, trying next
  [BidonCache] RtbProcessor: Loading RTB[1] → SUCCESS

Result:
  → Auction продолжается ✓
  → onAdLoaded от RTB[1] ✓
```

**Validation:**
- ✅ Exception не крашит весь auction
- ✅ Другие adapters работают
- ✅ onAdLoaded срабатывает от работающего adapter

**Priority:** 🔴 HIGH

---

### TC-ADAPTER-003: Adapter возвращает null AdSource

**Цель:** Проверить null safety.

**Preconditions:**
- Adapter.load() возвращает null

**Steps:**
1. Mock null AdSource
2. Вызвать loadAd()
3. Проверить обработку

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: AdSource is null → treating as FAILED
  [BidonCache] RtbProcessor: Trying next RTB

Result:
  → Null обрабатывается как failure ✓
```

**Validation:**
- ✅ Null не крашит
- ✅ Treated как failure
- ✅ Auction продолжается

**Priority:** 🟡 MEDIUM

---

## 5. Memory & Resource Management

### TC-MEM-001: Activity destroyed во время auction

**Цель:** Проверить что context leak не происходит.

**Preconditions:**
- loadAd() запущен
- Activity destroyed до завершения auction

**Steps:**
1. Запустить loadAd()
2. Подождать 1 секунду
3. Закрыть Activity (back button)
4. Проверить memory profiler

**Expected Result:**
```
Logs:
  [BidonCache] CoordinationLayer: Auction in progress
  [BidonCache] AdCacheDenisImpl: Activity destroyed
  [BidonCache] WeakContextValidator: Context is weak, no leak
  [BidonCache] PeriodicSweepJob: validateAndCleanup() → 0 leaks

Memory Profiler:
  Activity instance count: 0 ✓
```

**Validation:**
- ✅ Activity garbage collected
- ✅ WeakReference работает
- ✅ Нет memory leak

**Priority:** 🔴 HIGH

---

### TC-MEM-002: Большое количество ads в READY_TO_SHOW

**Цель:** Проверить memory limits.

**Preconditions:**
- Загружено 10+ ads в READY_TO_SHOW

**Steps:**
1. Запустить 5 consecutive loadAd() calls
2. Проверить размер кэша
3. Проверить memory usage

**Expected Result:**
```
Cache State:
  READY_TO_SHOW: max 3-5 entries (capacity limit)

Logs:
  [BidonCache] ReadyToShowCache: Capacity limit reached (5)
  [BidonCache] ReadyToShowCache: Evicting lowest eCPM entry
  [BidonCache] ReadyToShowCache: AdSource.destroy() called

Memory:
  → Stable, не растёт бесконечно ✓
```

**Validation:**
- ✅ Cache capacity ограничен
- ✅ LRU или lowest-eCPM eviction
- ✅ Memory usage stable

**Priority:** 🟡 MEDIUM

---

### TC-MEM-003: Memory warning от OS

**Цель:** Проверить поведение при low memory.

**Preconditions:**
- System в low memory state

**Steps:**
1. Trigger onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)
2. Проверить что кэши реагируют

**Expected Result:**
```
Logs:
  [BidonCache] onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)
  [BidonCache] RtbPayloadCache: Clearing cache (low memory)
  [BidonCache] ReadyToShowCache: Keeping (critical for UX)

Cache State:
  READY_TO_SHOW: kept (важнее)
  RTB_PAYLOAD:   cleared ✓
```

**Validation:**
- ✅ RTB_PAYLOAD очищается
- ✅ READY_TO_SHOW сохраняется (критичнее)
- ✅ System не убивает процесс

**Priority:** 🟢 LOW

---

## 6. Edge Cases

### TC-EDGE-001: Empty waterfall от backend

**Цель:** Проверить обработку пустого waterfall.

**Preconditions:**
- Backend возвращает `{ "adUnits": [] }`

**Steps:**
1. Mock empty waterfall response
2. Вызвать loadAd() (cold start)
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AuctionRequest: Response → 0 adUnits
  [BidonCache] WaterfallSplitter: Empty waterfall
  [BidonCache] CallbackCoordinator: onAdLoadFailed(NO_FILL)

Callback:
  onAdLoadFailed(cause = NO_FILL)
```

**Validation:**
- ✅ onAdLoadFailed корректный
- ✅ Нет crash

**Priority:** 🟡 MEDIUM

---

### TC-EDGE-002: Только RTB в waterfall (no CPM)

**Цель:** Проверить обработку RTB-only waterfall.

**Preconditions:**
- Waterfall: [RTB $5.00, RTB $3.00]
- CPM group пуст

**Steps:**
1. Вызвать loadAd()
2. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] WaterfallSplitter: RTB: 2, CPM: 0
  [BidonCache] RtbProcessor: Processing 2 RTB units
  [BidonCache] CpmProcessor: Empty CPM group, skipping

Result:
  → onAdLoaded от RTB ✓
```

**Validation:**
- ✅ CPM processing не ломается
- ✅ onAdLoaded срабатывает

**Priority:** 🟡 MEDIUM

---

### TC-EDGE-003: Только CPM в waterfall (no RTB)

**Цель:** Проверить обработку CPM-only waterfall.

**Preconditions:**
- Waterfall: [CPM $4.50, CPM $2.50]
- RTB group пуст

**Steps:**
1. Вызвать loadAd()
2. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] WaterfallSplitter: RTB: 0, CPM: 2
  [BidonCache] RtbProcessor: Empty RTB group, skipping
  [BidonCache] CpmProcessor: Processing 2 CPM units

Result:
  → onAdLoaded от CPM ✓
```

**Validation:**
- ✅ RTB processing не ломается
- ✅ onAdLoaded срабатывает

**Priority:** 🟡 MEDIUM

---

### TC-EDGE-004: Ad с eCPM = 0

**Цель:** Проверить обработку нулевого eCPM.

**Preconditions:**
- Waterfall: [RTB $0.00, CPM $4.50]

**Steps:**
1. Вызвать loadAd()
2. Проверить getBest()

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: RTB $0.00 loaded → READY_TO_SHOW
  [BidonCache] CpmProcessor: CPM $4.50 loaded → READY_TO_SHOW
  [BidonCache] AdCacheDenisImpl: getBest() comparing eCPMs
  [BidonCache] AdCacheDenisImpl: getBest() → CPM $4.50 (highest)

Result:
  → showAd() показывает CPM $4.50 ✓
```

**Validation:**
- ✅ eCPM = 0 обрабатывается корректно
- ✅ getBest() выбирает CPM с eCPM > 0

**Priority:** 🟢 LOW

---

### TC-EDGE-005: destroyAd() во время auction

**Цель:** Проверить cleanup при destroy.

**Preconditions:**
- loadAd() в процессе

**Steps:**
1. Запустить loadAd()
2. Подождать 1 секунду
3. Вызвать destroyAd()
4. Проверить cleanup

**Expected Result:**
```
Logs:
  [BidonCache] CoordinationLayer: Auction in progress
  [BidonCache] AdCacheDenisImpl: destroyAd() called
  [BidonCache] CancellationManager: Cancelling all jobs
  [BidonCache] AdInstanceScope: cancel() called
  [BidonCache] PeriodicSweepJob: Stopped

State After:
  → Auction cancelled ✓
  → Jobs stopped ✓
  → Кэши НЕ очищены (application-wide) ✓
```

**Validation:**
- ✅ Ongoing auction отменяется
- ✅ Coroutine scope cancelled
- ✅ Periodic sweep stopped
- ✅ Кэши сохраняются (application-wide)

**Priority:** 🔴 HIGH

---

## Summary

**Total Edge Case Tests:** 26
**Priority Distribution:**
- 🔴 HIGH: 13 (50%)
- 🟡 MEDIUM: 10 (38%)
- 🟢 LOW: 3 (12%)

**Coverage:**
- Race Conditions: 5 test cases
- TTL & Expiration: 4 test cases
- Network Errors: 4 test cases
- Adapter Failures: 3 test cases
- Memory Management: 3 test cases
- Edge Cases: 5 test cases

---

**Document Status:** Complete
**Last Updated:** 2026-02-05
**Next Steps:** Execute edge case tests after functional tests pass
