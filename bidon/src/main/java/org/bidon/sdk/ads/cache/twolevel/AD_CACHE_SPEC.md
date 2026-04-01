# Two-Level Cache — Ad Caching Strategy

## 1. Overview

Two-Level Cache — стратегия кеширования рекламы с двумя уровнями хранения: **Main Cache** и **Fallback Cache**.

Биды аукциона последовательно опрашиваются от дорогих к дешёвым. Порядок заполнения:

1. **Main** — принимает биды до capacity. Threshold отсекает слишком дешёвые — Main может заполниться не полностью.
2. **Fallback** — отклонённые Main-ом (по threshold или capacity) заполняют Fallback. При повторных аукционах вытесняет дешёвые биды более дорогими.
3. **Стоп** — когда Main не принимает **и** Fallback не может принять — опрос **прекращается**.

При следующем `loadAd()` реклама отдаётся мгновенно из кеша без повторного аукциона.

**Форматы:** Interstitial, Banner. Rewarded не поддерживается.

**Ограничение:** pricefloor фиксирован для ad cache. Один auctionKey = один pricefloor. Аукцион запускается только когда Main **пуст**.

Водопад **отсортирован по цене** (дорогие первые). Первый бид — самый дорогой.

---

## 2. Configuration

Задаётся с сервера, отдельно для каждого формата.

| Параметр | Диапазон | Default | Описание |
| --- | --- | --- | --- |
| `adunitCacheSize` | 1–10 | 2 | Ёмкость Main Cache |
| `fallbackCacheSize` | 0–10 | 1 | Ёмкость Fallback. **0 = отключён** |
| `threshold` | 0–100 | 80 | % от maxPrice для фильтрации дешёвых бидов |

---

## 3. Main Cache

Упорядоченный массив по цене (дорогие первые). Ёмкость = `adunitCacheSize`.

| Операция | Описание |
| --- | --- |
| `peek()` | Лучший элемент без удаления |
| `popFirst()` | Извлечь лучший. Sticky режим снимается |
| `insert(ad, sticky)` | Вставить с валидацией. Отклонённые → Fallback |

### 3.1 Sticky Head

Первый бид аукциона вставляется как **sticky** — защищён от вытеснения. Гарантирует что бид, отданный в `didLoad`, будет доступен при `show()`.

- При `popFirst()` sticky режим **снимается**.
- При `capacity == 1` + sticky: все не-sticky вставки отклоняются.

### 3.2 Threshold

Порог определяет, может ли бид попасть в Main (при наличии хотя бы одного элемента):

```
thresholdBar = mainCache.maxPrice × (threshold / 100)

bid.price >= thresholdBar → принять (если есть место)
bid.price <  thresholdBar → REJECTED → Fallback
```

Первый бид (пустой кеш) всегда принимается — он задаёт maxPrice.

Крайние случаи:
- **threshold=0** → bar=0, все биды проходят
- **threshold=100** → bar=maxPrice, только биды >= maxPrice проходят (фактически только первый)
- **capacity=1 + sticky** → все не-sticky отклоняются (sticky protection, threshold не проверяется)

### 3.3 Алгоритм insert

```
insert(element, sticky):
  1. Пустой кеш → принять
  2. capacity==1 + sticky active + не sticky → REJECTED
  3. price < thresholdBar → REJECTED
  4. Дубликат (same demandId) → удалить старый, вставить новый
  5. INSERT + sort (sticky stays at head)
```

Main заполняется один раз за аукцион. Caller проверяет `!isFull` перед insert — вытеснения нет.

---

## 4. Fallback Cache

Упорядоченный массив по цене **без sticky и threshold**. Ёмкость = `fallbackCacheSize`.

Бид попадает в Fallback при отклонении Main-ом.

**Заполнение:** если есть место — вставляется. Если Fallback полон — **вытесняет самый дешёвый**, при условии что новый строго дороже (`price > cheapest.price`). Равная цена не вытесняет. Вытесненный уничтожается.

