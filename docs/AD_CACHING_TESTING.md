# Ad Caching v2 — Testing Guide

> **Version:** 1.0
> **Date:** 2026-02-05
> **Related:** [AD_CACHING_SPEC.md](./AD_CACHING_SPEC.md)

## Цель документа

Этот документ описывает как тестировать ad caching v2 систему вручную на Android эмуляторе.

---

## 1. Test Environment Setup

### 1.1 Требования

- **Android Emulator:** API 26+ (Android 8.0+)
- **Test App:** https://github.com/AlexGladkov/claude-in-mobile (обязательно для UI тестирования)
- **Ad Type:** Interstitial only (ad caching работает только для interstitial)
- **Test Placement Key:** `1O16GQT380000`

### 1.2 Подключение к тестовому приложению

**Тестирование через claude-in-mobile (основной способ):**

Это приложение используется для "протыкивания UI" и полноценного тестирования ad caching системы через Claude MCP.

```bash
# Подключить MCP сервер для работы с мобильным приложением
claude mcp add --transport stdio mobile -- npx -y claude-in-mobile
```

После подключения MCP сервера Claude автоматически получает доступ к приложению на эмуляторе.

**Конфигурация для тестирования:**
1. Убедиться что эмулятор запущен (API 26+)
2. Открыть раздел "Interstitial Ad" в приложении через Claude
3. **Ввести ключ `1O16GQT380000`** в поле "Placement ID" / "Auction Key"
4. Нажать "Load Ad" для начала тестирования

**Для разработчиков SDK:**

Внутренний demo app в Bidon SDK (`app/` module) имеет ключ `1O16GQT380000` уже установленным по дефолту в коде для удобства быстрого тестирования во время разработки. Но для полноценного UI тестирования и проверки user flow обязательно используйте `claude-in-mobile` через MCP.

### 1.3 Конфигурация в claude-in-mobile

**Шаги настройки:**

1. **Введите Placement Key:**
   - Откройте "Interstitial Ad" экран в приложении
   - Найдите поле "Placement ID" или "Auction Key"
   - Введите `1O16GQT380000`

2. **Проверьте Bidon SDK версию:**
   - Убедитесь что приложение использует вашу локальную версию SDK с ad caching v2
   - Или обновите зависимость на последнюю версию Bidon SDK

3. **AdCacheFactory конфигурация (в коде SDK):**

```kotlin
// Ad caching v2 активируется через AdCacheFactory
// Это настраивается внутри Bidon SDK, не в тестовом приложении

// Для активации v2:
AdCacheFactory.setVersion(AdCacheVersion.V2_DENIS)

// Для fallback на старую версию:
AdCacheFactory.setVersion(AdCacheVersion.V1_LEGACY)
```

**Проверка готовности:**
- ✅ claude-in-mobile установлен на эмуляторе
- ✅ Placement key `1O16GQT380000` введён в UI
- ✅ Bidon SDK содержит ad caching v2 код
- ✅ AdCacheFactory настроен на использование v2

---

## 2. Test Scenarios

### Scenario 1: Cold Start (первый loadAd)

**Цель:** Проверить полный auction flow без кэша.

**Steps:**
1. Запустить app на чистом эмуляторе (fresh install)
2. Нажать кнопку "Load Interstitial Ad"
3. Дождаться callback `onAdLoaded`

