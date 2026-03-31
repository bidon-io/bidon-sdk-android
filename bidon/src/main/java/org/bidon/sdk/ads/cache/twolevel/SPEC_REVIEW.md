# Two-Level Cache: Spec vs Implementation Review

Spec: https://appodeal.atlassian.net/wiki/spaces/MGP/pages/6669172786/Two-Level+Cache
Date: 2026-03-31

---

## Implemented (matching spec)

| Section | Feature                                                                   | Status                  |
|---------|---------------------------------------------------------------------------|-------------------------|
| 1       | Supported formats: Interstitial, Banner (Rewarded not supported)          | OK                      |
| 2       | Config from server per format (banner/interstitial)                       | OK                      |
| 2       | Defaults: cacheSize=2, fallbackSize=1, threshold=80                       | OK (fixed this session) |
| 2       | fallbackSize=0 disables Fallback                                          | OK                      |
| 3       | Architecture: Main → rejected/evicted → Fallback                          | OK                      |
| 4       | peek, popFirst, insert(sticky), beginIteration                            | OK                      |
| 4       | Sticky head: first bid protected, capacity=1 rejects all non-sticky       | OK                      |
| 4       | Iteration threshold (capacity > 1, minAllowed = maxPrice * threshold%)    | OK                      |
| 4       | Full insert algorithm (5 steps)                                           | OK                      |
| 4       | Rejected/evicted → Fallback (not destroyed)                               | OK (fixed this session) |
| 5       | Fallback: no sticky, no threshold                                         | OK                      |
| 5       | Bids enter Fallback on rejection or eviction from Main                    | OK                      |
| 5       | Fallback used on auction no-fill or network error                         | OK                      |
| 5       | fallbackSize=0: rejected bids lost, early stop                            | OK                      |
| 6       | Pricefloor checked at peek (loadAd, fallback), NOT at insert/show/isReady | OK                      |
| 7       | loadAd: warm start (peek Main >= pricefloor → instant)                    | OK                      |
| 7       | loadAd: cold start guard (auction running → silent return)                | OK                      |
| 7       | loadAd: first bid → didLoad, rest silently cached                         | OK                      |
| 7       | loadAd: no-fill → check Fallback                                          | OK                      |
| 8       | show: pop(Main ?? Fallback) → cancel auction                              | OK (fixed this session) |
| 10      | adUnits overwrite (not accumulate) for all formats                        | OK                      |
| 11      | Stop: Main full AND (Fallback full OR disabled)                           | OK                      |
| 11      | Pre-filtering: threshold reject + Fallback full → stop                    | OK (fixed this session) |
| 13      | Manager Pool: one manager per auctionKey, reuse on same key               | OK                      |
| 13      | Auto-cleanup: 60s interval, idle > 5min + no refs → remove                | OK                      |
| 14      | Thread safety: Mutex on caches, callbacks on Main thread                  | OK                      |

---

## Open Issues — need implementation (code)

### 1. Bid statuses: WIN / CACHE / LOSE (spec section 9)

**Spec says:**
- WIN = first (sticky) bid or cache hit. Delivered in didLoad.
- CACHE = bid that went into Main or Fallback (not first). Silently cached.
- LOSE = bid that didn't fit anywhere (both caches full or not queried).

**Current code:**
- Only `"WIN"` is used in `buildSyntheticAuctionInfo`.
- No CACHE or LOSE status tracking per bid.
- `SequentialAuctionPipeline` uses `RoundStatus.Successful` for all fills regardless of routing.

**Impact:** AuctionInfo/AdUnitInfo reported to SDK consumers doesn't distinguish cache vs win vs lose. Affects reporting accuracy.

**Effort:** Medium. Need to pass status through singleLoadCompletion or set it after routing.

---

### 2. AuctionInfo: fallback-served bids should append to auction report (spec section 10)

**Spec says:**
- "Auction fail → fallback hit": AuctionInfo should contain adUnits from the auction report PLUS fallback bid with status=WIN.
- Currently the fallback bid's AdUnitInfo is NOT appended to the pipeline's auctionInfo.

**Current code:**
```kotlin
// onComplete handler:
val cached = mainCache.peek() ?: fallbackCache.peek()
val info = auctionInfo ?: buildSyntheticAuctionInfo(cached)
```
Uses either pipeline's auctionInfo (without fallback bid) or synthetic (without pipeline results). Should merge both.