Вытеснение важно при повторных аукционах: Fallback может содержать дешёвые биды от прошлого раунда, а новый аукцион приносит более дорогие отклонённые Main-ом.

При `fallbackCacheSize = 0`: Fallback отключён, отклонённые биды уничтожаются.

---

## 5. Pricefloor

**Не проверяется при peek/pop** — pricefloor фиксирован и одинаков для всех аукционов данного auctionKey. Аукцион сам фильтрует по pricefloor, в кеш попадают только прошедшие биды.

---

## 6. State Machine

```
.idle → .auction → .ready → .idle
```

State определяется по **Main Cache** (не Fallback). Fallback — резерв на случай no-fill.

| State | Описание | loadAd() |
| --- | --- | --- |
| `.idle` | Main пуст, аукцион не идёт | Запускает аукцион |
| `.auction` | Аукцион в процессе | Silent return (нет колбэка) |
| `.ready` | Бид доступен (Main или Fallback) | Мгновенный ответ из Main (bypass state) |

Переходы:
- `.idle` → `.auction` — loadAd() при пустом Main (Fallback может быть не пуст)
- `.auction` → `.ready` — первый бид зафилился (didLoad)
- `.auction` → `.ready` — no-fill, но Fallback имеет бид (didLoad из Fallback)
- `.auction` → `.idle` — no-fill и Fallback пуст (didFailToLoad)
- `.ready` → `.ready` — show() при непустом кеше (аукцион может ещё работать в фоне)
- `.ready` → `.idle` — show() опустошил последний элемент

`loadAd()` при state=`.ready` всегда возвращает из кеша, не проверяя state.

---

## 7. loadAd()

```
loadAd()
  │
  ├── 1. Main.peek() != null?
  │     → YES: didLoad (мгновенно из Main)
  │
  ├── 2. state == .auction? → silent return (нет колбэка)
  │
  ├── 3. Запрос конфигурации с сервера
  │     ├── failure → Fallback.peek() != null?
  │     │               → YES: didLoad (из Fallback)
  │     │               → NO:  didFailToLoad
  │     └── success → performAuction()
  │
  ├── 4. performAuction() — см. псевдокод ниже
  │     │  Первый бид → didLoad
  │     │  Остальные → молча кешируются
  │     │
  │     └── no-fill → Fallback.peek() != null?
  │                     → YES: didLoad (из Fallback)
  │                     → NO:  didFailToLoad
  │
  └── 5. Отправить auction report
```

### Псевдокод опроса водопада

Pre-filter использует `adUnit.ecpm` (ожидаемая цена из водопада) для раннего прекращения опроса. Это оптимизация — реальная fill-цена может отличаться.

```
performAuction(adUnits, mainCache, fallbackCache):
  isFirstFill = true
  mainBar = null  // порог Main, вычисляется после первого fill

  for adUnit in adUnits:  // отсортированы по ecpm desc

    // --- PRE-FILTER: стоит ли опрашивать? ---
    canAcceptMain = !mainCache.isFull
                    AND (mainBar == null OR adUnit.ecpm >= mainBar)

    canAcceptFallback = !fallbackCache.isDisabled
                        AND (!fallbackCache.isFull
                             OR adUnit.ecpm > fallbackCache.cheapest)

    if !canAcceptMain AND !canAcceptFallback:
      markRemaining(LOSE)
      break

    // --- LOAD ---
    result = loadAdUnit(adUnit)
    if result == NO_FILL:
      continue

    // --- ROUTE filled bid ---
    bid = result.bid

    // Попытка Main
    if !mainCache.isFull:
      if mainBar == null OR bid.price >= mainBar:
        mainCache.insert(bid, sticky = isFirstFill)
        mainBar = mainCache.maxPrice * (threshold / 100)

        if isFirstFill:
          didLoad(bid, status = WIN)
          isFirstFill = false
        else:
          markStatus(bid, CACHE)
        continue

    // Main не принял → Fallback
    if fallbackCache.isDisabled:
      bid.destroy()
      markStatus(bid, LOSE)
      continue

    if !fallbackCache.isFull:
      fallbackCache.insert(bid)
      markStatus(bid, CACHE)
    else if bid.price > fallbackCache.cheapest:
      evicted = fallbackCache.evictCheapest()
      evicted.destroy()
      fallbackCache.insert(bid)
      markStatus(bid, CACHE)
    else:
      bid.destroy()
      markStatus(bid, LOSE)
```