**Expected Result:**
```
Timeline:
  T=0s:     loadAd() called
  T=0-2s:   Token collection (RTB adapters)
  T=2-3s:   POST /v2/auction/interstitial
  T=3-5s:   Parallel RTB + CPM loading
  T=5-7s:   onAdLoaded() callback ✓

Logs (Logcat filter: "BidonCache"):
  [BidonCache] CoordinationLayer: determineStartState() → PureColdStart
  [BidonCache] CoordinationLayer: Dynamic pricefloor = 0.01 (default)
  [BidonCache] GetTokensUseCase: Collecting tokens from 5 adapters
  [BidonCache] GetTokensUseCase: Skipped 0 adapters (no cache)
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial with pricefloor=0.01
  [BidonCache] WaterfallSplitter: Split waterfall → RTB: 2, CPM: 3
  [BidonCache] RtbProcessor: Loading RTB[0] with eCPM $5.00
  [BidonCache] CpmProcessor: Loading CPM[0] with eCPM $4.50
  [BidonCache] RtbProcessor: SUCCESS → READY_TO_SHOW (RTB $5.00)
  [BidonCache] RtbProcessor: Saving RTB[1] → RTB_PAYLOAD ($3.00)
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $4.50)
  [BidonCache] CallbackCoordinator: onAdLoaded() fired (first fill)

Cache State After:
  READY_TO_SHOW: [RTB $5.00, CPM $4.50]
  RTB_PAYLOAD:   [RTB $3.00 payload]
```

**Validation:**
- ✅ onAdLoaded должен сработать через 5-7 секунд
- ✅ Логи должны показать "PureColdStart"
- ✅ Логи должны показать "Skipped 0 adapters"
- ✅ READY_TO_SHOW не пуст после загрузки

---

### Scenario 2: Warm Start (повторный loadAd)

**Цель:** Проверить немедленный onAdLoaded из кэша.

**Steps:**
1. **Prerequisite:** Выполнить Scenario 1 (cold start)
2. Подождать 5 секунд (дать аукциону завершиться)
3. **НЕ** вызывать showAd() - кэш должен остаться заполненным
4. Нажать кнопку "Load Interstitial Ad" СНОВА

**Expected Result:**
```
Timeline:
  T=0s:     loadAd() called
  T=0.1s:   onAdLoaded() callback ✓ (INSTANT!)
  T=0-2s:   (фоновый аукцион продолжается)

Logs (Logcat filter: "BidonCache"):
  [BidonCache] CoordinationLayer: determineStartState() → WarmStart
  [BidonCache] CoordinationLayer: READY_TO_SHOW.getBest() → RTB $5.00
  [BidonCache] CoordinationLayer: onAdLoaded() IMMEDIATE (warm start)
  [BidonCache] CoordinationLayer: Background auction starting...
  [BidonCache] GetTokensUseCase: Collecting tokens from 4 adapters
  [BidonCache] GetTokensUseCase: Skipped 1 adapter (RTB $3.00 cached)
  [BidonCache] PricefloorCalculator: Dynamic pricefloor = $4.50 (0.9 * $5.00)
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial with pricefloor=$4.50
  [BidonCache] WaterfallSplitter: Split waterfall → RTB: 1, CPM: 2
  [BidonCache] RtbProcessor: Loading RTB[0] with eCPM $6.00
  [BidonCache] RtbProcessor: SUCCESS → READY_TO_SHOW (RTB $6.00)

Cache State After:
  READY_TO_SHOW: [RTB $6.00, RTB $5.00, CPM $4.50]  ← новый ad добавлен
  RTB_PAYLOAD:   [RTB $3.00 payload]
```

**Validation:**
- ✅ onAdLoaded должен сработать за <1 секунду (INSTANT!)
- ✅ Логи должны показать "WarmStart"
- ✅ Логи должны показать "Skipped 1 adapter"
- ✅ Dynamic pricefloor должен быть $4.50 (защита кэша)
- ✅ Фоновый аукцион должен продолжаться (видно в логах)

---

### Scenario 3: showAd() и выбор лучшей рекламы

**Цель:** Проверить что showAd() выбирает ad с максимальным eCPM.

**Steps:**
1. **Prerequisite:** Выполнить Scenario 2 (warm start)
2. Cache state: [RTB $6.00, RTB $5.00, CPM $4.50]
3. Нажать кнопку "Show Interstitial Ad"

