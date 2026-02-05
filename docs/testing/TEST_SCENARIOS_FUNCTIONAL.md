# Ad Caching v2 — Functional Test Scenarios

> **Version:** 1.0
> **Date:** 2026-02-05
> **Related:** [AD_CACHING_TESTING.md](../AD_CACHING_TESTING.md), [AD_CACHING_SPEC.md](../AD_CACHING_SPEC.md)

## Цель документа

Функциональные тест-кейсы для проверки основной функциональности ad caching v2 системы.

---

## 1. Cold Start Scenarios

### TC-COLD-001: Первый loadAd() без кэша (Pure Cold Start)

**Цель:** Проверить полный auction flow без кэша.

**Preconditions:**
- Fresh install приложения OR очищенные кэши
- READY_TO_SHOW.isEmpty() = true
- RTB_PAYLOAD.isEmpty() = true

**Steps:**
1. Запустить приложение на эмуляторе
2. Открыть раздел "Interstitial Ad"
3. Ввести placement key `1O16GQT380000`
4. Нажать "Load Ad"
5. Дождаться callback

**Expected Result:**
```
Timeline:
  T=0s:     loadAd() called
  T=0-2s:   Token collection (все 5 RTB адаптеров)
  T=2-3s:   POST /v2/auction/interstitial
  T=3-5s:   Parallel RTB + CPM loading
  T=5-7s:   onAdLoaded() callback ✓

Logs:
  [BidonCache] CoordinationLayer: determineStartState() → PureColdStart
  [BidonCache] CoordinationLayer: Dynamic pricefloor = 0.01 (default)
  [BidonCache] GetTokensUseCase: Collecting tokens from 5 adapters
  [BidonCache] GetTokensUseCase: Skipped 0 adapters (no cache)
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial
  [BidonCache] WaterfallSplitter: Split waterfall → RTB: 2, CPM: 3
  [BidonCache] RtbProcessor: Loading RTB[0] with eCPM $5.00
  [BidonCache] CpmProcessor: Loading CPM[0] with eCPM $4.50
  [BidonCache] RtbProcessor: SUCCESS → READY_TO_SHOW (RTB $5.00)
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $4.50)
  [BidonCache] CallbackCoordinator: onAdLoaded() fired (first fill)

Cache State After:
  READY_TO_SHOW: [RTB $5.00, CPM $4.50]
  RTB_PAYLOAD:   [RTB $3.00 payload]
```

**Validation:**
- ✅ onAdLoaded срабатывает через 5-7 секунд
- ✅ Логи показывают "PureColdStart"
- ✅ Token collection занимает 1-3 секунды
- ✅ READY_TO_SHOW заполнен после загрузки
- ✅ RTB_PAYLOAD содержит payload-ы RTB[1..N]

**Priority:** 🔴 HIGH
**Automation:** Manual (MCP claude-in-mobile)

---

### TC-COLD-002: Cold Start с user pricefloor

**Цель:** Проверить что user pricefloor корректно используется.

**Preconditions:**
- Кэши пусты
- User передаёт pricefloor = $2.00

**Steps:**
1. Очистить кэши (переустановка app)
2. Вызвать `loadAd(pricefloor = 2.00)`
3. Проверить логи

**Expected Result:**
```
Logs:
  [BidonCache] PricefloorCalculator: userPricefloor = $2.00
  [BidonCache] PricefloorCalculator: dynamicPricefloor = $0.01 (default)
  [BidonCache] PricefloorCalculator: finalPricefloor = max($2.00, $0.01) = $2.00
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial with pricefloor=$2.00
```

**Validation:**
- ✅ Backend получает pricefloor=$2.00 в запросе
- ✅ Backend НЕ возвращает ads с eCPM < $2.00

**Priority:** 🟡 MEDIUM

---

### TC-COLD-003: Cold Start с медленным token collection

**Цель:** Проверить поведение при медленном ответе адаптеров.

**Preconditions:**
- Кэши пусты
- Один или несколько адаптеров отвечают медленно (>3 секунды)

**Steps:**
1. Установить debug timeout для token collection = 10s
2. Запустить loadAd()
3. Наблюдать логи

