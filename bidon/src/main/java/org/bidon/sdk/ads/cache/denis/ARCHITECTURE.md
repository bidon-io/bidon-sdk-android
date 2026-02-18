# Denis -- Ad Caching Pipeline (V3)

Denis is the V3 ad caching pipeline for Bidon SDK. It runs RTB and CPM loading
in parallel, stores results in a pure FIFO cache (insertion order), and uses a
dynamic WeightModel to optimize CPM waterfall ordering based on observed fill
rates. The orchestrator collects results from both pipelines, sorts by eCPM, and
inserts into cache.

> **Scope:** Denis cache currently supports Interstitial only. Rewarded uses the classic auction path.

## Load Flow

```
loadAd()
|
+-- Cache has READY_TO_SHOW ads?
|   +-- YES -> onAdLoaded(FIFO head from cache) immediately [WARM START]
|   |          background auction starts to replenish cache
|   +-- NO  -> full auction (cold start)
|
Full Auction (wrapped in auctionTimeout - 5sec):
+-- RTB Pipeline (async)
|   +-- Collect tokens (skip cached networks via RtbPayloadCache)
|   +-- Server bidding request
|   +-- Merge server RTB with cached RTB payloads, sort by ecpm
|   +-- Try load best RTB; if fail, try next (waterfall fallback)
|   +-- First successful -> return CacheEntry to orchestrator
|   +-- Cache untried payloads in RtbPayloadCache for next auction
|
+-- CPM Pipeline (async, parallel with RTB)
|   +-- Sort by WeightModel score (fill rate x eCPM)
|   +-- Waterfall in batches of 2 (parallel within batch)
|   +-- For each batch:
|   |   +-- If pricefloor <= best RTB in cache -> STOP
|   |   +-- Load 2 adUnits in parallel (with adUnit.timeout)
|   |   +-- Any fill -> collect CacheEntry -> STOP
|   |   +-- Both fail -> continue to next batch
|   +-- Result -> 0 or 1 CacheEntry returned
|
+-- Orchestrator collects all CacheEntries from RTB + CPM
|   +-- Sort by eCPM descending
|   +-- Insert into ReadyToShowCache (preserves per-auction ordering)
|
+-- Both pipelines done (or timeout reached)
|   +-- Has results -> onAdLoaded(FIFO head from cache)
|   +-- No results  -> onAdLoadFailed
|
+-- Stats: ResultsCollector -> CacheAuctionStat.sendAuctionStats()
```

## Show Flow

```
showAd()
+-- ReadyToShowCache.popFirst() -> FIFO head (oldest ad)
+-- Try show
|   +-- Success -> onAdShown, onAdClicked, onAdClosed, onRevenuePaid
|   +-- Fail    -> try next from cache (ShowWithFallback)
+-- Shown ad removed from cache
+-- Remaining ads stay for next showAd()
```

## Key Components

| File | Responsibility |
|------|---------------|
| `../impl/AdCacheDenisImpl.kt` | Public API entry point (loadAd, showAd, isReady) |
| `orchestration/CoordinationLayer.kt` | Orchestrates auction lifecycle, stats, callbacks |
| `orchestration/ParallelAuctionOrchestrator.kt` | Runs RTB + CPM in parallel, collects results, sorts by eCPM, inserts into cache, fires callback |
| `processors/RtbProcessor.kt` | RTB waterfall with fallback, payload caching, returns CacheEntry |
| `processors/CpmProcessor.kt` | CPM batch loading, WeightModel integration, returns CacheEntries |
| `stores/ReadyToShowCache.kt` | Pure FIFO cache (insertion order) |
| `stores/RtbPayloadCache.kt` | Persists untried RTB payloads between auctions |
| `orchestration/CallbackCoordinator.kt` | Ensures exactly-once public callback firing |
| `extensions/ShowWithFallback.kt` | Show with fallback to next cached ad on failure |
| `processors/WeightModel.kt` | Tracks CPM fill rates, dynamic waterfall scoring |
| `stats/CacheAuctionStat.kt` | Denis-specific stats -- all cached ads marked WIN |
| `AdCacheDenisFactory.kt` | DI wiring for all Denis components |

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Wait for both pipelines before onAdLoaded | Developer gets the best ad, not the fastest |
| Pure FIFO cache, no size limit | Ads served in insertion order. Orchestrator sorts by eCPM before inserting into cache |
| RTB waterfall fallback | Don't waste RTB bids on single failure |
| CPM batches of 2 | Balance between speed and network load |
| WeightModel CPM sorting | Networks with better fill rates get priority |
| All cached ads = WIN in stats | Cached ads can still be shown, not losers |
| No saveWinners/markLoss | Prevents destroying ads still in cache |
| Reduced timeout (auctionTimeout - 5sec) | Faster response for cached ad use case |
| No dynamic pricefloor | Always use publisher's explicit pricefloor |
| Orchestrator owns cache insertion | Processors return results, orchestrator sorts by eCPM and inserts. Ensures per-auction ordering (expensive first) while keeping FIFO between auctions |
| Concurrent loadAd() guard | Prevent duplicate auctions |

## Stats Reporting

Uses `CacheAuctionStat`, a Denis-specific wrapper around `AuctionStatImpl`.

- All successfully loaded ads are marked as WIN (they remain in cache, available
  for show).
- Failed loads use NO_FILL or TIMEOUT as appropriate.
- No LOSE status -- cached ads are never losers since they may still be shown.
- `ResultsCollector` tracks round lifecycle: `startRound` ->
  `serverBiddingStarted`/`Finished` -> add results -> `sendStats`.

## WeightModel

Tracks CPM fill rate per demandId. State is in-memory and resets on app restart.

- Default weight: 10 (neutral, 1.0x multiplier)
- Fill: weight + 1 (max 20, 2.0x multiplier)
- No-fill: weight - 1 (min 1, 0.1x multiplier)
- Score = eCPM x (weight / 10.0)
- CPM waterfall is sorted by score before batch loading
