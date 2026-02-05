# Ad Caching v2 — Lifecycle & Memory Management Test Scenarios

> **Version:** 1.0
> **Date:** 2026-02-05
> **Related:** [TEST_SCENARIOS_EDGE_CASES.md](./TEST_SCENARIOS_EDGE_CASES.md)

## Цель документа

Тест-кейсы для проверки lifecycle management, memory management, и cleanup процессов.

---

## 1. Periodic Sweep

### TC-SWEEP-001: Periodic sweep запуск и выполнение

**Цель:** Проверить что periodic sweep job запускается и работает.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00, CPM $4.50]
- RTB_PAYLOAD: [RTB $3.00]
- TTL = 2 минуты (для теста)

**Steps:**
1. Загрузить ads через loadAd()
2. Подождать 5 минут
3. Проверить логи

**Expected Result:**
```
Timeline:
  T=0min:   Cache filled
  T=5min:   [BidonCache] PeriodicSweepJob: Sweep started (first execution)
            [BidonCache] ReadyToShowCache: sweep() checking 2 entries
            [BidonCache] ReadyToShowCache: Entry RTB $5.00 expired
            [BidonCache] ReadyToShowCache: Entry CPM $4.50 expired
            [BidonCache] ReadyToShowCache: Destroying 2 AdSources
            [BidonCache] ReadyToShowCache: sweep() removed 2 entries
            [BidonCache] RtbPayloadCache: sweep() checking 1 entry
            [BidonCache] RtbPayloadCache: Entry RTB $3.00 expired
            [BidonCache] RtbPayloadCache: sweep() removed 1 entry
            [BidonCache] WeakContextValidator: validateAndCleanup() → 0 leaks
            [BidonCache] PeriodicSweepJob: Sweep completed (3 entries removed)

  T=10min:  [BidonCache] PeriodicSweepJob: Sweep started (second execution)
            [BidonCache] ReadyToShowCache: sweep() checking 0 entries
            [BidonCache] RtbPayloadCache: sweep() checking 0 entries
            [BidonCache] PeriodicSweepJob: Sweep completed (0 entries removed)
```

**Validation:**
- ✅ Sweep job запускается через 5 минут
- ✅ Expired entries удаляются
- ✅ AdSource.destroy() вызывается
- ✅ Sweep повторяется каждые 5 минут
- ✅ Empty sweep не ломается

**Priority:** 🔴 HIGH

---

### TC-SWEEP-002: Sweep не трогает valid entries

**Цель:** Проверить что valid entries не удаляются.

**Preconditions:**
- READY_TO_SHOW:
  - RTB $5.00 (created 1 min ago) ← valid
  - CPM $4.50 (created 31 min ago) ← expired
- TTL = 30 минут

**Steps:**
1. Подождать sweep execution
2. Проверить кэш после sweep

**Expected Result:**
```
Logs:
  [BidonCache] PeriodicSweepJob: Sweep started
  [BidonCache] ReadyToShowCache: sweep() checking 2 entries
  [BidonCache] ReadyToShowCache: RTB $5.00 valid (age=1min < 30min)
  [BidonCache] ReadyToShowCache: CPM $4.50 expired (age=31min > 30min)
  [BidonCache] ReadyToShowCache: Destroying CPM $4.50
  [BidonCache] ReadyToShowCache: sweep() removed 1 entry

Cache State After:
  READY_TO_SHOW: [RTB $5.00]  ← kept
```

**Validation:**
- ✅ Valid entry НЕ удаляется
- ✅ Expired entry удаляется
- ✅ Partial cleanup работает

**Priority:** 🔴 HIGH

---

### TC-SWEEP-003: Sweep останавливается при destroyAd()

**Цель:** Проверить cleanup при destroy.

**Preconditions:**
- Periodic sweep запущен

**Steps:**
1. Запустить loadAd()
2. Подождать 2 минуты (sweep ещё не выполнился)
3. Вызвать destroyAd()
4. Подождать 5 минут
5. Проверить что sweep не выполняется