**Expected Result:**
```
Logs:
  [BidonCache] GetTokensUseCase: Collecting tokens from 5 adapters
  [BidonCache] GetTokensUseCase: Adapter 'meta_an' timeout (10s)
  [BidonCache] GetTokensUseCase: Collected 4/5 tokens in 10.2s
  [BidonCache] AuctionRequest: POST with 4 tokens (1 missing)
```

**Validation:**
- ✅ Token collection завершается по timeout
- ✅ Аукцион продолжается с частичными tokens
- ✅ Медленный адаптер не блокирует весь процесс

**Priority:** 🟡 MEDIUM

---

### TC-COLD-004: Cold Start с network error

**Цель:** Проверить fallback при ошибке сети.

**Preconditions:**
- Кэши пусты
- Network disconnected OR backend недоступен

**Steps:**
1. Отключить WiFi/Mobile data
2. Вызвать loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AuctionRequest: POST /v2/auction/interstitial → NETWORK_ERROR
  [BidonCache] CallbackCoordinator: onAdLoadFailed(NO_CONNECTION)

Callback:
  onAdLoadFailed(cause = NO_CONNECTION)
```

**Validation:**
- ✅ onAdLoadFailed вызывается
- ✅ Причина ошибки корректная (NO_CONNECTION)
- ✅ Кэши остаются пустыми

**Priority:** 🔴 HIGH

---

## 2. Warm Start Scenarios

### TC-WARM-001: Немедленный onAdLoaded из READY_TO_SHOW

**Цель:** Проверить warm start optimization (<1 секунда).

**Preconditions:**
- Выполнен TC-COLD-001
- READY_TO_SHOW: [RTB $5.00, CPM $4.50]
- RTB_PAYLOAD: [RTB $3.00]

**Steps:**
1. НЕ вызывать showAd() после первого loadAd()
2. Подождать 2 секунды (дать аукциону завершиться)
3. Нажать "Load Ad" СНОВА
4. Засечь время до onAdLoaded

**Expected Result:**
```
Timeline:
  T=0s:     loadAd() called
  T=0.1s:   onAdLoaded() callback ✓ (INSTANT!)
  T=0-2s:   (фоновый аукцион продолжается)

Logs:
  [BidonCache] CoordinationLayer: determineStartState() → WarmStart
  [BidonCache] CoordinationLayer: READY_TO_SHOW.getBest() → RTB $5.00
  [BidonCache] CoordinationLayer: onAdLoaded() IMMEDIATE (warm start)
  [BidonCache] CoordinationLayer: Background auction starting...
  [BidonCache] GetTokensUseCase: Skipped 1 adapter (RTB $3.00 cached)
  [BidonCache] PricefloorCalculator: Dynamic pricefloor = $4.50 (0.9 * $5.00)

Cache State After Background Auction:
  READY_TO_SHOW: [RTB $6.00, RTB $5.00, CPM $4.50]  ← новый RTB добавлен
  RTB_PAYLOAD:   [RTB $3.00 payload]
```

**Validation:**
- ✅ onAdLoaded срабатывает за <1 секунду (INSTANT!)
- ✅ Логи показывают "WarmStart"
- ✅ Логи показывают "IMMEDIATE"
- ✅ Dynamic pricefloor = $4.50 (защита кэша)
- ✅ Фоновый аукцион продолжается (видно в логах)
- ✅ Token collection пропускает cached adapters

**Priority:** 🔴 HIGH (Основная фича!)

---

### TC-WARM-002: Warm Start с динамическим pricefloor

**Цель:** Проверить расчёт dynamic pricefloor на основе кэша.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00, CPM $4.50]
- RTB_PAYLOAD: [RTB $7.00 payload]

**Steps:**
1. Вызвать loadAd()
2. Проверить логи pricefloor calculation

**Expected Result:**
```
Logs:
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
- ✅ Dynamic pricefloor = $6.30 (0.9 * $7.00)
- ✅ Backend получает pricefloor=$6.30 в запросе
- ✅ Backend НЕ возвращает ads с eCPM < $6.30

**Priority:** 🔴 HIGH

---

