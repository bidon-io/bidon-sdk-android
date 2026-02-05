# Ad Caching v2 — E2E Test Report

> **Version:** 1.0
> **Date:** 2026-02-05
> **Tester:** Claude (Automated Testing via MCP)
> **Device:** emulator-5554 (sdk_gphone64_arm64)
> **Test App:** claude-in-mobile
> **Placement Key:** 1O16GQT380000

---

## Test Environment

- **SDK Version:** bidon-sdk (experiment/ad-caching-gl branch)
- **AdCache Implementation:** AdCacheDenisImpl (v2)
- **Android Emulator:** API 26+ (emulator-5554)
- **MCP Server:** Connected ✅
- **Test Start Time:** 2026-02-05

---

## Test Execution Plan

### Phase 1: Smoke Tests (Priority: CRITICAL) ⏳
**Goal:** Verify основная функциональность работает
**Time Estimate:** 1-2 hours

- [ ] TC-COLD-001: Pure cold start
- [ ] TC-WARM-001: Warm start <1s ⭐ **MAIN FEATURE**
- [ ] TC-SHOW-001: showAd() works
- [ ] TC-PERF-001: Cold start latency target
- [ ] TC-PERF-002: Warm start latency target ⭐

**Success Criteria:** All 5 tests pass

---

## Test Results

### Phase 1: Smoke Tests

#### TC-COLD-001: Pure Cold Start
- **Status:** ⬜ Not Started
- **Expected:** onAdLoaded in 5-7s
- **Actual:** _____
- **Logs:** _____
- **Issues:** _____

#### TC-WARM-001: Warm Start <1s ⭐
- **Status:** ⬜ Not Started
- **Expected:** onAdLoaded in <1000ms (instant!)
- **Actual:** _____
- **Logs:** _____
- **Issues:** _____

#### TC-SHOW-001: showAd() getBest()
- **Status:** ⬜ Not Started
- **Expected:** Highest eCPM ad shown
- **Actual:** _____
- **Logs:** _____
- **Issues:** _____

#### TC-PERF-001: Cold Start Latency
- **Status:** ⬜ Not Started
- **Target:** 5000-7000ms
- **Actual:** _____ ms
- **Pass:** ⬜ Yes / ⬜ No
- **Issues:** _____

#### TC-PERF-002: Warm Start Latency ⭐
- **Status:** ⬜ Not Started
- **Target:** <1000ms (prefer <500ms)
- **Actual:** _____ ms
- **Pass:** ⬜ Yes / ⬜ No
- **Issues:** _____

---

## Issues Found

### Issue #1: ✅ BLOCKER - Test App Uses AdCacheImpl v1 Instead of AdCacheDenisImpl v2
- **Test Case:** TC-COLD-001 (and all subsequent tests)
- **Severity:** ✅ **BLOCKER** - Cannot test v2 features
- **Title:** Application uses old AdCacheImpl (v1) instead of new AdCacheDenisImpl (v2)
- **Description:** The test application (com.games.joinblocks) is using the old AdCacheImpl implementation instead of the new AdCacheDenisImpl v2. All logcat entries show `[AdCacheImpl_interstitial]` tags, not `[AdCacheDenisImpl]` or expected v2 tags.
- **Steps to Reproduce:**
  1. Launch Bidon app on emulator
  2. Navigate to Interstitial Ad screen
  3. Press "LOAD" button
  4. Check logcat output
- **Expected Result:** Logs should show AdCacheDenisImpl tags like:
  - `[CoordinationLayer] determineStartState() → PureColdStart`
  - `[AdCacheDenisImpl] cache() called`
  - `[RtbProcessor]` / `[CpmProcessor]` logs
- **Actual Result:** Logs show old v1 implementation:
  ```
  [AdCacheImpl_interstitial] Cache started: (0)
  [AdCacheImpl_interstitial] Cache ad: org.bidon.sdk.auction...
  [AdCacheImpl_interstitial] Auction completed: (1) dtexchange:45.0
  ```
- **Root Cause:** One of the following:
  1. AdCacheFactory not configured to use v2
  2. AdCacheDenisImpl code not compiled into APK
  3. Application using old SDK version without v2 code
  4. DI configuration not wiring v2 implementation
- **Impact:** **ALL v2 testing is blocked** - cannot test warm start, dynamic pricefloor, token optimization, or any v2 features
- **Status:** ✅ **OPEN** - Requires investigation and fix before testing can continue

---

## Performance Metrics

| Metric | Target | Measured | Pass? |
|--------|--------|----------|-------|
| Cold Start Latency | 5-7s | _____ s | ⬜ |
| Warm Start Latency | <1s | _____ ms | ⬜ |
| Token Collection Improvement | 30-50% | _____ % | ⬜ |
| Memory Overhead | <5MB | _____ MB | ⬜ |
| Parallel Speedup | 30-50% | _____ % | ⬜ |
| Success Rate (100 loads) | >95% | _____ % | ⬜ |
| Crash Rate | 0 | _____ | ⬜ |

---

## Test Session Summary

- **Tests Planned:** 90 (25 Functional + 26 Edge Cases + 20 Lifecycle + 19 Performance)
- **Tests Executed:** 1 / 90 (TC-COLD-001 attempted)
- **Passed:** 0
- **Failed:** 0
- **Blocked:** 90 (all tests blocked by Issue #1)
- **Issues Found:** 1 (BLOCKER)

---

## Sign-off

### Test Lead
- **Name:** Claude (Automated Testing)
- **Date:** _____
- **Overall Status:** ⬜ Pass / ⬜ Fail / ⬜ In Progress

---

**Document Status:** In Progress 🔄
**Last Updated:** 2026-02-05
**Next Update:** After Phase 1 completion