**Expected Result:**
```
Logs:
  T=0min:   [BidonCache] PeriodicSweepJob: Started (delay=5min)
  T=2min:   [BidonCache] AdCacheDenisImpl: destroyAd() called
            [BidonCache] AdInstanceScope: cancel() called
            [BidonCache] PeriodicSweepJob: Job cancelled
  T=5min:   (no sweep logs) ✓

Result:
  → Sweep job stopped ✓
```

**Validation:**
- ✅ Sweep job cancellation работает
- ✅ Sweep не выполняется после destroy

**Priority:** 🟡 MEDIUM

---

### TC-SWEEP-004: Multiple ad instances с отдельными sweep jobs

**Цель:** Проверить ad-instance scoped lifecycle.

**Preconditions:**
- Создано 2 InterstitialAd instance

**Steps:**
1. Создать InterstitialAd #1 → loadAd()
2. Создать InterstitialAd #2 → loadAd()
3. Проверить что 2 sweep jobs запущены
4. destroyAd() на instance #1
5. Проверить что sweep job #1 остановлен, #2 продолжает работать

**Expected Result:**
```
Logs:
  [BidonCache] InterstitialAd(id=1): PeriodicSweepJob started
  [BidonCache] InterstitialAd(id=2): PeriodicSweepJob started

  T=2min:
  [BidonCache] InterstitialAd(id=1): destroyAd() called
  [BidonCache] InterstitialAd(id=1): PeriodicSweepJob cancelled

  T=5min:
  [BidonCache] InterstitialAd(id=2): PeriodicSweepJob: Sweep started ✓
  (no sweep logs for id=1) ✓
```

**Validation:**
- ✅ Каждый ad instance имеет свой sweep job
- ✅ destroy одного instance не влияет на другой
- ✅ Application-wide кэши shared между instances

**Priority:** 🟡 MEDIUM

---

## 2. WeakContextValidator

### TC-WEAK-001: Activity destroyed, context не leaked

**Цель:** Проверить WeakReference pattern.

**Preconditions:**
- loadAd() выполнен
- READY_TO_SHOW: [RTB $5.00, CPM $4.50]

**Steps:**
1. Открыть Activity с interstitial ad
2. Вызвать loadAd()
3. Дождаться onAdLoaded
4. **НЕ** вызывать showAd()
5. Закрыть Activity (back button)
6. Подождать 10 секунд (дать GC сработать)
7. Проверить Memory Profiler OR LeakCanary

**Expected Result:**
```
Memory Profiler:
  Activity instance count: 0 ✓
  (Activity должна быть garbage collected)

LeakCanary:
  No leaks detected ✓

Logs:
  [BidonCache] WeakContextValidator: validateAndCleanup() starting
  [BidonCache] WeakContextValidator: Checking READY_TO_SHOW entries
  [BidonCache] WeakContextValidator: Entry RTB $5.00 → context is WEAK ✓
  [BidonCache] WeakContextValidator: Entry CPM $4.50 → context is WEAK ✓
  [BidonCache] WeakContextValidator: No leaked contexts found ✓
```

**Validation:**
- ✅ LeakCanary НЕ показывает leak
- ✅ Memory Profiler НЕ показывает retained Activity
- ✅ WeakContextValidator логи показывают "context is WEAK"

**Priority:** 🔴 HIGH (Critical for production)

---

### TC-WEAK-002: Multiple Activity rotations

**Цель:** Проверить что rotation не создаёт leaks.

**Preconditions:**
- loadAd() выполнен

**Steps:**
1. Открыть Activity
2. loadAd()
3. Rotate device (portrait → landscape)
4. loadAd() в новой Activity instance
5. Rotate снова (landscape → portrait)
6. loadAd() снова
7. Проверить Memory Profiler

**Expected Result:**
```
Memory Profiler:
  Activity instance count: 1 ✓ (только текущая)
  Old Activity instances: 0 ✓ (GC'd)

Logs:
  [BidonCache] WeakContextValidator: validateAndCleanup()
  [BidonCache] WeakContextValidator: Found 2 entries with weak contexts
  [BidonCache] WeakContextValidator: All contexts properly weak ✓
```