**Effort:** Low. Build composite AuctionInfo with pipeline results + fallback adUnit.

---

### 3. Manager Pool: strong vs weak reference semantics (spec section 13)

**Spec says:** "Сильная ссылка на менеджер. Слабая ссылка на рекламный объект."

**Current code:** Pool stores `WeakReference<TwoLevelAdManager>`. The proxy (held by ad object) holds a strong ref to the manager.

**Difference:** In the spec model, the pool keeps the manager alive independently. In current code, when the ad object is GC'd, the proxy dies, and the manager's only strong ref is gone — it can be collected before the next cleanup cycle.

**Impact:** If a new InterstitialAd(auctionKey="X") is created shortly after the old one is destroyed, the manager might be GC'd and recreated (losing cached ads). The spec model preserves the manager until explicit cleanup (5min idle + no refs).

**Effort:** Low. Change `WeakReference<TwoLevelAdManager>` to strong ref, adjust cleanup.

---

### 4. Pre-filtering: skip demand load entirely (spec section 11)

**Spec says:** "demand-источники ниже threshold не опрашиваются" — the demand should NOT be loaded at all if it would be rejected by threshold and Fallback can't accept.

**Current code:** We stop AFTER a threshold rejection occurs (one wasted load triggers the stop signal). The pipeline doesn't check threshold BEFORE loading.

**Improvement:** Pass a `shouldLoadUnit(price: Double): Boolean` callback to the pipeline. Check `adUnit.pricefloor` against CacheStorage's threshold before loading. This saves one network request per auction.

**Impact:** One extra wasted load per auction in threshold-stop scenarios. Minor optimization.

**Effort:** Medium. Need to expose threshold state from CacheStorage for pre-load check.

---

## Open Issues — need product/architecture decision

### 5. State machine: full lifecycle (spec section 12)

**Spec defines:** idle → preparing → auction → ready → impression → idle

**Current code:** Only `AtomicBoolean auctionRunning`. Key behaviors are handled:
- Duplicate load → silent return (via compareAndSet)
- Cache bypass regardless of state (warm start)

**Question:** Is the state machine the cache's responsibility or the ad object's (InterstitialImpl/RewardedImpl)? If the ad object already manages states, the cache only needs idle/running which it has.

**Effort:** Low if ad object handles it. Medium if cache needs its own state enum.

---

### 6. Win/Loss notifications for cached bids (spec open question)

No win/loss notifications for cached bids. WinLossNotifier is not invoked for two-level cache bids.

**Questions:**
- When to send win: at loadAd (peek) or show (pop)?
- What about loss for evicted/expired bids?

---

## Resolved

| Issue                                       | Resolution                                                                     |
|---------------------------------------------|--------------------------------------------------------------------------------|
| AuctionInfo: empty auctionId for cache hits | AdSource.getStats().auctionId is populated from original auction — works as-is |
| Banner overwrite vs accumulate adUnits      | Resolved: overwrite for all formats. Spec updated on Confluence.               |
| TTL for cached bids                         | Manager-level only: 5 min idle TTL in ManagerPool. No per-bid TTL needed.      |

---

## Fixed This Session

| Issue                                                              | Commit     |
|--------------------------------------------------------------------|------------|
| Duplicated code (createAdSource, asStatisticAdType, applyParams)   | `5a89fac9` |
| Fallback ad leak bug (pop without delivery)                        | `5a89fac9` |
| Unused auctionKey param, scope leaks, proxy poll() bug             | `14d7c7be` |
| iOS/Zhenya references in comments                                  | `f11e7915` |
| Shared cache stores per AdType (should be per auctionKey)          | `54479157` |
| Config defaults (spec: cacheSize=2, fallbackSize=1)                | `3322b4bf` |
| fallbackCacheSize coerce range 0-10 (was 1-10)                     | `3322b4bf` |
| Evicted from Main → Fallback (was destroyed)                       | `dfb32c02` |
| Auction stop condition (Main full + Fallback full)                 | `dfb32c02` |
| show()/pop() cancels running auction                               | `dfb32c02` |
| fallbackSize=0 = disabled                                          | `dfb32c02` |
| Pre-filtering: threshold reject + Fallback full → stop             | `3322b4bf` |
| pop()/show() actually cancels pipeline (was dead controller.scope) | `f0a37d81` |
| ktlint format fixes                                                | `428e17aa` |