---

## 8. show()

```
show()
  → pop(Main ?? Fallback)
  → показ
  → cancel auction (если ещё идёт в фоне)
```

Уже закешированные биды **не теряются** при cancel.

---

## 9. Статусы бидов

Новый статус `CACHE` для ad caching логики. Используется в stats (auction report) и в AdUnitInfo.

| Статус | Когда | Где |
| --- | --- | --- |
| **WIN** | Первый (sticky) бид аукциона. Отдаётся в didLoad. Всегда ровно один. | stats + AdUnitInfo |
| **CACHE** | Бид зафилился и положился в Main или Fallback (не первый). | stats + AdUnitInfo |
| **LOSE** | Бид не попал никуда или demand не опрошен. | stats |

При cache hit (без аукциона) — бид отдаётся со status=WIN.

RoundStatus mapping: WIN → `RoundStatus.Win`, CACHE → `RoundStatus.Cached` (новый), LOSE → `RoundStatus.Lose`.

---

## 10. AuctionInfo

При каждом аукционе формируется AuctionInfo с результатами.

```
AuctionInfo:
  auctionId, auctionConfigurationId, auctionConfigurationUid,
  auctionPricefloor, timeout,
  noBids: [AdUnitInfo]?,  adUnits: [AdUnitInfo]?
```

| Кейс | AuctionInfo |
| --- | --- |
| Cache hit (Main.peek) | Synthetic: 1 adUnit status=WIN |
| First bid (аукцион) | Synthetic: 1 adUnit status=WIN |
| Auction complete | Полный: все adUnits из pipeline |
| No-fill → Fallback delivery | Pipeline AuctionInfo (все failed) |
| No-fill → no Fallback | Pipeline AuctionInfo (все failed) |

При каждом новом аукционе adUnits **перезаписываются** результатами последнего.

---

## 11. Управление аукционом

Опрос прекращается когда ни один кеш не может принять следующий бид:

```
canAcceptMain     = Main не полон И бид проходит threshold
canAcceptFallback = Fallback не отключён И (не полон ИЛИ бид дороже cheapest)

!canAcceptMain AND !canAcceptFallback → СТОП
```

С отсортированным водопадом стоп необратим — все последующие ещё дешевле.

**При show():** `auction.cancel()` — результат доставлен, опрос не нужен.

| Конфигурация | Поведение |
| --- | --- |
| cacheSize=1, fallbackSize=0 | Стоп после первого бида |
| cacheSize=N, fallbackSize=0 | Main заполняется, стоп когда полон или threshold reject |
| cacheSize=1, fallbackSize=M | Main=первый бид, остальные в Fallback |
| cacheSize=N, fallbackSize=M | Main→Fallback→стоп когда оба не принимают |
| show() во время аукциона | Немедленный cancel |

---

## 12. Manager Pool

Глобальный пул хранит менеджеры по `auctionKey`. Один менеджер на ключ.

**Автоочистка** каждые 60 сек: idle + простой > 5 мин + нет объекта → удаление.

---

## 13. Потокобезопасность

| Компонент | Механизм |
| --- | --- |
| Main / Fallback Cache | Мьютекс |
| Manager Pool | Мьютекс |
| Колбэки | Главный поток |

---

## 14. Примеры

### 14.1 cacheSize=3, threshold=70, fallbackSize=2