**Validation:**
- ✅ Только 1 Activity instance в memory
- ✅ Old instances garbage collected
- ✅ WeakReference работает для всех entries

**Priority:** 🔴 HIGH

---

### TC-WEAK-003: Sweep удаляет ads с leaked context

**Цель:** Проверить cleanup leaked ads (если случайно произошёл leak).

**Preconditions:**
- READY_TO_SHOW содержит ad с leaked context (симуляция)

**Steps:**
1. Симулировать leaked context (адаптер держит strong reference)
2. Запустить sweep
3. Проверить что leaked ad удаляется

**Expected Result:**
```
Logs:
  [BidonCache] WeakContextValidator: validateAndCleanup()
  [BidonCache] WeakContextValidator: Entry RTB $5.00 → context is STRONG (leak!)
  [BidonCache] WeakContextValidator: Destroying leaked AdSource
  [BidonCache] ReadyToShowCache: Removed leaked entry

Cache State:
  READY_TO_SHOW: []  ← leaked entry removed
```

**Validation:**
- ✅ Leaked entry обнаруживается
- ✅ Leaked entry удаляется
- ✅ AdSource.destroy() вызывается

**Priority:** 🟡 MEDIUM

---

## 3. Cancellation Management

### TC-CANCEL-001: showAd() отменяет ongoing CPM loading

**Цель:** Проверить cancellation при showAd().

**Preconditions:**
- loadAd() в процессе
- READY_TO_SHOW: [RTB $5.00] (из предыдущего)
- CPM processing в процессе

**Steps:**
1. Нажать "Load Ad"
2. Подождать 1 секунду (CPM loading started)
3. Нажать "Show Ad"
4. Проверить логи

**Expected Result:**
```
Logs:
  [BidonCache] CoordinationLayer: Starting auction (auctionId=X)
  [BidonCache] CpmProcessor: Loading CPM[0]...
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] CancellationManager: cancelIfMatching(auctionId=X)
  [BidonCache] CancellationManager: Cancelling auction X
  [BidonCache] CpmProcessor: Job cancelled (showAd called)
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $5.00 (from cache)
  [BidonCache] AdCacheDenisImpl: show SUCCESS

Network Stats:
  → CPM[1], CPM[2] requests NOT sent (saved bandwidth) ✓
```

**Validation:**
- ✅ Ongoing CPM loading отменяется
- ✅ Логи показывают "Job cancelled"
- ✅ showAd() использует cached ad
- ✅ Bandwidth saved (unnecessary requests not sent)

**Priority:** 🔴 HIGH

---

### TC-CANCEL-002: destroyAd() отменяет auction

**Цель:** Проверить cleanup при destroy во время auction.

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
  [BidonCache] CoordinationLayer: Auction starting
  [BidonCache] RtbProcessor: Loading RTB[0]...
  [BidonCache] CpmProcessor: Loading CPM[0]...
  [BidonCache] AdCacheDenisImpl: destroyAd() called
  [BidonCache] CancellationManager: Cancelling all ongoing auctions
  [BidonCache] RtbProcessor: Job cancelled (destroyAd called)
  [BidonCache] CpmProcessor: Job cancelled (destroyAd called)
  [BidonCache] AdInstanceScope: cancel() called
  [BidonCache] PeriodicSweepJob: Stopped

State:
  → All jobs cancelled ✓
  → Scope cancelled ✓
```

**Validation:**
- ✅ Все running jobs отменяются
- ✅ CoroutineScope cancelled
- ✅ Periodic sweep stopped

**Priority:** 🔴 HIGH

---

### TC-CANCEL-003: Multiple auction cancellation

**Цель:** Проверить cancellation нескольких auctions подряд.

**Preconditions:**
- Быстрый sequence: loadAd() → showAd() → loadAd() → showAd()

**Steps:**
1. Нажать "Load Ad" (auction #1 started)
2. Через 500ms нажать "Show Ad" (cancel auction #1)
3. Через 500ms нажать "Load Ad" (auction #2 started)
4. Через 500ms нажать "Show Ad" (cancel auction #2)
5. Проверить логи

**Expected Result:**
```
Logs:
  T=0ms:    [BidonCache] Auction(id=1): Started
  T=500ms:  [BidonCache] showAd() → Cancelling auction(id=1)
  T=1000ms: [BidonCache] Auction(id=2): Started
  T=1500ms: [BidonCache] showAd() → Cancelling auction(id=2)