**Expected Result:**
```
Timeline:
  T=0s:   showAd() called
  T=0s:   Ad displayed (RTB $6.00) ✓

Logs (Logcat filter: "BidonCache"):
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $6.00 (highest eCPM)
  [BidonCache] CancellationManager: cancelIfMatching(auctionId=...)
  [BidonCache] CancellationManager: Cancelled CPM processing (saved network)
  [BidonCache] AdCacheDenisImpl: adSource.show() starting
  [BidonCache] AdCacheDenisImpl: show SUCCESS
  [BidonCache] AdCacheDenisImpl: Removing shown ad from READY_TO_SHOW
  [BidonCache] Statistics: Sending win notification for RTB $6.00

Cache State After:
  READY_TO_SHOW: [RTB $5.00, CPM $4.50]  ← RTB $6.00 удалён
  RTB_PAYLOAD:   [RTB $3.00 payload]
```

**Validation:**
- ✅ Должна показаться реклама с eCPM $6.00 (highest)
- ✅ Логи должны показать "getBest() → RTB $6.00"
- ✅ Логи должны показать "Cancelled CPM processing"
- ✅ Показанная реклама должна удалиться из READY_TO_SHOW
- ✅ Следующий showAd() покажет RTB $5.00

---

### Scenario 4: Periodic Sweep (TTL expiration)

**Цель:** Проверить что expired ads удаляются из кэша.

**Steps:**
1. **Prerequisite:** Выполнить Scenario 1 (cold start)
2. Cache state: [RTB $5.00, CPM $4.50] with TTL=30 minutes
3. **Изменить TTL для тестирования:** Установить `DEFAULT_TTL_MS = 2 * 60 * 1000L` (2 минуты)
4. Подождать 5 минут (первый sweep job)
5. Проверить логи

**Expected Result:**
```
Timeline:
  T=0s:     Cache filled: [RTB $5.00, CPM $4.50]
  T=5min:   PeriodicSweepJob: First sweep execution
  T=5min:   2 entries expired (> 2 min TTL)
  T=5min:   AdSource.destroy() called for expired ads

Logs (Logcat filter: "BidonCache"):
  [BidonCache] PeriodicSweepJob: Sweep started
  [BidonCache] ReadyToShowCache: sweep() found 2 expired entries
  [BidonCache] ReadyToShowCache: Destroying AdSource for RTB $5.00
  [BidonCache] ReadyToShowCache: Destroying AdSource for CPM $4.50
  [BidonCache] ReadyToShowCache: sweep() removed 2 entries
  [BidonCache] RtbPayloadCache: sweep() found 1 expired entry
  [BidonCache] RtbPayloadCache: sweep() removed 1 entry
  [BidonCache] WeakContextValidator: validateAndCleanup() → 0 leaked contexts
  [BidonCache] PeriodicSweepJob: Sweep completed (removed 3 entries total)

Cache State After:
  READY_TO_SHOW: []  ← empty
  RTB_PAYLOAD:   []  ← empty
```

**Validation:**
- ✅ Sweep job должен запуститься через 5 минут после start
- ✅ Expired entries должны удалиться
- ✅ AdSource.destroy() должен вызваться для каждого expired ad
- ✅ Следующий loadAd() будет cold start (cache empty)

---

### Scenario 5: Token Collection Optimization

**Цель:** Проверить что token collection пропускает cached networks.

**Steps:**
1. **Prerequisite:** Выполнить Scenario 1 (cold start)
2. RTB_PAYLOAD state: [meta_an $3.00, bidmachine $2.50]
3. Нажать кнопку "Load Interstitial Ad" (warm start)

**Expected Result:**
```
Logs (Logcat filter: "BidonCache"):
  [BidonCache] GetTokensUseCase: Total RTB adapters: 5
  [BidonCache] GetTokensUseCase: Cached demand IDs: [meta_an, bidmachine]
  [BidonCache] GetTokensUseCase: Collecting tokens from 3 adapters
  [BidonCache] GetTokensUseCase: Skipped 2 adapters:
    - meta_an (cached payload available)
    - bidmachine (cached payload available)
  [BidonCache] GetTokensUseCase: Collected 3 tokens in 1.2s
```

