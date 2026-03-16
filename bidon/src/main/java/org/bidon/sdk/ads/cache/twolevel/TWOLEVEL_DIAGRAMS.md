# Two-Level Cache — Diagrams & Use Cases

Date: 2026-03-12

---

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        InterstitialImpl                         │
│                     (or BannerView, etc.)                       │
├─────────────────────────────────────────────────────────────────┤
│  load(pricefloor)          show()            isReady            │
│       │                      │                  │               │
│       ▼                      ▼                  ▼               │
│  ┌─────────┐           ┌─────────┐        ┌─────────┐          │
│  │ cache() │           │  pop()  │        │ peek()  │          │
│  └────┬────┘           └────┬────┘        └────┬────┘          │
└───────┼─────────────────────┼──────────────────┼────────────────┘
        │                     │                  │
        ▼                     ▼                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                     TwoLevelAdManager                           │
│                   (AdCache facade per auctionKey)                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────┐    ┌──────────────────────────────┐   │
│  │     Main Cache       │    │      Fallback Cache          │   │
│  │   (CacheStorage)     │    │  (FallbackCacheStorage)      │   │
│  │                      │    │                              │   │
│  │  Sorted descending   │    │  Sorted descending           │   │
│  │  Sticky head mode    │    │  No sticky, no threshold     │   │
│  │  Iteration threshold │    │  Strict > eviction           │   │
│  │  capacity=10         │    │  capacity=10                 │   │
│  └──────────────────────┘    └──────────────────────────────┘   │
│            ▲                            ▲                        │
│            │         SHARED             │                        │
│            └────── per AdType ──────────┘                        │
│              (TwoLevelCacheStores)                               │
└─────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ManagerPool (singleton)                      │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ auctionKey  │  │ auctionKey  │  │ auctionKey  │  ...        │
│  │   "home"    │  │   "feed"    │  │  "detail"   │             │
│  │ WeakRef<Mgr>│  │ WeakRef<Mgr>│  │ WeakRef<Mgr>│             │
│  └─────────────┘  └─────────────┘  └─────────────┘             │
│                                                                 │
│  Cleanup: every 60s, remove if idle AND (>5min OR ref dead)     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Use Case: First Load — Cold Start + First Fill at $10

Publisher calls `load(pricefloor = 5.0)`. Cache is empty. Auction returns 3 ad units.

```
Time  Action                                     Main Cache        Fallback Cache
────  ──────────────────────────────────────────  ────────────────  ────────────────
t0    load(pricefloor=5.0)                        [ empty ]         [ empty ]
      │
      ├─ Warm start: Main.peek() → null
      ├─ auctionRunning.CAS(false,true) → OK
      ├─ beginIteration() → iterationMaxPrice=null
      │
      ├─ Pipeline starts: collect tokens, POST /auction
      │  Response: adUnits = [AdMob/$10, Unity/$8, Meta/$6]
      │
t1    ├─ Load AdMob → Fill at $10
      │  singleLoadCompletion($10):
      │    isFirst = CAS(false,true) = true
      │    Main.insert($10, sticky=true) → SUCCESS
      │    iterationMaxPrice = 10.0
      │    ★ onSuccess($10) on Main thread                [10*]             [ empty ]
      │
t2    ├─ Load Unity → Fill at $8
      │  singleLoadCompletion($8):
      │    isFirst = CAS(false,true) = false
      │    threshold check: $8 >= $10 * 80% = $8 → PASS
      │    Main.insert($8, sticky=false) → SUCCESS        [10*, 8]          [ empty ]
      │
t3    ├─ Load Meta → Fill at $6
      │  singleLoadCompletion($6):
      │    isFirst = false
      │    threshold check: $6 >= $10 * 80% = $8 → FAIL
      │    Main.insert → REJECTED (IterationThreshold)
      │    Fallback.insert($6) → SUCCESS                  [10*, 8]          [6]
      │
t4    └─ Pipeline complete: 3 fills
         onComplete(info, null)
         auctionRunning = false

      Cache state after auction:
      Main:     [10*, 8]       (* = sticky head)
      Fallback: [6]
```