State:
  → Both auctions cancelled cleanly ✓
  → No memory leaks ✓
  → No hanging jobs ✓
```

**Validation:**
- ✅ Каждый auction cleanly cancelled
- ✅ Нет memory leaks
- ✅ Нет hanging coroutines

**Priority:** 🟡 MEDIUM

---

## 4. Cleanup & Resource Management

### TC-CLEANUP-001: AdSource.destroy() вызывается для expired ads

**Цель:** Проверить proper cleanup AdSource.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- TTL expired

**Steps:**
1. Подождать TTL expiry
2. Запустить sweep OR вызвать getBest()
3. Проверить destroy() call

**Expected Result:**
```
Logs:
  [BidonCache] ReadyToShowCache: Entry RTB $5.00 expired
  [BidonCache] ReadyToShowCache: Calling adSource.destroy()
  [BidonCache] MetaAnAdapter: destroy() called ✓
  [BidonCache] MetaAnAdapter: Releasing resources
  [BidonCache] ReadyToShowCache: Entry removed

Result:
  → AdSource.destroy() called ✓
  → Resources released ✓
```

**Validation:**
- ✅ destroy() вызывается для каждого expired ad
- ✅ Adapter cleanup выполняется
- ✅ Memory released

**Priority:** 🔴 HIGH

---

### TC-CLEANUP-002: AdSource.destroy() вызывается при duplicate replacement

**Цель:** Проверить cleanup при замене duplicate.

**Preconditions:**
- READY_TO_SHOW: [meta_an $5.00]
- Новый auction возвращает [meta_an $7.00]

**Steps:**
1. Вызвать loadAd()
2. Дождаться duplicate replacement
3. Проверить destroy() call

**Expected Result:**
```
Logs:
  [BidonCache] ReadyToShowCache: Duplicate demandId detected (meta_an)
  [BidonCache] ReadyToShowCache: New eCPM ($7.00) > old eCPM ($5.00)
  [BidonCache] ReadyToShowCache: Calling adSource.destroy() on old entry
  [BidonCache] MetaAnAdapter: destroy() called ✓
  [BidonCache] ReadyToShowCache: Replacing with new entry

Cache State:
  READY_TO_SHOW: [meta_an $7.00]  ← replaced
```

**Validation:**
- ✅ destroy() вызывается для OLD ad
- ✅ Ресурсы старого ad released
- ✅ Новый ad добавлен

**Priority:** 🔴 HIGH

---

### TC-CLEANUP-003: AdSource.destroy() вызывается после show

**Цель:** Проверить cleanup после показа.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]

**Steps:**
1. Нажать "Show Ad"
2. Дождаться onAdShown
3. Проверить destroy() call

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: adSource.show() SUCCESS
  [BidonCache] AdCacheDenisImpl: Removing shown ad from cache
  [BidonCache] AdCacheDenisImpl: adSource.destroy() called ✓
  [BidonCache] ReadyToShowCache: Entry removed

Cache State:
  READY_TO_SHOW: []  ← empty
```

**Validation:**
- ✅ destroy() вызывается после show
- ✅ Entry удаляется из кэша
- ✅ Resources released

**Priority:** 🔴 HIGH

---

### TC-CLEANUP-004: destroyAd() не очищает application-wide кэши

**Цель:** Проверить что destroyAd() не трогает shared кэши.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- RTB_PAYLOAD: [RTB $3.00]

**Steps:**
1. Вызвать destroyAd()
2. Проверить кэши

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: destroyAd() called
  [BidonCache] AdInstanceScope: cancel() called
  [BidonCache] PeriodicSweepJob: Stopped
  [BidonCache] AdCacheDenisImpl: destroyAd() completed