### TC-WARM-003: Warm Start с user pricefloor выше dynamic

**Цель:** Проверить что user pricefloor имеет приоритет.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00]
- RTB_PAYLOAD: [RTB $3.00]
- User передаёт pricefloor = $10.00

**Steps:**
1. Вызвать `loadAd(pricefloor = 10.00)`
2. Проверить логи

**Expected Result:**
```
Logs:
  [BidonCache] PricefloorCalculator: dynamicPricefloor = $4.50 (0.9 * $5.00)
  [BidonCache] PricefloorCalculator: userPricefloor = $10.00
  [BidonCache] PricefloorCalculator: finalPricefloor = max($10.00, $4.50) = $10.00
  [BidonCache] AuctionRequest: POST with pricefloor=$10.00
```

**Validation:**
- ✅ finalPricefloor = $10.00 (user wins)
- ✅ Dynamic pricefloor НЕ игнорируется, а используется max()

**Priority:** 🟡 MEDIUM

---

### TC-WARM-004: Warm Start с пустым waterfall ответом

**Цель:** Проверить что пустой waterfall не ломает warm start.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00, CPM $4.50]
- Dynamic pricefloor = $4.50
- Backend возвращает empty waterfall (все ads отфильтрованы)

**Steps:**
1. Mock backend response: `{ "adUnits": [] }`
2. Вызвать loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] CoordinationLayer: onAdLoaded() IMMEDIATE (warm start)
  [BidonCache] AuctionRequest: Response received → 0 adUnits
  [BidonCache] WaterfallSplitter: Empty waterfall, skipping processing
  [BidonCache] CoordinationLayer: Empty waterfall OK (warm start already served)

Cache State:
  READY_TO_SHOW: [RTB $5.00, CPM $4.50]  ← unchanged
  RTB_PAYLOAD:   [RTB $3.00 payload]     ← unchanged
```

**Validation:**
- ✅ onAdLoaded вызван СРАЗУ (warm start)
- ✅ Empty waterfall НЕ вызывает onAdLoadFailed
- ✅ Кэш остаётся нетронутым
- ✅ showAd() работает (использует cached ads)

**Priority:** 🔴 HIGH

---

### TC-WARM-005: Token collection skip для cached networks

**Цель:** Проверить оптимизацию token collection.

**Preconditions:**
- RTB_PAYLOAD: [meta_an $3.00, bidmachine $2.50]
- Всего RTB адаптеров: 5

**Steps:**
1. Вызвать loadAd()
2. Проверить логи token collection

**Expected Result:**
```
Logs:
  [BidonCache] GetTokensUseCase: Total RTB adapters: 5
  [BidonCache] GetTokensUseCase: Cached demand IDs: [meta_an, bidmachine]
  [BidonCache] GetTokensUseCase: Collecting tokens from 3 adapters
  [BidonCache] GetTokensUseCase: Skipped 2 adapters:
    - meta_an (cached payload available)
    - bidmachine (cached payload available)
  [BidonCache] GetTokensUseCase: Collected 3 tokens in 1.2s
```

**Validation:**
- ✅ Token collection пропускает 2 адаптера
- ✅ Логи показывают "Skipped 2 adapters"
- ✅ Время token collection уменьшается (~1-2s вместо 2-3s)
- ✅ Backend НЕ получает tokens для cached networks

**Priority:** 🟡 MEDIUM

---

## 3. showAd() Scenarios

### TC-SHOW-001: showAd() выбирает ad с максимальным eCPM

**Цель:** Проверить логику getBest().

**Preconditions:**
- READY_TO_SHOW: [RTB $6.00, RTB $5.00, CPM $4.50]

**Steps:**
1. Нажать "Show Ad"
2. Проверить логи

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $6.00 (highest eCPM)
  [BidonCache] AdCacheDenisImpl: adSource.show() starting
  [BidonCache] AdCacheDenisImpl: show SUCCESS
  [BidonCache] AdCacheDenisImpl: Removing shown ad from READY_TO_SHOW
  [BidonCache] Statistics: Sending win notification for RTB $6.00

Cache State After:
  READY_TO_SHOW: [RTB $5.00, CPM $4.50]  ← RTB $6.00 удалён
  RTB_PAYLOAD:   [RTB $3.00 payload]
```