---

## 3. Use Case: Show at $10 + Next Load

Publisher shows the ad, then loads again.

```
Time  Action                                     Main Cache        Fallback Cache
────  ──────────────────────────────────────────  ────────────────  ────────────────
      State from previous:                        [10*, 8]          [6]

t5    show()
      │ pop() → Main.popFirst()
      │   remove $10, stickyHeadActive = false
      │   sort remaining → [8]                    [8]               [6]
      │
      │ → Return $10 ad to InterstitialImpl
      │ → InterstitialImpl shows the ad

t6    load(pricefloor=5.0)
      │
      ├─ Warm start: Main.peek() → $8
      │  $8 >= $5 → YES
      │  Main.popFirst() → $8                     [ empty ]         [6]
      │  ★ onSuccess($8) immediately
      │
      │ (No auction needed — warm start served)

t7    show()
      │ pop() → Main.popFirst() → null
      │        → Fallback.popFirst() → $6         [ empty ]         [ empty ]
      │
      │ → Return $6 ad to InterstitialImpl
```

---

## 4. Use Case: Warm Start Below Floor → Cold Start

```
Time  Action                                     Main Cache        Fallback Cache
────  ──────────────────────────────────────────  ────────────────  ────────────────
      State: Main has $3 cached from prev auction [3]               [ empty ]

t0    load(pricefloor=5.0)
      │
      ├─ Warm start: Main.peek() → $3
      │  $3 >= $5 → NO (below floor)
      │
      ├─ Cold start: auctionRunning.CAS → true
      ├─ beginIteration()
      ├─ Pipeline starts...
      │
t1    ├─ Load AppLovin → Fill at $7
      │  singleLoadCompletion($7):
      │    isFirst = true
      │    Main.insert($7, sticky=true) → SUCCESS
      │    ★ onSuccess($7)                        [7*, 3]           [ empty ]
      │
t2    └─ Pipeline complete
         auctionRunning = false

      Note: $3 was NOT evicted — it's still in Main behind $7.
      Sticky head $7 is protected, $3 stays as tail.
```

---

## 5. Use Case: Cache Full — Eviction

Main cache capacity = 3. Already full with [$10, $8, $5].

```
Time  Action                                     Main Cache        Fallback Cache
────  ──────────────────────────────────────────  ────────────────  ────────────────
      State:                                      [10*, 8, 5]       [ empty ]

      singleLoadCompletion arrives with $12:
      │ isFirst = false
      │ Main.insert($12, sticky=false):
      │   Step 1: threshold OK ($12 > max)
      │   Step 4: capacity=3, size=3
      │     cheapest evictable = $5 (items.last)
      │     $12 > $5 → PASS
      │   Step 5: append $12, sort tail [8,5,12]→[12,8,5]
      │     full list with head: [10*, 12, 8, 5]
      │     trimIfNeeded: size=4 > cap=3 → remove $5
      │     $5.adSource.destroy()                 [10*, 12, 8]      [ empty ]

      singleLoadCompletion arrives with $4:
      │ isFirst = false
      │ Main.insert($4, sticky=false):
      │   Step 4: capacity=3, size=3
      │     cheapest evictable = $8 (items.last)
      │     $4 <= $8 → REJECTED (CacheFull)
      │   Fallback.insert($4) → SUCCESS           [10*, 12, 8]      [4]

      singleLoadCompletion arrives with $2:
      │ Main.insert → REJECTED (CacheFull, $2 <= $8)
      │ Fallback.insert($2):
      │   capacity=3, size=1 → SUCCESS             [10*, 12, 8]      [4, 2]
```

---

## 6. Use Case: Sticky Head Protection

Sticky head prevents the first-fill ad from being evicted by cheaper ads.

