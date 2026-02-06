# Debug Session: Cache Not Persisting Between loadAd() Calls

**Date:** 2026-02-05
**Status:** 🚨 BLOCKING
**Phase:** 05-entry-point-integration (E2E Testing)

---

## Problem Statement

Second `loadAd()` call shows `Pure cold start: both caches empty` instead of warm start. Cache is not preserved between auction calls, making warm start feature completely broken.

**Impact:** Main feature (warm start <1s) not working. All warm start, token optimization, and dynamic pricefloor tests blocked.

---

## Observed Behavior

### Test Execution Timeline

**First loadAd() - TC-COLD-001:**
```
23:28:10.111  [CoordinationLayer] Pure cold start: both caches empty (userPricefloor=0.001)
23:28:10.112  [AdCacheDenisImpl] cache: cold start in progress
23:28:10.112  [CoordinationLayer] Cold start: dynamicPricefloor=0.001, skipDemandIds=0
23:28:12.527  [CoordinationLayer] Waterfall split complete: rtb=3, cpm=17
23:28:14.005  onAdLoaded! ✅ (~4 seconds)
```

**Second loadAd() - TC-WARM-001:**
```
23:29:17.651  [CoordinationLayer] Pure cold start: both caches empty ❌
23:29:17.651  [AdCacheDenisImpl] cache: cold start in progress
23:29:17.651  [CoordinationLayer] Cold start: dynamicPricefloor=0.001, skipDemandIds=0
23:29:19.674  [CoordinationLayer] Waterfall split complete: rtb=3, cpm=17
23:29:20.535  onAdLoaded! (~3 seconds, still cold!)
```

**Expected behavior for second loadAd():**
```
XX:XX:XX.XXX  [CoordinationLayer] Warm start: READY_TO_SHOW has 5 ads
XX:XX:XX.XXX  [AdCacheDenisImpl] Returning cached ad IMMEDIATELY
XX:XX:XX.XXX  onAdLoaded! (<1000ms)
XX:XX:XX.XXX  [CoordinationLayer] Background auction starting...
```

---

## Evidence

### Log Patterns Missing
No logs found for:
- `ReadyToShowCache.add()` or `cache added`
- `RtbPayloadCache.put()` or `payload cached`
- `READY_TO_SHOW size` or `cache size`
- Any indication that ads are being stored

### Error Found
```
23:29:20.535  E  [StatisticsCollector] Ad is null
java.lang.NullPointerException
    at org.bidon.sdk.stats.impl.StatisticsCollectorImpl.getAd(StatisticsCollectorImpl.kt:80)
    at org.bidon.dtexchange.impl.DTExchangeInterstitial.getAd(Unknown Source:2)
```

---

## Hypotheses

### H1: Processors Not Caching Ads (Most Likely)
**Theory:** CpmProcessor/RtbProcessor successfully load ads but never call `ReadyToShowCache.add()` or `RtbPayloadCache.put()`

**Evidence:**
- No cache logs in output
- Cache remains empty after successful auction
- User opened RtbProcessor.kt in IDE (investigating?)

**Files to Check:**
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/processors/RtbProcessor.kt`
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/processors/CpmProcessor.kt`
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/ParallelAuctionOrchestrator.kt`

**Expected Code (Missing?):**
```kotlin
// After successful ad load
readyToShowCache.add(CacheEntry(adSource, ecpm, demandId, ...))
```

### H2: Cache Cleared After onAdLoaded
**Theory:** Cache is populated correctly but cleared when auction completes

**Evidence:**
- Log shows `[CancellationManager] Auction completed normally`
- Possible cleanup logic triggered on completion

**Files to Check:**
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt` (cleanup logic)
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/LifecycleManager.kt`

### H3: Singleton ReadyToShowCache Not Working
**Theory:** ReadyToShowCache instantiated per-auction instead of singleton

**Evidence:**
- 05-UAT.md mentions "shares ReadyToShowCache, RtbPayloadCache, WeightModel singletons"
- May not be properly registered in DI

**Files to Check:**
- `bidon/src/main/java/org/bidon/sdk/config/di/DI.kt` (singleton registration)
- Cache creation in AdCacheFactoryImpl

### H4: Ads Expired Immediately
**Theory:** TTL set to 0 or ads marked expired right after loading

**Evidence:**
- Weak evidence
- Would still see logs about cache additions

---

## Investigation Steps

### Step 1: Add Debug Logging to ReadyToShowCache
Add temporary logs to verify if `add()` is ever called:

```kotlin
// In ReadyToShowCache.kt
fun add(entry: CacheEntry) {
    Log.d("BidonCache", ">>> ReadyToShowCache.add() called: demandId=${entry.demandId}, ecpm=${entry.ecpm}")
    // ... existing code
    Log.d("BidonCache", ">>> ReadyToShowCache size after add: ${cache.size}")
}

fun getBest(): CacheEntry? {
    Log.d("BidonCache", ">>> ReadyToShowCache.getBest() called, current size: ${cache.size}")
    // ... existing code
}
```

### Step 2: Check Processor Implementation
Verify RtbProcessor.kt and CpmProcessor.kt call cache methods:

```kotlin
// Search for:
readyToShowCache.add(...)
rtbPayloadCache.put(...)

// If missing, add after successful load:
onAdLoaded { adSource ->
    readyToShowCache.add(CacheEntry(adSource, ecpm, demandId, ...))
}
```

### Step 3: Verify Singleton Registration
Check DI.kt has singleton scope:

```kotlin
single<ReadyToShowCache> { ReadyToShowCacheImpl() }
single<RtbPayloadCache> { RtbPayloadCacheImpl() }
```

### Step 4: Check CoordinationLayer Cleanup
Search for cache clearing on auction completion:

```kotlin
// Look for:
readyToShowCache.clear()  // Should NOT exist
// or
cache.removeIf { ... }    // Check conditions
```

---

## Next Actions

1. **URGENT:** Check RtbProcessor.kt and CpmProcessor.kt for missing `readyToShowCache.add()` calls
2. Add debug logging to ReadyToShowCache to trace calls
3. Verify singleton registration in DI
4. Run test again with debug logs
5. Analyze why ads not being cached

---

## Files to Investigate

### High Priority
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/processors/RtbProcessor.kt`
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/processors/CpmProcessor.kt`
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/caches/ReadyToShowCache.kt`

### Medium Priority
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt`
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/ParallelAuctionOrchestrator.kt`
- [ ] `bidon/src/main/java/org/bidon/sdk/config/di/DI.kt`

### Low Priority
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/lifecycle/LifecycleManager.kt`
- [ ] `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/caches/RtbPayloadCache.kt`

---

## References

- Test Report: `docs/testing/E2E_TEST_REPORT.md` (Issue #2)
- Test Spec: `docs/testing/TEST_SCENARIOS_FUNCTIONAL.md` (TC-WARM-001)
- Phase Summary: `.planning/phases/05-entry-point-integration/05-VERIFICATION.md`

---

**Status:** Awaiting investigation
**Blocker For:** TC-WARM-001, TC-WARM-002, TC-WARM-003, TC-WARM-004, TC-WARM-005, all warm start tests
**Last Updated:** 2026-02-05
