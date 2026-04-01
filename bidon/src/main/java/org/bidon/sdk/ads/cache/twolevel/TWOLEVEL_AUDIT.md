# Two-Level Cache Audit

## Part A: Логические проблемы (2026-04-01)

### A.1 runBlocking в TwoLevelAdManager.pop() [FIXED]

**Severity:** HIGH — ANR risk

`pop()` вызывается с Main thread. `CacheStorage.popFirst()` и `FallbackCacheStorage.popFirst()` использовали `kotlinx.coroutines.sync.Mutex` (suspend). Для вызова suspend из не-suspend использовался `runBlocking`, блокируя Main thread при contention.

**Fix:** Заменил `Mutex` на `synchronized` в обоих storage. Операции внутри lock — list add/remove/sort без suspend-точек. `popFirst()` и `insert()` теперь обычные `fun`, `runBlocking` убран из `pop()`.

### A.2 TwoLevelAdManagerProxy мёртв после clear() [FIXED]

**Severity:** MEDIUM — silent failure

`clear()` отменял `scope.coroutineContext[Job]`. После этого `cache()` → `scope.launch { }` — no-op. Callbacks `onSuccess`/`onFailure` никогда не вызывались. Publisher не получал ни ответа, ни ошибки.

**Fix:** Добавил `ensureActiveScope()` — если scope отменён, пересоздаёт его. `clear()` также обнуляет `_delegate`, чтобы при следующем `cache()` manager резолвился заново.

### A.3 Ad Expiry не обрабатывается для закешированных бидов [OPEN]

**Severity:** MEDIUM — stale ads

V1 (`AdCacheImpl`) подписывается на `adSource.adEvent` и удаляет expired ads из кеша. Two-Level Cache не подписывается. Закешированный бид может протухнуть (ad network TTL 30-60 мин), и при `pop()` вернётся невалидный креатив.

Задокументировано в AD_CACHE_SPEC.md, section 14.

---

## Part B: Stats, Callbacks, ResultsCollector (ранее)

Comparison of `TwoLevel (SequentialAuctionPipeline + TwoLevelAdManager)` vs reference flows
(`AuctionImpl + DefaultAuctionExecutor + WinLossNotifier`).

## 1. Stats Pipeline (ResultsCollector) -- OK

| Step | AuctionImpl | TwoLevel | Status |
|------|------------|----------|--------|
| `startRound(pricefloor)` | yes | yes | OK |
| `serverBiddingStarted()` | yes | yes | OK |
| `serverBiddingFinished(rtbUnits)` | `adUnit.bidType == RTB` | `adapter is Adapter.Bidding` | **FIXED** |
| `setNoBidInfo(noBids)` | yes | yes | OK |
| `add(result)` per unit | yes | yes | OK |
| `saveWinners(pricefloor)` | yes | N/A | Intentional: twolevel routes via caches |
| `proceedRoundResults()` / `addRoundResults()` | yes | yes | OK |
| `sendAuctionStats()` | yes | yes | OK |
| `clear()` | yes | **NO** | **FIXED** |

## 2. Win/Loss Notifications -- FIXED

Both reference flows notify adapters. TwoLevel was missing all of these:

| Action | Reference | TwoLevel Before | TwoLevel After |
|--------|-----------|----------------|---------------|
| `winner.markWin()` | yes | NO | **FIXED** |
| `winner.notifyWin()` (CPM, when `!externalWin`) | yes | NO | **FIXED** |
| `loser.notifyLoss(winnerId, winnerPrice)` (non-bidding) | yes | NO | **FIXED** |
| `loser.markLoss()` (Successful losers) | yes | NO | **FIXED** |

## 3. `markFillFinished` on failure -- FIXED

Standard `RequestAdUnitUseCaseImpl` calls `markFillFinished(status, price)` for every outcome.
TwoLevel was missing it on failure paths:

| Path | Before | After |
|------|--------|-------|
| Fill | OK | OK (+ price validation) |
| Timeout | OK | OK |
| LoadFailed | **MISSING** | **FIXED** |
| Expired | **MISSING** | **FIXED** |
| Exception | **MISSING** | **FIXED** |

## 4. Below-Pricefloor Check on Fill -- FIXED

Standard flow checks `loadedPrice >= priceFloor` and returns `BelowPricefloor` / `Lose`.
TwoLevel was marking every fill as `Successful` with `adUnit.pricefloor` instead of actual price.

Fixed: now uses `adSource.ad?.price` and validates against pricefloor.

## 5. `markAuctionCanceled()` -- FIXED

`AuctionImpl.cancel()` calls `auctionStat.markAuctionCanceled()`.
TwoLevel was just cancelling the Job.

Fixed: pipeline catches `CancellationException` and marks stat before rethrowing.

## 6. Error Path Stats -- FIXED

When `/auction` request fails:
- AuctionImpl: sends `proceedRoundResults()` + `sendAuctionStats()`
- TwoLevel was: just `onComplete(null, error)`

Fixed: now sends round results and auction stats on request failure too.

## 7. `serverBiddingFinished` Filter -- FIXED

- AuctionImpl: `response.adUnits?.filter { it.bidType == BidType.RTB }`
- TwoLevel was: filters by adapter instance type

Fixed: now uses `adUnit.bidType == BidType.RTB` to match reference flow.