**Validation:**
- ✅ Token collection должна пропустить 2 адаптера
- ✅ Логи должны показать "Skipped 2 adapters"
- ✅ Время token collection должно уменьшиться (~1-2s вместо 2-3s)

---

### Scenario 6: Dynamic Pricefloor Calculation

**Цель:** Проверить расчёт динамического pricefloor.

**Steps:**
1. **Prerequisite:** Выполнить Scenario 1 (cold start)
2. Cache state:
   - READY_TO_SHOW: [RTB $5.00, CPM $4.50]
   - RTB_PAYLOAD: [RTB $7.00 payload]
3. Нажать кнопку "Load Interstitial Ad" (warm start)

**Expected Result:**
```
Logs (Logcat filter: "BidonCache"):
  [BidonCache] PricefloorCalculator: Calculating dynamic pricefloor
  [BidonCache] PricefloorCalculator: READY_TO_SHOW.maxEcpm = $5.00
  [BidonCache] PricefloorCalculator: RTB_PAYLOAD.maxEcpm = $7.00
  [BidonCache] PricefloorCalculator: max($5.00, $7.00) = $7.00
  [BidonCache] PricefloorCalculator: Applying safety margin: $7.00 * 0.9 = $6.30
  [BidonCache] PricefloorCalculator: userPricefloor = null
  [BidonCache] PricefloorCalculator: Dynamic pricefloor = $6.30 ✓
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial with pricefloor=$6.30
```

**Validation:**
- ✅ Dynamic pricefloor должен быть $6.30 (0.9 * $7.00)
- ✅ Backend должен получить pricefloor=$6.30 в запросе
- ✅ Backend НЕ должен вернуть ads с eCPM < $6.30

---

### Scenario 7: Empty Waterfall (все ads отфильтрованы)

**Цель:** Проверить поведение когда backend возвращает пустой waterfall.

**Steps:**
1. **Prerequisite:** Выполнить Scenario 2 (warm start)
2. Cache state: [RTB $5.00, CPM $4.50]
3. Dynamic pricefloor = $4.50
4. Backend возвращает empty waterfall (все ads ниже pricefloor)
5. Нажать кнопку "Load Interstitial Ad"

**Expected Result:**
```
Logs (Logcat filter: "BidonCache"):
  [BidonCache] CoordinationLayer: determineStartState() → WarmStart
  [BidonCache] CoordinationLayer: onAdLoaded() IMMEDIATE (warm start)
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial with pricefloor=$4.50
  [BidonCache] AuctionRequest: Response received → 0 adUnits (empty waterfall)
  [BidonCache] WaterfallSplitter: Empty waterfall, skipping processing
  [BidonCache] CoordinationLayer: Empty waterfall OK (warm start already served)

Cache State After:
  READY_TO_SHOW: [RTB $5.00, CPM $4.50]  ← unchanged
  RTB_PAYLOAD:   [RTB $3.00 payload]     ← unchanged
```

**Validation:**
- ✅ onAdLoaded должен сработать СРАЗУ (warm start)
- ✅ Empty waterfall НЕ должен вызвать onAdLoadFailed
- ✅ Кэш должен остаться нетронутым
- ✅ showAd() должен работать (использует cached ads)

---

### Scenario 8: Memory Leak Detection (WeakReference)

**Цель:** Проверить что Activity context не retained singleton caches.

**Setup:**
1. Установить LeakCanary в тестовое приложение
2. Или использовать Android Studio Memory Profiler

**Steps:**
1. Открыть Activity с interstitial ad
2. Вызвать loadAd()
3. Подождать onAdLoaded
4. **НЕ** вызывать showAd()
5. Закрыть Activity (back button)
6. Подождать 10 секунд (дать GC сработать)
7. Проверить Memory Profiler / LeakCanary

