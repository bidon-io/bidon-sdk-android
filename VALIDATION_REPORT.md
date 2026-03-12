# Validation Report: Android vs iOS Two-Level Cache

## Date: 2025-03-12

## Critical Bugs (FIXED)

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

## Medium Differences

### 3. Fallback routing: Main success -> Fallback insert
- **Android:** Fallback insert called ONLY if Main rejects.
- **iOS:** ALWAYS inserts into Fallback even if Main accepted.
- **Decision:** Keep Android behavior (more efficient, no duplicate caching).

### 4. ManagerPool: pipeline/controller creation — FALSE POSITIVE
- **File:** `ManagerPool.kt:67-114`
- **Clarification:** Pipeline/controller are only created when weak ref is dead (new manager needed). When an existing live manager is found, it returns early at line 74. No waste.

### 5. AuctionKey: null handling
- **iOS:** `auctionKey ?? "default"` handles nil.
- **Android:** Non-null String expected.
- **Decision:** Acceptable — caller always provides value.

## Minor Differences (not bugs)

| Aspect | Android | iOS | Note |
|--------|---------|-----|------|
| Warm start pop | Pops from cache | Leaves in cache | Android correct |
| Duplicate load guard | AtomicBoolean | Implicit state machine | Android safer |
| Per-unit timeout | withTimeout(adUnit.timeout) | Global timeout only | Android more granular |
| CacheStorage eviction lookup | minByOrNull O(n) | items.last O(1) | iOS optimal (sorted) |
| sortTailKeepingHead guard | items.size > 1 | items.count > 2 | Android correct (iOS skips sort at 2 elements) |
| Stats collection | Explicit ResultsCollector | Via observers | Different approach, both work |
| Stores initialization | Lazy per AdType | Eager static enums | Android more flexible |