Cache State:
  READY_TO_SHOW: [RTB $5.00]  ← unchanged ✓
  RTB_PAYLOAD:   [RTB $3.00]  ← unchanged ✓

Result:
  → Кэши сохраняются для других ad instances ✓
```

**Validation:**
- ✅ READY_TO_SHOW НЕ очищается
- ✅ RTB_PAYLOAD НЕ очищается
- ✅ Кэши application-wide shared

**Priority:** 🔴 HIGH

---

## 5. Coroutine Scope Management

### TC-SCOPE-001: AdInstanceScope создаётся при initialize

**Цель:** Проверить lifecycle scope.

**Preconditions:**
- Fresh InterstitialAd instance

**Steps:**
1. Создать InterstitialAd()
2. Проверить что scope создан
3. Вызвать loadAd()
4. Проверить что jobs запускаются в этом scope

**Expected Result:**
```
Logs:
  [BidonCache] InterstitialAd(id=1): Created
  [BidonCache] AdInstanceScope: Created (scope=SupervisorJob)
  [BidonCache] PeriodicSweepJob: Started in AdInstanceScope

  loadAd():
  [BidonCache] CoordinationLayer: Starting auction in AdInstanceScope
  [BidonCache] RtbProcessor: Job launched in AdInstanceScope
  [BidonCache] CpmProcessor: Job launched in AdInstanceScope
```

**Validation:**
- ✅ AdInstanceScope создаётся
- ✅ Все jobs используют этот scope
- ✅ SupervisorJob pattern используется

**Priority:** 🟡 MEDIUM

---

### TC-SCOPE-002: SupervisorJob изолирует failures

**Цель:** Проверить что failure одного job не крашит другие.

**Preconditions:**
- loadAd() в процессе

**Steps:**
1. Запустить loadAd()
2. Mock exception в RTB processor
3. Проверить что CPM processor продолжает работать

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: Exception caught: NullPointerException
  [BidonCache] RtbProcessor: Job failed
  [BidonCache] CpmProcessor: Loading CPM[0]... (continues) ✓
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW
  [BidonCache] CallbackCoordinator: onAdLoaded() ✓

Result:
  → RTB failure изолирован ✓
  → CPM продолжает работать ✓
  → onAdLoaded срабатывает ✓
```

**Validation:**
- ✅ SupervisorJob изолирует failures
- ✅ One job failure не крашит scope
- ✅ Auction продолжается

**Priority:** 🔴 HIGH

---

### TC-SCOPE-003: NonCancellable context для critical cleanup

**Цель:** Проверить что cleanup не cancellable.

**Preconditions:**
- loadAd() в процессе
- destroyAd() вызван во время loading

**Steps:**
1. Запустить loadAd()
2. Подождать 1 секунду
3. Вызвать destroyAd()
4. Проверить что cleanup завершается

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: destroyAd() called
  [BidonCache] RtbProcessor: Job cancelling...
  [BidonCache] RtbProcessor: Cleanup starting (NonCancellable context)
  [BidonCache] RtbProcessor: adSource.destroy() called ✓
  [BidonCache] RtbProcessor: Cleanup completed ✓
  [BidonCache] RtbProcessor: Job cancelled

Result:
  → Cleanup завершился даже при cancellation ✓
  → Resources released ✓
```

**Validation:**
- ✅ Cleanup не cancellable
- ✅ Resources properly released
- ✅ NonCancellable context используется

**Priority:** 🔴 HIGH

---

## Summary

**Total Lifecycle Tests:** 20
**Priority Distribution:**
- 🔴 HIGH: 15 (75%)
- 🟡 MEDIUM: 5 (25%)

**Coverage:**
- Periodic Sweep: 4 test cases
- WeakContextValidator: 3 test cases
- Cancellation: 3 test cases
- Cleanup: 4 test cases
- Coroutine Scope: 3 test cases

---

**Document Status:** Complete
**Last Updated:** 2026-02-05
**Critical Tests:** TC-SWEEP-001, TC-WEAK-001, TC-WEAK-002, TC-CANCEL-001, TC-CLEANUP-001-003, TC-SCOPE-002-003