**Expected Result:**
```
Memory Profiler:
  Activity instance count: 0 ✓
  (Activity должна быть garbage collected)

LeakCanary:
  No leaks detected ✓

Logs (Logcat filter: "BidonCache"):
  [BidonCache] WeakContextValidator: validateAndCleanup() starting
  [BidonCache] WeakContextValidator: Checking READY_TO_SHOW entries
  [BidonCache] WeakContextValidator: Entry RTB $5.00 → context is WEAK
  [BidonCache] WeakContextValidator: Entry CPM $4.50 → context is WEAK
  [BidonCache] WeakContextValidator: No leaked contexts found ✓
```

**Validation:**
- ✅ LeakCanary НЕ должен показать leak
- ✅ Memory Profiler НЕ должен показать retained Activity
- ✅ WeakContextValidator логи должны показать "context is WEAK"

---

### Scenario 9: Concurrent loadAd() Calls (race condition)

**Цель:** Проверить что concurrent loadAd() блокируется.

**Steps:**
1. Нажать кнопку "Load Interstitial Ad"
2. **СРАЗУ** (через 100ms) нажать кнопку СНОВА
3. Проверить логи

**Expected Result:**
```
Logs (Logcat filter: "BidonCache"):
  [BidonCache] AdCacheDenisImpl: loadAd() called (auctionId=A)
  [BidonCache] CoordinationLayer: Starting auction A
  [BidonCache] AdCacheDenisImpl: loadAd() called (auctionId=B)
  [BidonCache] AdCacheDenisImpl: Auction already in progress, ignoring loadAd()
  [BidonCache] CallbackCoordinator: onAdLoaded() fired (auction A)
```

**Validation:**
- ✅ Второй loadAd() должен быть ignored
- ✅ Только ОДИН auction должен выполниться
- ✅ onAdLoaded должен сработать ОДИН раз

---

### Scenario 10: showAd() Cancels Ongoing Auction

**Цель:** Проверить что showAd() отменяет ongoing CPM loading.

**Steps:**
1. Нажать кнопку "Load Interstitial Ad" (cold start)
2. Подождать 1 секунду (auction начался, но не завершился)
3. Нажать кнопку "Show Interstitial Ad"

**Expected Result:**
```
Logs (Logcat filter: "BidonCache"):
  [BidonCache] CoordinationLayer: Starting auction (auctionId=X)
  [BidonCache] RtbProcessor: Loading RTB[0]...
  [BidonCache] CpmProcessor: Loading CPM[0]...
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] CancellationManager: cancelIfMatching(auctionId=X)
  [BidonCache] CancellationManager: Cancelling auction X
  [BidonCache] CpmProcessor: Job cancelled (showAd called)
  [BidonCache] RtbProcessor: Job cancelled (showAd called)
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $5.00 (from cache)
  [BidonCache] AdCacheDenisImpl: show SUCCESS
```

**Validation:**
- ✅ Ongoing auction должен отмениться
- ✅ Логи должны показать "Job cancelled"
- ✅ showAd() должен использовать cached ad (если есть)
- ✅ Если кэш пуст → onAdShowFailed(NO_FILL)

---

## 3. Logcat Filters

### 3.1 Основные теги для фильтрации

```bash
# Все логи BidonCache
adb logcat -s BidonCache:D

# Только координация аукциона
adb logcat -s BidonCache:D | grep "CoordinationLayer"

# Только кэш операции
adb logcat -s BidonCache:D | grep -E "(READY_TO_SHOW|RTB_PAYLOAD)"

# Только прайсфлур расчёты
adb logcat -s BidonCache:D | grep "PricefloorCalculator"

# Только токен коллекция
adb logcat -s BidonCache:D | grep "GetTokensUseCase"

# Только процессоры
adb logcat -s BidonCache:D | grep -E "(RtbProcessor|CpmProcessor)"

# Только lifecycle события
adb logcat -s BidonCache:D | grep -E "(PeriodicSweepJob|CancellationManager|CleanupCoordinator)"
```

