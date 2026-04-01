# Two-Level Cache — Audit: Spec vs Implementation

Дата: 2026-04-01
Ветка: `feature/ad-caching-two-level`

---

## Сводка

| # | Severity | Проблема | Статус |
|---|----------|----------|--------|
| 1 | ~~Critical~~ | Pre-filter не учитывает eviction в Fallback | **FIXED** — `shouldContinueAuction` принимает `ecpm` |
| 2 | ~~Critical~~ | Двойная проверка Fallback → потеря колбэка | **FIXED** — Controller упрощён до passthrough |
| 3 | ~~Medium~~ | Pricefloor проверяется при peek Fallback | **FIXED** — удалено вместе с handlePipelineFailure |
| 4 | ~~Medium~~ | Статус CACHE теряется в stats pipeline | **NOT A BUG** — `asStatsAdUnit()` читает `stat.roundStatus` (из adSource) первым |
| 5 | Minor | Условие cleanup в ManagerPool отличается от спеки | Не исправлен, minor |
| 6 | Medium | Manager reuse after clear() — dead scope | **FIXED** — `isAlive()` check в ManagerPool |

---

## 1–3: FIXED

- **#1**: `shouldContinueAuction` теперь `(ecpm: Double) -> Boolean`, проверяет threshold для Main и cheapest для Fallback eviction.
- **#2**: `TwoLevelAuctionController` упрощён до тонкого passthrough к pipeline. Fallback-delivery — ответственность только `TwoLevelAdManager.onComplete`.
- **#3**: Pricefloor-проверка при peek удалена вместе с `handlePipelineFailure`.

---

## 4: NOT A BUG — Статус CACHE корректно проходит через stats

Ранее считалось что `AuctionResult.roundStatus` (immutable `val = Successful`) теряет CACHE. Однако `AuctionStatImpl.asStatsAdUnit()` читает из adSource stats первым:

```kotlin
status = (stat.roundStatus ?: roundStatus).code
```

Трассировка:

| Бид | Pipeline markFillFinished | Manager action | stat.roundStatus | asStatsAdUnit | getFinalStatus |
|-----|--------------------------|----------------|------------------|---------------|---------------|
| Winner | Successful | markWin() | **Win** | "WIN" | WIN ✓ |
| Cached CPM | Successful | markFillFinished(Cached) | **Cached** | "CACHE" | CACHE ✓ |
| Cached RTB | Successful | markFillFinished(Cached) | **Cached** | "CACHE" | CACHE ✓ |

`getFinalStatus("CACHE", false)` → `else → "CACHE"`. Никаких перезаписей.

Timing корректен: `singleLoadCompletion` (→ `routeBidToCache` → `markFillFinished(Cached)`) вызывается синхронно до `proceedRoundResults`.

---

## 5: MINOR — Pool cleanup OR vs AND

Спека §12: `idle AND idle_time > 5 min AND weak ref dead`
Реализация: `isWeakRefDead || (isIdle && isStale)` — более агрессивная очистка.

Минимальное влияние — пересоздание менеджера дешёвое.

---

## 6: FIXED — Manager reuse after clear()

### Проблема

`clear()` навсегда отменяет `SupervisorJob` через `scope.coroutineContext[Job]?.cancel()`. Если `ManagerPool.getOrCreate` возвращает этот manager новому proxy (WeakRef ещё жива), `scope.launch {}` молча не работает.

### Исправление

`TwoLevelAdManager.isAlive()` проверяет `scope.coroutineContext[Job]?.isActive`.
`ManagerPool.getOrCreate` проверяет `live.isAlive()` — если false, удаляет запись и создаёт новый manager.

---

## Проверено и соответствует спеке

| Раздел спеки | Что проверено |
|--------------|---------------|
| §2 Configuration | Defaults 2/1/80, ranges coerceIn, JSON parsing |
| §3.1 Sticky Head | Первый бид pinned, popFirst снимает sticky |
| §3.2 Threshold | Формула `maxPrice * (threshold/100.0)`, пустой кеш пропускает |
| §3.3 Insert алгоритм | 5 шагов, no eviction, duplicate handling |
| §4 Fallback Cache | Strict `>` для eviction, destroy, disabled при capacity=0 |
| §5 Pricefloor | Не проверяется при peek/pop |
| §6 State Machine | idle/auction/ready, переходы, silent return |
| §7 loadAd шаги 1-5 | Cache hit, silent return, config failure, Fallback rescue |
| §7 Waterfall pseudocode | Pre-filter (ecpm), routing, markRemaining(LOSE) |
| §8 show() | pop Main??Fallback, cancel auction |
| §9 Statuses | WIN/CACHE/LOSE корректно через stat.roundStatus |
| §9.1 Win/Loss notifications | markWin, notifyWin (CPM), notifyLoss, markLoss, destroy |
| §10 Synthetic AuctionInfo | Cache hit, first bid, full pipeline |
| §11 Auction management | Pre-filter с ecpm, show → cancel |
| §12 Manager Pool | Keyed by auctionKey, WeakRef, cleanup, isAlive check |
| §13 Thread safety | Mutex на storages/pool, callbacks на Main thread |
| §1 Форматы | Rewarded заблокирован на уровне AdCacheFactoryImpl |