**Validation:**
- ✅ Показывается реклама с eCPM $6.00 (highest)
- ✅ Логи показывают "getBest() → RTB $6.00"
- ✅ Показанная реклама удаляется из READY_TO_SHOW
- ✅ Следующий showAd() покажет RTB $5.00

**Priority:** 🔴 HIGH

---

### TC-SHOW-002: showAd() без предварительного loadAd()

**Цель:** Проверить что showAd() использует кэш.

**Preconditions:**
- READY_TO_SHOW: [RTB $5.00] (из предыдущего сеанса)
- loadAd() НЕ вызывался

**Steps:**
1. Запустить app (кэш сохранился)
2. Сразу нажать "Show Ad" БЕЗ "Load Ad"
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: isReady() → true (cache not empty)
  [BidonCache] AdCacheDenisImpl: getBest() → RTB $5.00
  [BidonCache] AdCacheDenisImpl: show SUCCESS

Result:
  Ad displayed ✓
```

**Validation:**
- ✅ isReady() возвращает true
- ✅ Реклама показывается из кэша
- ✅ onAdLoaded НЕ вызывался

**Priority:** 🟡 MEDIUM

---

### TC-SHOW-003: showAd() с пустым кэшем

**Цель:** Проверить error handling.

**Preconditions:**
- READY_TO_SHOW.isEmpty() = true

**Steps:**
1. Очистить кэш
2. Вызвать showAd() без loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] AdCacheDenisImpl: showAd() called
  [BidonCache] AdCacheDenisImpl: getBest() → null (cache empty)
  [BidonCache] AdCacheDenisImpl: onAdShowFailed(NO_FILL)

Callback:
  onAdShowFailed(cause = NO_FILL)
```

**Validation:**
- ✅ onAdShowFailed вызывается
- ✅ Причина = NO_FILL
- ✅ Приложение не крашится

**Priority:** 🔴 HIGH

---

### TC-SHOW-004: showAd() отменяет ongoing auction

**Цель:** Проверить cancellation logic.

**Preconditions:**
- loadAd() запущен (аукцион в процессе)
- READY_TO_SHOW: [RTB $5.00] (из предыдущего)

**Steps:**
1. Нажать "Load Ad"
2. Подождать 1 секунду (аукцион в процессе)
3. Нажать "Show Ad"
4. Проверить логи

**Expected Result:**
```
Logs:
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
- ✅ Ongoing auction отменяется
- ✅ Логи показывают "Job cancelled"
- ✅ showAd() использует cached ad
- ✅ onAdLoaded НЕ срабатывает после cancellation

**Priority:** 🔴 HIGH

---

### TC-SHOW-005: Повторный showAd() после первого

**Цель:** Проверить показ нескольких ads из кэша.

**Preconditions:**
- READY_TO_SHOW: [RTB $6.00, RTB $5.00, CPM $4.50]

**Steps:**
1. Нажать "Show Ad" → должен показать RTB $6.00
2. Закрыть рекламу
3. Нажать "Show Ad" СНОВА → должен показать RTB $5.00
4. Закрыть рекламу
5. Нажать "Show Ad" СНОВА → должен показать CPM $4.50

**Expected Result:**
```
Show #1:
  Displayed: RTB $6.00 ✓
  Cache After: [RTB $5.00, CPM $4.50]

Show #2:
  Displayed: RTB $5.00 ✓
  Cache After: [CPM $4.50]

Show #3:
  Displayed: CPM $4.50 ✓
  Cache After: []
