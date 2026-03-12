# Validation Report: Android vs iOS Two-Level Cache

## Date: 2025-03-12

## Critical Bugs (ALL FIXED)

### 1. CacheStorage: capacity==1 sticky insert rejected incorrectly — FIXED
**File:** `CacheStorage.kt:103`
**Issue:** Missing `!sticky` check. Android rejected ALL inserts when capacity==1 + stickyHead active. iOS correctly allows sticky inserts to replace the current sticky head.
**Root cause:** Three bugs in one flow:
  1. Step 3: Missing `&& !sticky` in the condition
  2. Step 4: `cheapest == null` was treated as reject; iOS skips step 4 when cheapest is nil (Swift `if let` binding fails)
  3. Step 5: Always appended to tail; iOS inserts sticky at head (`items.insert(element, at: 0)`)
**Fix:** Added `!sticky` guard, changed step 4 null handling, sticky inserts now go at index 0.
**Tests:** 3 new tests added for capacity==1 sticky scenarios.

### 2. ZhenyaAdManager: missing beginIteration() call before auction — FIXED
**File:** `ZhenyaAdManager.kt` — before `controller.start()`
**Issue:** iOS calls `Cacher.Main.interstitialStorage.beginIteration()` before `auction.load()`. Android didn't reset iteration state, so `iterationMaxPrice` could carry over from previous auction.
**Fix:** Added `mainCache.beginIteration()` before `controller.start()`.

### 3. CacheStorage step 4: eager eviction before insert — FIXED
**File:** `CacheStorage.kt` step 4
**Issue:** Android evicted cheapest item BEFORE inserting the new element. iOS does NOT evict in step 4 — it only checks if the new price beats the cheapest, then lets `trimIfNeeded()` in step 5 handle overflow after insert+sort.
**Fix:** Removed eager eviction from step 4. Now only rejects if price <= cheapest. `trimIfNeeded()` handles overflow.

### 4. CacheStorage cheapestAllowedToEvict: O(n) scan vs O(1) — FIXED
**File:** `CacheStorage.kt:204`
**Issue:** Android used `minByOrNull` O(n) to find cheapest in tail. iOS uses `items.last?.price` O(1) since the array is always sorted descending.
**Fix:** Changed to `cheapestAllowedToEvictPrice()` returning `items.last().price()`, matching iOS exactly.

## Design Decisions (not bugs)

### 5. Fallback routing: Main success -> Fallback insert
- **Android:** Fallback insert called ONLY if Main rejects.
- **iOS:** ALWAYS inserts into Fallback even if Main accepted.
- **Decision:** Keep Android behavior (more efficient, no duplicate caching).

### 6. ManagerPool: pipeline/controller creation — FALSE POSITIVE
- **File:** `ManagerPool.kt:67-114`
- **Clarification:** Pipeline/controller are only created when weak ref is dead (new manager needed). When an existing live manager is found, it returns early at line 74. No waste.

### 7. AuctionKey: null handling
- **iOS:** `auctionKey ?? "default"` handles nil.
- **Android:** Non-null String expected.
- **Decision:** Acceptable — caller always provides value.

### 8. Fallback peek/pop in ZhenyaAuctionController
- **File:** `ZhenyaAuctionController.kt:89-101`
- **Issue flagged:** peek() + popFirst() as separate calls could race.
- **Decision:** Safe — FallbackCacheStorage uses Mutex internally, and this code runs in a sequential pipeline (one auction at a time per controller). Matches iOS pattern exactly.

## Minor Differences (not bugs)

| Aspect | Android | iOS | Note |
|--------|---------|-----|------|
| Warm start pop | Pops from cache | Leaves in cache | Android correct |
| Duplicate load guard | AtomicBoolean | Implicit state machine | Android safer |
| Per-unit timeout | withTimeout(adUnit.timeout) | Global timeout only | Android more granular |
| sortTailKeepingHead guard | items.size > 1 | items.count > 2 | Android correct |
| Stats collection | Explicit ResultsCollector | Via observers | Different approach, both work |
| Stores initialization | Lazy per AdType | Eager static enums | Android more flexible |
| singleLoadCompletion timing | Direct in loop | Deferred via BlockOperation | Semantically equivalent |
| Timeout mechanism | withTimeout coroutine | Timer + timeoutReached() | Both cancel executing work |
