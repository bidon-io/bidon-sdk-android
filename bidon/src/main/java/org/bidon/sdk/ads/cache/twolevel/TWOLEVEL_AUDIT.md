# Two-Level Cache Audit: Stats, Callbacks, ResultsCollector

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