### 3.2 Android Studio Logcat Filter

```
tag:BidonCache
```

Или более специфичный:

```
tag:BidonCache level:debug message:CoordinationLayer
```

---

## 4. Cache State Inspection

### 4.1 Добавить debug endpoint (для тестирования)

```kotlin
// В AdCacheDenisImpl добавить:
fun debugCacheState(): CacheDebugInfo {
    return CacheDebugInfo(
        readyToShow = ReadyToShowCache.getAll().map {
            "demandId=${it.demandId}, eCPM=${it.ecpm}, age=${now() - it.createdAt}ms"
        },
        rtbPayload = RtbPayloadCache.getAll().map {
            "demandId=${it.demandId}, pricefloor=${it.pricefloor}, age=${now() - it.createdAt}ms"
        }
    )
}

data class CacheDebugInfo(
    val readyToShow: List<String>,
    val rtbPayload: List<String>
)
```

### 4.2 Вызов debug endpoint

```kotlin
// В тестовом приложении добавить кнопку:
binding.buttonDebugCache.setOnClickListener {
    val cacheInfo = interstitialAd.debugCacheState()
    Log.d("TestApp", "READY_TO_SHOW: ${cacheInfo.readyToShow}")
    Log.d("TestApp", "RTB_PAYLOAD: ${cacheInfo.rtbPayload}")
}
```

---

## 5. Performance Benchmarking

### 5.1 Метрики для измерения

| Metric | Target | How to Measure |
|--------|--------|----------------|
| Cold Start Latency | 5-7s | Time from loadAd() to onAdLoaded() |
| Warm Start Latency | <1s | Time from loadAd() to onAdLoaded() |
| Token Collection Time | 1-3s | Time for getTokens() completion |
| Cache Operation Overhead | <10ms | Time for put/get/remove operations |
| Memory Footprint | <5MB | Android Studio Memory Profiler |

### 5.2 Timing Log

```kotlin
// В CoordinationLayer добавить:
private val startTime = System.currentTimeMillis()

private fun logTiming(event: String) {
    val elapsed = System.currentTimeMillis() - startTime
    Log.d("BidonCache", "[TIMING] $event at T+${elapsed}ms")
}

// Usage:
logTiming("loadAd() called")
logTiming("Tokens collected")
logTiming("Auction response received")
logTiming("onAdLoaded() fired")
```

---

## 6. Troubleshooting

### Issue 1: onAdLoaded не срабатывает (cold start)

**Symptoms:**
- loadAd() вызван, но callback не приходит
- Логи показывают "Auction started" но нет "onAdLoaded"

**Possible Causes:**
1. Все RTB и CPM загрузки failed
2. Backend вернул empty waterfall
3. Network timeout

**Debug Steps:**
```bash
# Check auction response
adb logcat -s BidonCache:D | grep "AuctionRequest"

# Check processor failures
adb logcat -s BidonCache:D | grep -E "(FAILED|ERROR)"

# Check network connectivity
adb logcat -s OkHttp:D
```

**Solution:**
- Проверить network connectivity
- Проверить placement key (`1O16GQT380000`)
- Проверить что тестовые адаптеры зарегистрированы

---

### Issue 2: Warm start не срабатывает (нет immediate callback)

**Symptoms:**
- Второй loadAd() занимает 5-7 секунд (как cold start)
- Логи показывают "PureColdStart" вместо "WarmStart"

**Possible Causes:**
1. READY_TO_SHOW cache пуст (ads expired или показаны)
2. showAd() вызван между двумя loadAd()
3. destroyAd() очистил кэш (bug — не должно быть)

**Debug Steps:**
```bash
# Check cache state
adb logcat -s BidonCache:D | grep "READY_TO_SHOW"

# Check if cache is empty
adb logcat -s BidonCache:D | grep "isEmpty"
```

