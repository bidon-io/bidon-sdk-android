# Ad Caching V2 - Test Progress Tracker

> **Started:** 2026-02-06
> **Status:** 🔄 IN PROGRESS
> **Current Blocker:** Issue #2 - Cache not persisting between loadAd() calls

---

## Test Execution Status

### Phase 1: Smoke Tests (CRITICAL) ⏳

| Test ID | Name | Status | Result | Notes |
|---------|------|--------|--------|-------|
| TC-COLD-001 | Pure Cold Start | ✅ PASSED | 4s (target: 5-7s) | Better than expected |
| TC-WARM-001 | Warm Start <1s | ✅ PASSED | <1s INSTANT! | **FIXED** - Warm start working! |
| TC-SHOW-001 | showAd() getBest() | ✅ PASSED | demandId=mintegral, ecpm=5.790811 | Highest eCPM ad served |
| TC-PERF-001 | Cold Start Latency | ⬜ PENDING | - | Blocked by TC-WARM-001 |
| TC-PERF-002 | Warm Start Latency | ⬜ PENDING | - | Blocked by TC-WARM-001 |

### Scenario Tests (from AD_CACHING_TESTING.md)

| Scenario | Status | Result | Notes |
|----------|--------|--------|-------|
| Scenario 1: Cold Start | ✅ PASSED | onAdLoaded in ~4s | Working correctly |
| Scenario 2: Warm Start | ❌ FAILED | No instant callback | Cache not persisting |
| Scenario 3: showAd() Best eCPM | ⬜ PENDING | - | - |
| Scenario 4: Periodic Sweep (TTL) | ⬜ PENDING | - | - |
| Scenario 5: Token Collection Skip | ⬜ PENDING | - | - |
| Scenario 6: Dynamic Pricefloor | ⬜ PENDING | - | - |
| Scenario 7: Empty Waterfall | ⬜ PENDING | - | - |
| Scenario 8: Memory Leak Detection | ⬜ PENDING | - | - |
| Scenario 9: Concurrent loadAd() | ✅ PASSED | One onAdLoaded callback | SDK handles concurrent calls correctly - second ignored/queued |
| Scenario 10: showAd() Cancel Auction | ⬜ PENDING | - | - |

---

## Active Issues

### ✅ Issue #2: Cache Not Persisting (RESOLVED!)
- **Status:** ✅ **FIXED AND VERIFIED**
- **Test:** TC-WARM-001
- **Root Cause:** Missing `markFillStarted()` call before `adSource.load()` in both processors
- **Fix Applied:**
  - Added `adSource.markFillStarted(adUnit, pricefloor)` in CpmProcessor before load()
  - Added `adSource.markFillStarted(source.adUnit, pricefloor)` in RtbProcessor before load()
- **Verification Results:**
  - ✅ First loadAd() at 08:47:05: "Pure cold start: both caches empty" (correct)
  - ✅ Second loadAd() at 08:47:51: "Warm start: cached ad available (demandId=mintegral, ecpm=5.790811)" 🎉
  - ✅ ReadyToShowCache.put() logs visible - multiple ads cached successfully
  - ✅ No NullPointerExceptions
  - ✅ Warm start serves cached ad INSTANTLY (<1s)

### ✅ Issue #3: NullPointerException in StatisticsCollectorImpl (RESOLVED!)
- **Status:** ✅ **FIXED AND VERIFIED**
- **Location:** StatisticsCollectorImpl.kt:80 - getAd()
- **Root Cause:** CpmProcessor and RtbProcessor didn't call `markFillStarted(adUnit)` before `adSource.load()`
- **Fix Applied:** Added `adSource.markFillStarted()` call in both processors before load()
- **Verification:**
  - ✅ No more NullPointerExceptions in logs
  - ✅ Adapters (DTExchange, Mintegral, Applovin, IronSource) loading successfully
  - ✅ getAd() returns valid Ad objects
  - ✅ AdEvent.Fill fires correctly
  - ✅ ReadyToShowCache.put() executes successfully
- **Impact:** This fix resolved BOTH Issue #2 and Issue #3 - they were the same root cause!

---

## Test Session Info

- **Device:** Android Emulator (to be detected)
- **Test App:** claude-in-mobile (MCP)
- **Placement Key:** 1O16GQT380000
- **SDK Branch:** experiment/ad-caching-gl
- **Implementation:** AdCacheDenisImpl (v2)

---

## Next Actions

1. ✅ Create progress tracker (this file)
2. ✅ Investigate Issue #2 - Cache persistence
3. ✅ Fix cache persistence bug (added markFillStarted calls)
4. ✅ Re-run TC-WARM-001 - **PASSED!**
5. ✅ Continue with remaining smoke tests (TC-COLD-001, TC-WARM-001, TC-SHOW-001 all PASSED)
6. 🔄 Run remaining scenarios from AD_CACHING_TESTING.md
7. ⏳ Create git commit with fixes
8. ⏳ Update E2E_TEST_REPORT.md with results

---

## Summary

🎉 **MAJOR BREAKTHROUGH!** Core ad caching functionality is now WORKING:
- ✅ Cold start: ads load and cache correctly
- ✅ Warm start: instant callback from cache (<1s)
- ✅ showAd(): serves highest eCPM ad from cache
- ✅ No NullPointerExceptions
- ✅ Multiple adapters loading successfully (DTExchange, Mintegral, Applovin, IronSource)
- ✅ Concurrent loadAd(): SDK handles properly (second call ignored/queued)

**Root cause was simple:** Missing `markFillStarted()` call before `adSource.load()` in both RtbProcessor and CpmProcessor. This caused `stat.adUnit` to remain null, which triggered NPE in `getAd()`, which prevented `AdEvent.Fill` from firing, which blocked `ReadyToShowCache.put()`.

**Fix:** Added two lines of code (one in each processor) to call `markFillStarted()` before loading.

---

## Current Testing Session (2026-02-06 09:00-09:14)

### Tests Completed:
1. ✅ Scenario 9: Concurrent loadAd() - PASSED (one onAdLoaded callback, SDK handles concurrent calls correctly)
2. ✅ SDK Initialization - VERIFIED (after proper INIT, ads load successfully: bidmachine/CPM 0.020227 USD)

### Key Learnings:
- **CRITICAL**: Must click INIT button (not SKIP INIT) for SDK to function properly
- After proper initialization, ad loading works correctly
- First test showed Vungle/RTB 0.001 USD
- Second proper init test showed bidmachine/CPM 0.020227 USD
- Both RTB and CPM adapters working

### Remaining Scenarios:
- Scenario 3: showAd() Best eCPM
- Scenario 4: Periodic Sweep (TTL) - deferred (requires long wait)
- Scenario 5: Token Collection Skip
- Scenario 6: Dynamic Pricefloor
- Scenario 7: Empty Waterfall
- Scenario 8: Memory Leak Detection
- Scenario 10: showAd() Cancel Auction

---

**Last Updated:** 2026-02-06 09:14 (Testing session: SDK initialized correctly, ready to continue)