```
Водопад: $10, $9, $8, $7, $5, $3

loadAd():
  Оба кеша пусты → аукцион.

  $10 (sticky) → Main [$10*]                           WIN, didLoad
    mainBar = $10 * 0.7 = $7

  $9  → $9 >= $7 → Main [$10*][$9]                      CACHE
  $8  → $8 >= $7 → Main [$10*][$9][$8] FULL              CACHE

  $7  → Main full. canAcceptFallback? Fb empty → YES.
        → Fallback [$7]                                  CACHE

  $5  → Main full. Fb not full. → Fallback [$7][$5]      CACHE

  $3  → Main full. Fb full, $3 <= $5 → canAccept=false. СТОП.  LOSE

Серия показов:
  load→$10 (instant). show→pop.
  load→$9 (instant). show→pop.
  load→$8 (instant). show→pop.
  load→Main пуст→auction→no-fill→Fallback $7→didLoad. show→Fb pop.
  load→Main пуст→auction→no-fill→Fallback $5→didLoad. show→Fb pop.
  load→оба пусты→аукцион.
```

### 14.2 cacheSize=1, threshold=80, fallbackSize=3

```
Водопад: $5, $4, $3, $2

  $5 (sticky) → Main [$5*]                     WIN, didLoad
    mainBar = $5 * 0.8 = $4

  $4  → Main full (capacity=1 + sticky).
        → Fallback [$4]                          CACHE
  $3  → Main full. → Fallback [$4][$3]           CACHE
  $2  → Main full. → Fallback [$4][$3][$2] FULL  CACHE
  → водопад исчерпан (если бы были ещё — СТОП, оба не принимают)

  show→$5. load→Main пуст→auction→no-fill→Fallback $4→didLoad. show→$4.
  Ещё 2 показа через auction→no-fill→Fallback.
```

### 14.3 show() во время аукциона

```
Config: cacheSize=3, threshold=70, fallbackSize=2
Водопад: $8, $7, $6, $4, $3, $2

  $8 (sticky) → Main [$8*]    WIN, didLoad. Аукцион продолжает.
    mainBar = $8 * 0.7 = $5.60
  $7 → $7 >= $5.60 → Main [$8*][$7]           CACHE
  $6 → $6 >= $5.60 → Main [$8*][$7][$6] FULL  CACHE

  show() → pop $8, auction.cancel()
  $4, $3, $2 — НЕ ОПРАШИВАЮТСЯ

  Main: [$7][$6]. Сэкономлено 3 запроса.
  Далее 2 показа из кеша без аукционов.
```

### 14.4 Main не заполнен (threshold отсекает)

```
Config: cacheSize=3, threshold=80, fallbackSize=2
Водопад: $10, $7, $5, $3

  $10 (sticky) → Main [$10*]                   WIN, didLoad
    mainBar = $10 * 0.8 = $8

  $7  → $7 < $8 → Main reject. → Fallback [$7]          CACHE
  $5  → $5 < $8 → Main reject. → Fallback [$7][$5]       CACHE
  $3  → Main reject ($3 < $8). Fb full, $3 <= $5. СТОП.  LOSE

  Main: [$10*] (1/3).  Fallback: [$7][$5] (2/2).

  load→$10 (instant). show→pop. Main пуст.
  load→Main пуст→auction→no-fill→Fallback $7→didLoad. show→Fb pop.
  load→Main пуст→auction→no-fill→Fallback $5→didLoad. show→Fb pop.
  load→оба пусты→аукцион.
```

### 14.5 Повторный аукцион обновляет Fallback