**Solution:**
- НЕ вызывать showAd() между тестами
- Проверить что TTL не истёк (default 30 минут)
- Добавить debug endpoint для cache inspection

---

### Issue 3: Dynamic pricefloor = 0.01 (не работает)

**Symptoms:**
- Логи показывают "Dynamic pricefloor = 0.01"
- Backend возвращает low-quality ads

**Possible Causes:**
1. Кэш пуст (READY_TO_SHOW и RTB_PAYLOAD empty)
2. PricefloorCalculator не вызывается
3. Bug в calculateDynamicPricefloor()

**Debug Steps:**
```bash
# Check cache max eCPM
adb logcat -s BidonCache:D | grep "maxEcpm"

# Check pricefloor calculation
adb logcat -s BidonCache:D | grep "PricefloorCalculator"
```

**Solution:**
- Выполнить cold start сначала (заполнить кэш)
- Проверить что warm start детектируется корректно

---

### Issue 4: Memory leak (Activity retained)

**Symptoms:**
- LeakCanary показывает leak
- Memory Profiler показывает retained Activity instance

**Possible Causes:**
1. AdSource держит strong reference на Activity
2. WeakContextValidator не работает
3. Expired ads не destroyed

**Debug Steps:**
```bash
# Check WeakContextValidator logs
adb logcat -s BidonCache:D | grep "WeakContextValidator"

# Check periodic sweep
adb logcat -s BidonCache:D | grep "PeriodicSweepJob"

# Check AdSource.destroy() calls
adb logcat -s BidonCache:D | grep "destroy"
```

**Solution:**
- Убедиться что PeriodicSweepJob запущен
- Убедиться что WeakContextValidator вызывается
- Проверить что AdSource.destroy() вызывается для expired ads

---

### Issue 5: Periodic sweep не запускается

**Symptoms:**
- Логи НЕ показывают "PeriodicSweepJob: Sweep started"
- Expired ads остаются в кэше после TTL

**Possible Causes:**
1. AdInstanceScope не создан
2. PeriodicSweepJob не запущен (start() не вызван)
3. AdInstanceScope.cancel() вызван слишком рано

**Debug Steps:**
```bash
# Check if sweep job is started
adb logcat -s BidonCache:D | grep "PeriodicSweepJob"

# Check AdInstanceScope lifecycle
adb logcat -s BidonCache:D | grep "AdInstanceScope"
```

**Solution:**
- Проверить что AdInstanceScope создаётся при initialize
- Проверить что periodicSweepJob.start() вызывается
- Проверить что destroyAd() НЕ вызывается раньше времени

---

## 7. Success Criteria Checklist

### Cold Start
- [ ] onAdLoaded срабатывает через 5-7 секунд
- [ ] Логи показывают "PureColdStart"
- [ ] Token collection занимает 1-3 секунды
- [ ] READY_TO_SHOW заполнен после загрузки
- [ ] RTB_PAYLOAD заполнен (если есть RTB ads)

### Warm Start
- [ ] onAdLoaded срабатывает за <1 секунду (INSTANT)
- [ ] Логи показывают "WarmStart"
- [ ] Token collection пропускает cached adapters
- [ ] Dynamic pricefloor > 0.01 (защищает кэш)
- [ ] Фоновый аукцион продолжается (видно в логах)

### showAd()
- [ ] Показывается ad с максимальным eCPM
- [ ] Логи показывают "getBest()"
- [ ] Показанная реклама удаляется из READY_TO_SHOW
- [ ] Ongoing auction отменяется (если был)

### Lifecycle
- [ ] Periodic sweep запускается каждые 5 минут
- [ ] Expired ads удаляются из кэша
- [ ] AdSource.destroy() вызывается для expired ads
- [ ] WeakContextValidator проверяет context leaks
- [ ] LeakCanary не показывает leaks