```

**Validation:**
- ✅ Каждый showAd() выбирает best ad
- ✅ Показанные ads удаляются из кэша
- ✅ Порядок соблюдается: $6 → $5 → $4.50

**Priority:** 🟡 MEDIUM

---

## 4. RTB Processing Scenarios

### TC-RTB-001: RTB[0] success, RTB[1..N] → payload cache

**Цель:** Проверить основной RTB flow.

**Preconditions:**
- Waterfall: [RTB $5.00, RTB $3.00, RTB $2.00]

**Steps:**
1. Запустить loadAd()
2. Дождаться завершения RTB processing
3. Проверить кэши

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: Loading RTB[0] with eCPM $5.00
  [BidonCache] RtbProcessor: SUCCESS → READY_TO_SHOW (RTB $5.00)
  [BidonCache] RtbProcessor: Saving RTB[1] → RTB_PAYLOAD ($3.00)
  [BidonCache] RtbProcessor: Saving RTB[2] → RTB_PAYLOAD ($2.00)

Cache State:
  READY_TO_SHOW: [RTB $5.00]
  RTB_PAYLOAD:   [RTB $3.00, RTB $2.00]
```

**Validation:**
- ✅ Только RTB[0] загружается
- ✅ RTB[1..N] сохраняются как payload
- ✅ onAdLoaded срабатывает после RTB[0] success

**Priority:** 🔴 HIGH

---

### TC-RTB-002: RTB[0] fail, fallback на RTB[1]

**Цель:** Проверить fallback logic.

**Preconditions:**
- Waterfall: [RTB $5.00 (fail), RTB $3.00 (success), RTB $2.00]

**Steps:**
1. Mock RTB[0] failure
2. Запустить loadAd()
3. Проверить что RTB[1] загружается

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: Loading RTB[0] with eCPM $5.00
  [BidonCache] RtbProcessor: FAILED → trying next RTB
  [BidonCache] RtbProcessor: Loading RTB[1] with eCPM $3.00
  [BidonCache] RtbProcessor: SUCCESS → READY_TO_SHOW (RTB $3.00)
  [BidonCache] RtbProcessor: Saving RTB[2] → RTB_PAYLOAD ($2.00)

Cache State:
  READY_TO_SHOW: [RTB $3.00]
  RTB_PAYLOAD:   [RTB $2.00]
```

**Validation:**
- ✅ RTB[1] загружается после fail RTB[0]
- ✅ RTB[2] сохраняется как payload
- ✅ Failed RTB НЕ попадает ни в READY_TO_SHOW, ни в RTB_PAYLOAD

**Priority:** 🔴 HIGH

---

### TC-RTB-003: Все RTB fail

**Цель:** Проверить graceful degradation.

**Preconditions:**
- Waterfall: [RTB $5.00 (fail), RTB $3.00 (fail)]
- CPM: [CPM $4.50 (success)]

**Steps:**
1. Mock все RTB failures
2. Запустить loadAd()
3. Проверить результат

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: Loading RTB[0] → FAILED
  [BidonCache] RtbProcessor: Loading RTB[1] → FAILED
  [BidonCache] RtbProcessor: All RTB failed, skipping RTB processing
  [BidonCache] CpmProcessor: Loading CPM[0] with eCPM $4.50
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $4.50)
  [BidonCache] CallbackCoordinator: onAdLoaded() fired (CPM fill)

Cache State:
  READY_TO_SHOW: [CPM $4.50]
  RTB_PAYLOAD:   []  ← empty
```

**Validation:**
- ✅ CPM processing не блокируется
- ✅ onAdLoaded срабатывает от CPM
- ✅ RTB_PAYLOAD остаётся пустым

**Priority:** 🟡 MEDIUM

---

### TC-RTB-004: Invalid payload в RTB_PAYLOAD cache

**Цель:** Проверить очистку невалидного payload.

**Preconditions:**
- RTB_PAYLOAD: [meta_an $3.00 payload (invalid)]
- Warm start

**Steps:**
1. Вызвать loadAd() (warm start)
2. System попытается использовать cached payload
3. adSource.load(payload) вернёт ошибку
4. Проверить что payload удаляется из кэша

**Expected Result:**
```
Logs:
  [BidonCache] RtbProcessor: Using cached payload for meta_an
  [BidonCache] RtbProcessor: adSource.load(payload) → FAILED (invalid payload)
  [BidonCache] RtbPayloadCache: Removing invalid entry for meta_an
  [BidonCache] GetTokensUseCase: meta_an NOT in skipList (cache cleared)

Next loadAd():
  → meta_an будет заново опрошен для токена
```

