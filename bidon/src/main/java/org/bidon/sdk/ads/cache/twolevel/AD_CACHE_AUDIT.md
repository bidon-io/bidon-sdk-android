# Two-Level Cache — Audit: Spec vs Implementation

Дата: 2026-04-01
Ветка: `feature/ad-caching-two-level`

---

## Сводка

| # | Severity | Проблема | Файлы |
|---|----------|----------|-------|
| 1 | **Critical** | Pre-filter не учитывает eviction в Fallback | `TwoLevelAdManager.kt:88`, `SequentialAuctionPipeline.kt:162` |
| 2 | **Critical** | Двойная проверка Fallback → пользователь не получает колбэк | `TwoLevelAuctionController.kt:71-95`, `TwoLevelAdManager.kt:91-101` |
| 3 | Medium | Pricefloor проверяется при peek Fallback | `TwoLevelAuctionController.kt:82` |
| 4 | Medium | Статус CACHE теряется в stats pipeline | `SequentialAuctionPipeline.kt:361-367`, `AuctionStatImpl.kt:78-115` |
| 5 | Minor | Условие cleanup в ManagerPool отличается от спеки | `ManagerPool.kt:131-138` |

---

## 1. CRITICAL: Pre-filter не учитывает Fallback eviction

### Спека (§7, §11, §14.6)

Pre-filter перед загрузкой каждого adUnit:

```
canAcceptMain     = !mainCache.isFull
                    AND (mainBar == null OR adUnit.ecpm >= mainBar)

canAcceptFallback = !fallbackCache.isDisabled
                    AND (!fallbackCache.isFull
                         OR adUnit.ecpm > fallbackCache.cheapest)

if !canAcceptMain AND !canAcceptFallback:
  markRemaining(LOSE)
  break
```

Fallback может принять бид через **вытеснение** даже когда `isFull == true`, если `ecpm > cheapest`.

### Реализация

`TwoLevelAdManager.kt:86-89`:

```kotlin
shouldContinueAuction = {
    !(mainCache.isFull && fallbackCache.isFull)
}
```

Лямбда **не имеет доступа** к `adUnit.ecpm` и `fallbackCache.cheapestPrice`. Когда оба кеша at capacity — стоп. Eviction-возможность Fallback не проверяется.

Также отсутствует threshold-проверка для Main: если Main не полон, но все оставшиеся биды ниже threshold — реализация продолжает опрос впустую (не баг, но лишние запросы).

### Воспроизведение (спека §14.6)

```
Config: cacheSize=1, threshold=80, fallbackSize=2

Аукцион 1: $5, $2, $1
  $5 → Main [$5*]
  $2 → Fallback [$2]
  $1 → Fallback [$2][$1]

show→$5. Main пуст.
loadAd → аукцион 2 (Fallback [$2][$1] — остатки от аукциона 1)

Аукцион 2: $10, $5, $4
  $10 → Main [$10*]. mainBar = $8.

  Далее shouldContinueAuction():
    mainCache.isFull = true
    fallbackCache.isFull = true ([$2][$1] at capacity)
    → СТОП!

  Ожидание по спеке:
    $5 → Main reject → Fb: $5 > $1 → evict $1 → Fallback [$5][$2]
    $4 → Main reject → Fb: $4 > $2 → evict $2 → Fallback [$5][$4]
```

**Результат:** Fallback остаётся [$2][$1] вместо [$5][$4]. Пользователь получает дешёвые биды вместо дорогих.

### Предложение

Изменить `shouldContinueAuction` на параметризованную лямбду `(ecpm: Double) -> Boolean` или вынести логику в pipeline:

```kotlin
// В SequentialAuctionPipeline, перед загрузкой:
val canAcceptMain = !mainCache.isFull
    && (mainBar == null || adUnit.ecpm >= mainBar)

val canAcceptFallback = !fallbackCache.isDisabled
    && (!fallbackCache.isFull || adUnit.ecpm > (fallbackCache.cheapestPrice ?: 0.0))

if (!canAcceptMain && !canAcceptFallback) break
```

---

## 2. CRITICAL: Двойная проверка Fallback → потеря колбэка