```
Time  Action                                     Main Cache
────  ──────────────────────────────────────────  ────────────────
      capacity = 3

t1    First fill: $5, sticky=true
      Main.insert($5, sticky=true) → SUCCESS      [5*]

t2    Second fill: $10, sticky=false
      Main.insert($10, sticky=false) → SUCCESS     [5*, 10]
      (sort tail: [10] → [10])
      actual order: [5*, 10]

t3    Third fill: $8, sticky=false
      Main.insert($8, sticky=false) → SUCCESS      [5*, 10, 8]
      (sort tail: [10, 8])

t4    Fourth fill: $12, sticky=false
      Step 4: cheapest evictable = $8 (last)
        $12 > $8 → PASS
      Step 5: append, sort tail, trim
        [5*, 12, 10, 8] → trim → remove $8        [5*, 12, 10]

      Note: $5 sticky head survives even though
      it's the cheapest! Head is never evicted.

t5    pop() → Main.popFirst()
      removes $5, stickyHeadActive = false
      sort all: [12, 10]                           [12, 10]

      Now sticky mode is off.
      Next pop returns $12 (highest price).
```

---

## 7. Use Case: Auction Failure → Fallback Recovery

Auction returns no fills. Fallback has a cached ad from a previous round.

```
Time  Action                                     Main Cache        Fallback Cache
────  ──────────────────────────────────────────  ────────────────  ────────────────
      State from previous round:                  [ empty ]         [6, 4]

t0    load(pricefloor=5.0)
      │
      ├─ Warm start: Main.peek() → null
      ├─ Cold start: beginIteration(), pipeline starts
      │
t1    ├─ Load AdMob → FAIL (no fill)
t2    ├─ Load Unity → FAIL (timeout)
t3    ├─ Load Meta → FAIL (no fill)
      │
t4    └─ Pipeline complete: 0 fills
         onComplete(info, BidonError.NoFill)
         │
         TwoLevelAuctionController.handlePipelineFailure:
         │ Fallback.peek() → $6
         │ $6 >= $5 (pricefloor) → YES
         │ Fallback.popFirst() → $6              [ empty ]         [4]
         │ onComplete(info, null) ← reported as success
         │
         TwoLevelAdManager:
         │ error == null → nothing to do
         │ (firstFillFired was never set, but onComplete has no error)

      ⚠ Note: In this flow the fallback ad is popped by the controller,
        but onSuccess was never called (firstFillFired == false).
        The ad is consumed from fallback but the caller gets
        onComplete(info, null) — no error, but also no onSuccess callback.
        The caller should check peek()/isReady to find the ad.
```

---

## 8. Use Case: Iteration Threshold Filtering

Threshold = 80%. Prevents low-quality ads from polluting the cache.

```
Iteration starts: iterationMaxPrice = null

Fill #1: $10.0
  iterationMaxPrice = null → set to 10.0, PASS
  minAllowed = 10.0 * 80% = $8.0

Fill #2: $12.0
  $12 > $10 (currentMax) → update max to 12.0, PASS
  minAllowed = 12.0 * 80% = $9.6

Fill #3: $9.6
  $9.6 >= $9.6 (minAllowed) → PASS (boundary: equal passes)

Fill #4: $9.5
  $9.5 < $9.6 (minAllowed) → REJECTED (IterationThreshold)
  → routes to Fallback

Fill #5: $15.0
  $15 > $12 (currentMax) → update max to 15.0, PASS
  minAllowed = 15.0 * 80% = $12.0

Fill #6: $11.0
  $11 < $12.0 → REJECTED (IterationThreshold)
  → routes to Fallback

Summary: max keeps rising, threshold tightens.
Only ads within 80% of the best seen price enter Main.
```

---

## 9. Use Case: Duplicate Ad (Same demandId, Different Price)

Same adapter returns a new bid with updated price.

```
Time  Action                                     Main Cache
────  ──────────────────────────────────────────  ────────────────
      capacity = 3

t1    insert(AdMob/$10, sticky=true) → SUCCESS     [AdMob/10*]

t2    insert(Unity/$8, sticky=false) → SUCCESS      [AdMob/10*, Unity/8]

t3    insert(AdMob/$12, sticky=false):
      Step 2: duplicate found at idx=0
        price $12 != $10 → remove old
        stickyHeadActive was true, idx==0 → set false
        items = [Unity/8]
        rebuildIndex()
      Step 5: insert AdMob/$12, non-sticky
        items = [Unity/8, AdMob/12]
        sortAccordingToMode() (no sticky) → [AdMob/12, Unity/8]
                                                    [AdMob/12, Unity/8]

      Note: sticky head was deactivated because the sticky
      element was replaced with a different price.
```