### Performance
- [ ] Warm start <1 секунда
- [ ] Cold start 5-7 секунд
- [ ] Memory footprint <5MB
- [ ] No ANR (application not responding)

---

## 8. Automated Testing (Future)

**Note:** Unit tests отложены на v2.1. Этот раздел для справки.

### 8.1 Instrumentation Tests

```kotlin
@RunWith(AndroidJUnit4::class)
class AdCachingInstrumentationTest {

    @Test
    fun testColdStart_shouldTake5to7Seconds() {
        val startTime = System.currentTimeMillis()

        interstitialAd.loadAd()

        // Wait for onAdLoaded callback
        val latch = CountDownLatch(1)
        interstitialAd.setListener(object : InterstitialListener {
            override fun onAdLoaded() {
                latch.countDown()
            }
        })

        latch.await(10, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - startTime

        assertThat(elapsed).isInRange(5000L, 7000L)
    }

    @Test
    fun testWarmStart_shouldBeLessThan1Second() {
        // Prerequisite: fill cache
        interstitialAd.loadAd()
        waitForOnAdLoaded()

        // Test warm start
        val startTime = System.currentTimeMillis()
        interstitialAd.loadAd()
        waitForOnAdLoaded()
        val elapsed = System.currentTimeMillis() - startTime

        assertThat(elapsed).isLessThan(1000L)
    }
}
```

---

## 9. Test Report Template

После тестирования заполнить этот template:

```markdown
# Ad Caching v2 Test Report

**Date:** 2026-02-XX
**Tester:** [Your Name]
**Device:** Pixel 5 Emulator (API 33)
**Test App:** claude-in-mobile v1.0

## Test Results

| Scenario | Status | Notes |
|----------|--------|-------|
| Cold Start | ✅ PASS | onAdLoaded in 6.2s |
| Warm Start | ✅ PASS | onAdLoaded in 0.3s (INSTANT!) |
| showAd() getBest() | ✅ PASS | Showed highest eCPM ad |
| Periodic Sweep | ✅ PASS | Sweep executed at T+5min |
| Token Skip | ✅ PASS | Skipped 2 cached adapters |
| Dynamic Pricefloor | ✅ PASS | Calculated $4.50 correctly |
| Empty Waterfall | ✅ PASS | No error, used cached ad |
| Memory Leak | ✅ PASS | LeakCanary clean |
| Concurrent loadAd | ✅ PASS | Second call ignored |
| showAd Cancel | ✅ PASS | Auction cancelled |

## Performance Metrics

- Cold Start: 6.2s
- Warm Start: 0.3s
- Token Collection: 1.5s
- Memory Footprint: 3.2MB

## Issues Found

None ✓

## Conclusion

Ad caching v2 works as expected. All scenarios passed.
Ready for production.
```

---

## 10. Quick Reference

### Test App
```
https://github.com/AlexGladkov/claude-in-mobile
↑ Используйте это для UI тестирования
```

### Placement Key
```
1O16GQT380000  ← Interstitial test placement (ввести вручную в UI)
```

### Logcat Command
```bash
adb logcat -s BidonCache:D
```

### Cache State Logs
```
READY_TO_SHOW: [RTB $X, CPM $Y]
RTB_PAYLOAD:   [RTB $Z payload]
```

### Expected Timings
- Cold Start: 5-7 seconds
- Warm Start: <1 second (INSTANT!)
- Token Collection: 1-3 seconds

### Key Log Markers
- `determineStartState() → WarmStart` = warm start detected ✓
- `onAdLoaded() IMMEDIATE` = warm start optimization working ✓
- `Dynamic pricefloor = $X` = cache protection working ✓
- `Skipped N adapters` = token optimization working ✓

---

**Document Status:** Ready for Testing
**Test App:** claude-in-mobile (required for UI testing)
**Last Updated:** 2026-02-05
**Next Review:** After Phase 5 completion