### Спека (§7)

Fallback-delivery при no-fill — ответственность менеджера:

```
performAuction() → no-fill
  → Fallback.peek() != null?
    → YES: didLoad (из Fallback)
    → NO:  didFailToLoad
```

### Реализация — два конфликтующих обработчика

**Controller** (`TwoLevelAuctionController.kt:71-95`):

```kotlin
private suspend fun handlePipelineFailure(...) {
    val fallbackAd = fallbackCache.peek()
    if (fallbackAd != null && fallbackAd.adSource.getStats().price >= pricefloor) {
        // "Всё ок, Fallback спасёт"
        onComplete(auctionInfo, null)  // error = null
        return
    }
    onComplete(null, error)
}
```

**Manager** (`TwoLevelAdManager.kt:91-101`):

```kotlin
onComplete = { auctionInfo, error ->
    if (error != null && !firstFillFired.get()) {
        // Fallback delivery
        val fallbackAd = fallbackCache.peek()
        if (fallbackAd != null) {
            onSuccess(fallbackAd, info)
        } else {
            onFailure(auctionInfo, error)
        }
    }
    // error == null && firstFillFired == false → НИЧЕГО НЕ ПРОИСХОДИТ
}
```

### Баг-путь

1. Pipeline завершается с ошибкой (no-fill), `firstFillFired = false`
2. Controller находит Fallback ad → вызывает `onComplete(info, null)` — **маскирует ошибку**
3. Manager проверяет `error != null` → `false` → блок пропущен
4. **Ни `onSuccess`, ни `onFailure` не вызван** — пользователь не получает колбэк

### Предложение

Убрать Fallback-проверку из Controller — она дублирует и конфликтует с Manager:

```kotlin
// TwoLevelAuctionController.start — упростить:
onComplete = { auctionInfo, error ->
    // Просто проксировать к Manager, без handlePipelineFailure
    onComplete(auctionInfo, error)
}
```

Или: Manager должен обработать случай `error == null && !firstFillFired`:

```kotlin
onComplete = { auctionInfo, error ->
    if (!firstFillFired.get()) {
        if (error != null) {
            // no-fill path
        } else {
            // Controller нашёл Fallback — deliver
        }
    }
}
```

---

## 3. MEDIUM: Pricefloor проверяется при peek Fallback

### Спека (§5)

> Не проверяется при peek/pop — pricefloor фиксирован и одинаков для всех аукционов данного auctionKey. Аукцион сам фильтрует по pricefloor, в кеш попадают только прошедшие биды.

### Реализация

`TwoLevelAuctionController.kt:82`:

```kotlin
if (fallbackAd != null && fallbackAd.adSource.getStats().price >= pricefloor) {
```

Проверка `price >= pricefloor` лишняя — биды в кеше уже прошли pricefloor в своём аукционе. Противоречит спеке. На практике безвредна (pricefloor фиксирован), но:

- Усложняет код
- Участвует в баге #2 (часть handlePipelineFailure, который нужно убрать)

### Предложение