**Validation:**
- ✅ Invalid payload удаляется из RTB_PAYLOAD
- ✅ Следующий auction соберёт токен для этой сети
- ✅ Ошибка НЕ блокирует auction

**Priority:** 🟡 MEDIUM

---

## 5. CPM Processing Scenarios

### TC-CPM-001: Последовательная загрузка CPM

**Цель:** Проверить sequential processing.

**Preconditions:**
- Waterfall: [CPM $4.50, CPM $2.50, CPM $1.00]

**Steps:**
1. Запустить loadAd()
2. Наблюдать порядок загрузки в логах

**Expected Result:**
```
Logs:
  [BidonCache] CpmProcessor: Loading CPM[0] with eCPM $4.50
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $4.50)
  [BidonCache] CpmProcessor: Loading CPM[1] with eCPM $2.50
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $2.50)
  [BidonCache] CpmProcessor: Loading CPM[2] with eCPM $1.00
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $1.00)

Cache State:
  READY_TO_SHOW: [CPM $4.50, CPM $2.50, CPM $1.00]
```

**Validation:**
- ✅ CPM загружаются последовательно (один за другим)
- ✅ Все success CPM попадают в READY_TO_SHOW
- ✅ onAdLoaded срабатывает при первом fill

**Priority:** 🔴 HIGH

---

### TC-CPM-002: CPM fail → skip, продолжить следующий

**Цель:** Проверить error handling.

**Preconditions:**
- Waterfall: [CPM $4.50 (fail), CPM $2.50 (success), CPM $1.00]

**Steps:**
1. Mock CPM[0] failure
2. Запустить loadAd()
3. Проверить что CPM[1] загружается

**Expected Result:**
```
Logs:
  [BidonCache] CpmProcessor: Loading CPM[0] with eCPM $4.50
  [BidonCache] CpmProcessor: FAILED → skip, continue to next
  [BidonCache] CpmProcessor: Loading CPM[1] with eCPM $2.50
  [BidonCache] CpmProcessor: SUCCESS → READY_TO_SHOW (CPM $2.50)
  [BidonCache] CallbackCoordinator: onAdLoaded() fired

Cache State:
  READY_TO_SHOW: [CPM $2.50]
```

**Validation:**
- ✅ Failed CPM пропускается
- ✅ Следующий CPM загружается
- ✅ onAdLoaded срабатывает от CPM[1]

**Priority:** 🔴 HIGH

---

### TC-CPM-003: Weight Model сортировка

**Цель:** Проверить базовую weight model.

**Preconditions:**
- Historical data:
  - unity_ads: fill_rate=0.8, avg_ecpm=$3.00
  - admob: fill_rate=0.6, avg_ecpm=$4.00
- Waterfall: [admob $4.00, unity_ads $3.00]

**Steps:**
1. Запустить loadAd()
2. Проверить что weight model переупорядочивает CPM

**Expected Result:**
```
Logs:
  [BidonCache] CpmWeightCalculator: Sorting CPM by weight
  [BidonCache] CpmWeightCalculator: unity_ads score = $3.00 * 0.8 = 2.4
  [BidonCache] CpmWeightCalculator: admob score = $4.00 * 0.6 = 2.4
  [BidonCache] CpmWeightCalculator: Order unchanged (tie in scores)

  OR (если unity_ads имеет fill_rate=0.9):
  [BidonCache] CpmWeightCalculator: unity_ads promoted (score 2.7 > 2.4)
```

**Validation:**
- ✅ Weight model применяется
- ✅ CPM с высоким fill rate приоритизируются
- ✅ Логи показывают score calculation

**Priority:** 🟢 LOW (Базовая реализация)

---

## Summary

**Total Test Cases:** 25
**Priority Distribution:**
- 🔴 HIGH: 16 (64%)
- 🟡 MEDIUM: 8 (32%)
- 🟢 LOW: 1 (4%)

**Coverage:**
- Cold Start: 4 test cases
- Warm Start: 5 test cases
- showAd(): 5 test cases
- RTB Processing: 4 test cases
- CPM Processing: 3 test cases

---

**Document Status:** Complete
**Last Updated:** 2026-02-05
**Next Steps:** Execute test cases on emulator with claude-in-mobile