---

## 10. Use Case: Multiple auctionKeys Share Cache

Two screens load interstitials with different auctionKeys. They share the same Main/Fallback stores.

```
┌──────────────────┐              ┌──────────────────┐
│  InterstitialImpl │              │  InterstitialImpl │
│  auctionKey="home"│              │  auctionKey="feed"│
└────────┬─────────┘              └────────┬─────────┘
         │                                  │
         ▼                                  ▼
┌──────────────────┐              ┌──────────────────┐
│ TwoLevelAdManager│              │ TwoLevelAdManager│
│   (manager #1)   │              │   (manager #2)   │
└────────┬─────────┘              └────────┬─────────┘
         │                                  │
         │    ┌────────────────────────┐    │
         └───►│  SHARED CacheStores    │◄───┘
              │  (per AdType singleton) │
              │                        │
              │  Main:     [10*, 8, 6] │
              │  Fallback: [4, 2]      │
              └────────────────────────┘

Scenario:
  t0: Manager #1 runs auction → fills Main with [10*, 8, 6]
  t1: Manager #2 calls load(pricefloor=5):
      Warm start: Main.peek() → $10 >= $5 → POP
      → Manager #2 serves $10 from Manager #1's auction!
  t2: Manager #1 calls show():
      pop() → Main: $8 (next highest)

  This is BY DESIGN — shared cache maximizes fill rate
  across all placements of the same ad type.
```

---

## 11. Sequence Diagram: Complete load() → show() Cycle

```
InterstitialImpl    TwoLevelAdManager    CacheStorage(Main)    FallbackCache    Pipeline
      │                    │                    │                    │              │
      │  load(floor=5)     │                    │                    │              │
      ├───────────────────►│                    │                    │              │
      │                    │  peek()            │                    │              │
      │                    ├───────────────────►│                    │              │
      │                    │  null              │                    │              │
      │                    │◄───────────────────┤                    │              │
      │                    │                    │                    │              │
      │                    │  CAS(false,true)   │                    │              │
      │                    │  beginIteration()  │                    │              │
      │                    ├───────────────────►│                    │              │
      │                    │                    │ maxPrice=null      │              │
      │                    │                    │                    │              │
      │                    │  controller.start()│                    │              │
      │                    ├────────────────────┼────────────────────┼─────────────►│
      │                    │                    │                    │              │
      │                    │                    │                    │    tokens    │
      │                    │                    │                    │    /auction  │
      │                    │                    │                    │    adUnits   │
      │                    │                    │                    │              │
      │                    │                    │                    │  Fill: $10   │
      │                    │  singleLoad($10)   │                    │◄─────────────┤
      │                    │◄────────────────────────────────────────┼──────────────┤
      │                    │                    │                    │              │
      │                    │  insert($10,       │                    │              │
      │                    │    sticky=true)     │                    │              │
      │                    ├───────────────────►│                    │              │
      │                    │  SUCCESS           │                    │              │
      │                    │◄───────────────────┤                    │              │
      │                    │                    │                    │              │
      │  ★ onSuccess($10)  │                    │                    │              │
      │◄───────────────────┤  (Main thread)     │                    │              │
      │                    │                    │                    │              │
      │                    │                    │                    │  Fill: $3    │
      │                    │  singleLoad($3)    │                    │◄─────────────┤
      │                    │◄────────────────────────────────────────┼──────────────┤
      │                    │  insert($3,false)  │                    │              │
      │                    ├───────────────────►│                    │              │
      │                    │  REJECTED(Thresh)  │                    │              │
      │                    │◄───────────────────┤                    │              │
      │                    │  insert($3)        │                    │              │
      │                    ├────────────────────┼───────────────────►│              │
      │                    │  SUCCESS           │                    │              │
      │                    │◄────────────────────────────────────────┤              │
      │                    │                    │                    │              │
      │                    │                    │                    │  complete    │
      │                    │  onComplete(ok)    │                    │◄─────────────┤
      │                    │◄────────────────────────────────────────┼──────────────┤
      │                    │                    │                    │              │
      │                    │  auctionRunning=F  │                    │              │
      │                    │                    │                    │              │
      ═══════════════ some time later ══════════════════════════════════════════════
      │                    │                    │                    │              │
      │  show()            │                    │                    │              │
      ├───────────────────►│                    │                    │              │
      │                    │  popFirst()        │                    │              │
      │                    ├───────────────────►│                    │              │
      │                    │  $10               │                    │              │
      │                    │◄───────────────────┤  Main: [empty]     │              │
      │  return $10        │                    │                    │              │
      │◄───────────────────┤                    │                    │              │
      │                    │                    │                    │              │
      │  ── show ad ──     │                    │                    │              │
```