```
Config: cacheSize=1, threshold=80, fallbackSize=2

Аукцион 1, водопад: $5, $3, $2
  $5 (sticky) → Main [$5*]         WIN, didLoad
  $3 → Main full → Fallback [$3]                CACHE
  $2 → Fallback [$3][$2] FULL. СТОП.            CACHE

show→$5. Main пуст.
load→Main пуст→auction→no-fill→Fallback $3→didLoad. show→Fb pop. Fallback: [$2]
load→Main пуст→auction→no-fill→Fallback $2→didLoad. show→Fb pop. Fallback: [].
load→оба пусты → аукцион 2.

Аукцион 2, водопад: $6, $4, $3.50
  $6 (sticky) → Main [$6*]         WIN, didLoad
  $4 → Main full → Fallback []     → Fallback [$4]        CACHE
  $3.50 → Main full → Fallback [$4][$3.50] FULL. СТОП.    CACHE

  Main: [$6*], Fallback: [$4][$3.50]
```

### 14.6 Повторный аукцион: Fallback не пуст → вытеснение

Fallback может содержать дешёвые биды от прошлого аукциона. Новый аукцион заполняет Main, а отклонённые по threshold биды дороже старых в Fallback → вытесняют.

Вытеснение возможно когда Fallback **не был полностью опустошён** между аукционами — например пользователь показал только Main, а Fallback не понадобился (аукцион при no-fill нашёл бы Fallback, но аукцион дал fill и заполнил Main).

```
Config: cacheSize=1, threshold=80, fallbackSize=2

Аукцион 1, водопад: $5, $2, $1
  $5 (sticky) → Main [$5*]                    WIN, didLoad
    mainBar = $5 * 0.8 = $4
  $2 → $2 < $4 → Main reject → Fallback [$2]          CACHE
  $1 → Main reject → Fallback [$2][$1] FULL. СТОП.    CACHE

  show→$5. Main пуст.
  loadAd → Main пуст → auction 2 (Fallback [$2][$1] не тронут)

Аукцион 2, водопад: $10, $5, $4
  $10 (sticky) → Main [$10*]                  WIN, didLoad
    mainBar = $10 * 0.8 = $8
  $5 → $5 < $8 → Main reject.
       Fallback: [$2][$1] full. $5 > $1 → вытеснить $1, destroy.
       Fallback [$5][$2]                               CACHE
  $4 → $4 < $8 → Main reject.
       Fallback: [$5][$2] full. $4 > $2 → вытеснить $2, destroy.
       Fallback [$5][$4]                               CACHE
  → СТОП (Main reject + Fb full + следующий ещё дешевле)

  Main: [$10*], Fallback: [$5][$4]
  Fallback обновился: [$2][$1] → [$5][$4] (оба вытеснены)
```

### 14.7 cacheSize=1, fallbackSize=0

```
Водопад: $5, $3, $2

  $5 (sticky) → Main [$5*]    WIN, didLoad.
  Main full + Fallback disabled → СТОП. $3, $2 не опрашиваются (LOSE).

  show→$5. Main пуст → новый аукцион.
```

### 14.8 threshold=0 (фильтрация отключена)

```
Config: cacheSize=3, threshold=0, fallbackSize=0
Водопад: $10, $1, $0.01

  $10 (sticky) → Main [$10*]      mainBar=0
  $1           → $1 >= 0 → Main [$10*][$1]
  $0.01        → $0.01 >= 0 → Main [$10*][$1][$0.01] FULL
  Main full + Fallback disabled → СТОП.
```

---

## 15. Кейсы

### Кейс 1: Два последовательных loadAd()

| Ситуация | Результат |
| --- | --- |
| Аукцион идёт, кеш пуст | Silent return (нет колбэка) |
| Main не пуст | Мгновенный ответ из Main |
| Main пуст, Fallback не пуст | Аукцион → no-fill → Fallback |
| Оба пусты, state=idle | Новый аукцион |

### Кейс 2: Fallback спасает no-fill

```
cacheSize=2, threshold=70, fallbackSize=2.  Водопад: $5, $2

  $5 → Main (WIN, didLoad).
  $2 → threshold reject → Fallback (CACHE).
  show→$5. load→Main пуст→auction→no-fill→Fallback $2→didLoad.
```