Убрать pricefloor-проверку вместе с `handlePipelineFailure` (см. #2).

---

## 4. MEDIUM: Статус CACHE теряется в stats pipeline

### Спека (§9)

| Статус | Когда |
|--------|-------|
| **WIN** | Первый (sticky) бид. Всегда один. |
| **CACHE** | Бид зафилился и положился в Main или Fallback (не первый). |
| **LOSE** | Бид не попал никуда. |

### Реализация — цепочка потери статуса

**Шаг 1:** Pipeline заполняет бид (`SequentialAuctionPipeline.kt:355-367`):

```kotlin
adSource.markFillFinished(RoundStatus.Successful, price)       // stat → Successful
val auctionResult = AuctionResult.Network(adSource, RoundStatus.Successful)
resultsCollector.add(auctionResult)                             // AuctionResult.roundStatus = Successful
return auctionResult
```

**Шаг 2:** Manager роутит бид (`TwoLevelAdManager.kt:134`):

```kotlin
winner.adSource.markFillFinished(RoundStatus.Cached, price)    // stat → Cached
```

Обновляет **adSource stat**, но `AuctionResult.roundStatus` — `val`, остаётся `Successful`.

**Шаг 3:** Stats собирает результаты (`AuctionStatImpl.kt:78-115`):

```kotlin
val results = roundResults
    .map { it.asStatsAdUnit() }  // Читает AuctionResult.roundStatus → "INTERNAL_STATUS"
    .map { statsAdUnit ->
        if (winnerUuid == currentUuid) {
            statsAdUnit.copy(status = RoundStatus.Win.code)     // → WIN (корректно)
        } else if (bidType == RTB && status == Successful.code) {
            statsAdUnit.copy(status = RoundStatus.Lose.code)    // → LOSE (должно быть CACHE)
        } else {
            statsAdUnit                                          // → "INTERNAL_STATUS" (должно быть CACHE)
        }
    }
```

### Результат

| Бид | Ожидание (спека) | Реальность |
|-----|------------------|------------|
| Первый (sticky) | WIN | WIN |
| Cached, RTB | CACHE | LOSE |
| Cached, CPM | CACHE | INTERNAL_STATUS |

Статус `CACHE` никогда не попадает в auction report.

### Корень проблемы

`AuctionResult` — immutable data class с `val roundStatus`. Pipeline создаёт его с `Successful` до routing. Manager обновляет adSource stat, но AuctionResult в resultsCollector не обновляется.

`AuctionStatImpl.addRoundResults` проектировался для single-winner аукциона, не для multi-fill кеширования.

### Предложение

Вариант A — использовать adSource stat вместо AuctionResult.roundStatus:

```kotlin
// AuctionStatImpl.asStatsAdUnit()
is AuctionResult.Network -> {
    val stat = adSource.getStats()
    StatsAdUnit(
        status = stat.roundStatus?.code ?: roundStatus.code,  // Приоритет: stat, потом AR
        ...
    )
}
```

Вариант B — перенести `resultsCollector.add()` в routeBidToCache, после установки финального статуса.

---

## 5. MINOR: Условие cleanup в ManagerPool отличается от спеки

### Спека (§12)

> Автоочистка каждые 60 сек: idle + простой > 5 мин + нет объекта → удаление.

Три условия через AND: idle AND idle_time > 5 min AND weak ref dead.

### Реализация

`ManagerPool.kt:131-138`:

```kotlin
val isIdle = manager?.isIdle() ?: true
val isOldEnough = (now - entry.createdAt) > IDLE_TTL_MS
isIdle && (isOldEnough || isWeakRefDead)  // OR вместо AND
```

Отличия:
- `OR` вместо `AND` между возрастом и weak ref — более агрессивная очистка
- `createdAt` — время создания entry, не время начала idle
- Удаляет live менеджеры если они idle > 5 мин (даже если на них есть ссылка)

### Влияние

Минимальное. Более агрессивная очистка может привести к лишнему пересозданию менеджеров, но не к потере данных (кеши привязаны к менеджеру, а не к pool entry).

---

## Проверено и соответствует спеке

| Раздел спеки | Что проверено |
|--------------|---------------|
| §2 Configuration | Defaults 2/1/80, ranges coerceIn, JSON parsing |
| §3.1 Sticky Head | Первый бид pinned, popFirst снимает sticky |
| §3.2 Threshold | Формула `maxPrice * (threshold/100.0)`, пустой кеш пропускает |
| §3.3 Insert алгоритм | 5 шагов, no eviction, duplicate handling |
| §4 Fallback Cache | Strict `>` для eviction, destroy, disabled при capacity=0 |
| §6 State Machine | idle/auction/ready, переходы, silent return |
| §7 loadAd шаги 1-2 | Cache hit (Main.peek), silent return при auction |
| §8 show() | pop Main??Fallback, cancel auction, кеш сохраняется |
| §9 RoundStatus.Cached | Enum существует, code="CACHE" |
| §10 Synthetic AuctionInfo | Cache hit и first bid — корректная структура |
| §13 Thread safety | Mutex на storages/pool, callbacks на Main thread |
| §1 Форматы | Rewarded заблокирован на уровне AdCacheFactoryImpl |