---

## 12. State Machine: Cache Insert Routing

```
                    ┌─────────────────────┐
                    │   Ad Unit Fills      │
                    │  (singleLoadCompletion)│
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  isFirst = CAS(F→T) │
                    │  sticky = isFirst    │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  Main.insert(       │
                    │    winner,           │
                    │    sticky=isFirst)   │
                    └──────────┬──────────┘
                               │
                 ┌─────────────┼─────────────┐
                 │ SUCCESS     │             │ REJECTED
                 ▼             │             ▼
          ┌──────────┐        │      ┌──────────────┐
          │ Stays in │        │      │ Fallback     │
          │ Main     │        │      │ .insert()    │
          └──────────┘        │      └──────┬───────┘
                              │             │
                              │   ┌─────────┼─────────┐
                              │   │ SUCCESS │         │ REJECTED
                              │   ▼         │         ▼
                              │ ┌────────┐  │  ┌────────────┐
                              │ │Stays in│  │  │ .destroy() │
                              │ │Fallback│  │  │ (cleanup)  │
                              │ └────────┘  │  └────────────┘
                              │             │
                 ┌────────────┴─────────────┘
                 │
          ┌──────▼──────┐
          │ isFirst?     │
          │ true → fire  │
          │ onSuccess()  │
          │ on Main thrd │
          └─────────────┘
```

---

## 13. iOS vs Android — Visual Comparison

### Warm Start

```
        ANDROID                              iOS
        ───────                              ───
   peek → found?                        peek → found?
      │                                    │
      ▼ YES                                ▼ YES
   popFirst() ← POP!                  leave in cache
      │                                    │
      ▼                                    ▼
   onSuccess(ad)                       state = .ready
                                       didLoad(ad)
                                           │
                                      ── later ──
                                           │
                                       show() → popFirst()
                                           │
                                       ⚠ Race: another manager
                                         could pop before show()
```

### Fallback on Failure

```
        ANDROID                              iOS
        ───────                              ───
   pipeline fails                       auction fails
      │                                    │
      ▼                                    ▼
   Fallback.peek()                     Fallback.peek()
   price >= floor?                     price >= floor?
      │                                    │
      ▼ YES                                ▼ YES
   Fallback.popFirst()                 state = .ready(ad)
   ← POP immediately!                 didLoad(ad)
      │                                    │
      ▼                                 ⚠ Ad NOT popped!
   onComplete(ok)                      Still in Fallback.
                                       Another manager could
                                       steal it before show().
```

### singleLoadCompletion: Dual Reject

```
        ANDROID                              iOS
        ───────                              ───
   Main.insert → REJECTED              Main.insert → REJECTED
      │                                    │
      ▼                                    ▼
   Fallback.insert → REJECTED          Fallback.insert(ad)
      │                                    │
      ▼                                    │ (return value ignored)
   ad.destroy() ← CLEANUP!                │
                                           ▼
                                       ⚠ If Fallback also rejects:
                                         ad source LEAKS
                                         (never destroyed)
```
