# E2E Test Hardcoding Investigation

**Issue:** E2E tests need hardcoded configuration for V2 testing
**Source:** UAT Test 12, docs/testing/E2E_TEST_REPORT.md
**Date:** 2026-02-05

## Root Cause Analysis

### Problem Statement
E2E tests currently use **V1 AdCacheImpl** instead of **V2 AdCacheDenisImpl** because the `cache_size` extra is not being set in test scenarios. The version selection mechanism relies on reading `demandAd.getExtras()["cache_size"]` to determine which implementation to instantiate.

### Version Selection Mechanism

**Location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheFactoryImpl.kt:35`

```kotlin
override fun create(demandAd: DemandAd): AdCache {
    val version = AdCacheVersion.fromInt(demandAd.getExtras()["cache_size"] as? Int)
    return when (version) {
        AdCacheVersion.V1 -> AdCacheImpl(...)        // Default when cache_size is null
        AdCacheVersion.V2 -> AdCacheDenisImpl(...)   // Requires cache_size = 2
        AdCacheVersion.V3 -> AdCacheAndreiImpl(...)  // Requires cache_size = 3
        AdCacheVersion.V4 -> AdCacheVladimirImpl(...)// Requires cache_size = 4
        AdCacheVersion.V5 -> AdCacheAlexImpl(...)    // Requires cache_size = 5
    }
}
```

**Default Behavior:** When `cache_size` is not set, `AdCacheVersion.fromInt(null)` returns `AdCacheVersion.Default` which is **V1**.

### How Extras Are Set

**DemandAd class:** Implements `Extras` interface via delegation to `ExtrasImpl()`

```kotlin
// bidon/src/main/java/org/bidon/sdk/adapter/DemandAd.kt
public class DemandAd(public val adType: AdType) : Extras by ExtrasImpl()
```

**Extras interface:** Provides `addExtra(key, value)` and `getExtras()` methods

```kotlin
// bidon/src/main/java/org/bidon/sdk/databinders/extras/Extras.kt
public interface Extras {
    public fun addExtra(key: String, value: Any?)
    public fun getExtras(): Map<String, Any>
}
```

**In production:** Ad implementations (InterstitialImpl, RewardedImpl, BannerView) delegate Extras to their internal `demandAd` instance:

```kotlin
// bidon/src/main/java/org/bidon/sdk/ads/interstitial/InterstitialImpl.kt:38-41
internal class InterstitialImpl(
    private val demandAd: DemandAd = DemandAd(AdType.Interstitial)
) : Interstitial, Extras by demandAd
```

**User access:** The public API (InterstitialAd, RewardedAd, BannerView) exposes `addExtra()` method:

```kotlin
// app/src/main/java/org/bidon/demoapp/ui/InterstitialScreen.kt:162-164
interstitial.addExtra("some_extra_obj", interstitial)
interstitial.addExtra("some_extra_int", 123)
interstitial.addExtra("some_extra_data", "some_value")
```

### Current Test Setup

**Test location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/app/src/androidTestServerless/java/org/bidon/demoapp/InterstitialTest.kt`

**Current configuration pattern:**
```kotlin
@Test
fun interstitial_OneRoundAdmob() {
    ServerlessConfigSettings.useAdapters("admob")
    ServerlessAuctionConfig.setLocalAuctionResponse(
        pricefloor = 0.0,
        adUnits = listOf(AdUnit(...))
    )
    // ... test continues but NEVER sets cache_size extra
}
```

**What's missing:** Tests create `InterstitialScreen` composable which internally creates `InterstitialAd`, but the test never calls `interstitial.addExtra("cache_size", 2)` before `loadAd()`.

### Why V2 Needs Hardcoded Values

**The problem:** E2E tests run in the **serverless build variant** which uses hardcoded auction responses instead of real backend calls. However, there's **no serverless configuration mechanism** for setting the `cache_size` extra.

**Production vs Serverless:**

1. **Production flow:**
   - Backend `/config` endpoint returns adapter initialization params
   - Backend `/auction` endpoint could theoretically return `cache_size` in auction response extras
   - **Current gap:** Backend doesn't send `cache_size` parameter yet

2. **Serverless flow:**
   - `ServerlessConfigSettings.getConfigResponse()` returns hardcoded adapter configs
   - `ServerlessAuctionConfig.setLocalAuctionResponse()` accepts hardcoded AdUnits
   - **Current gap:** Neither of these mechanisms set extras on the DemandAd instance

3. **Test environment:**
   - Tests use UI (InterstitialScreen composable)
   - UI creates InterstitialAd internally
   - Test code has NO access to the InterstitialAd instance to call `addExtra()`
   - **Blocker:** Cannot inject `cache_size = 2` into the DemandAd

## What Needs to Be Hardcoded

### Option 1: Extend ServerlessAuctionConfig (Recommended)

**Add cache version parameter to auction configuration:**

```kotlin
// bidon/src/serverless/java/org/bidon/sdk/auction/impl/ServerlessAuctionConfig.kt
fun setLocalAuctionResponse(
    adUnits: List<AdUnit>,
    pricefloor: Double,
    cacheVersion: Int? = null,  // NEW: Allow test to specify V2
    // ... other params
) {
    auctionResponse = AuctionResponse(
        adUnits = adUnits,
        pricefloor = pricefloor,
        extras = cacheVersion?.let { mapOf("cache_size" to it) } ?: emptyMap(),  // NEW
        // ... other fields
    )
}
```

**Problem:** Requires modifying AuctionResponse data class to support extras, which may not exist.

### Option 2: Global Test Configuration (Simpler)

**Add static configuration for test mode:**

```kotlin
// bidon/src/serverless/java/org/bidon/sdk/config/impl/ServerlessConfigSettings.kt
object ServerlessConfigSettings {
    private var cacheVersionOverride: Int? = null

    fun setCacheVersion(version: Int) {
        cacheVersionOverride = version
    }

    internal fun getCacheVersion(): Int? = cacheVersionOverride
}
```

**Usage in tests:**
```kotlin
@Test
fun interstitial_OneRoundAdmob_V2() {
    ServerlessConfigSettings.useAdapters("admob")
    ServerlessConfigSettings.setCacheVersion(2)  // NEW: Force V2
    ServerlessAuctionConfig.setLocalAuctionResponse(...)
    // ... rest of test
}
```

**Then modify DemandAd creation to check this override** (requires adding hook in InterstitialImpl constructor or factory).

### Option 3: Test Utility Helper (Most Isolated)

**Create test-specific helper that modifies DemandAd after creation:**

```kotlin
// app/src/androidTestServerless/java/org/bidon/demoapp/TestUtils.kt
object AdCacheTestConfig {
    fun configureV2ForTesting() {
        // Use reflection or test-only API to inject cache_size
        // This is hacky but keeps production code clean
    }
}
```

**Problem:** Requires either reflection hacks or adding test-only hooks to production code.

### Option 4: Separate Test Composable (Cleanest for Testing)

**Create test-specific screen that exposes the InterstitialAd instance:**

```kotlin
// app/src/androidTestServerless/java/org/bidon/demoapp/TestInterstitialScreen.kt
@Composable
fun TestInterstitialScreen(
    navController: NavHostController,
    cacheVersion: Int? = null,  // NEW: Allow test to specify version
) {
    val interstitial = remember {
        InterstitialAd(auctionKey = "1O16GQT380000").apply {
            cacheVersion?.let { addExtra("cache_size", it) }
        }
    }
    // ... rest identical to InterstitialScreen
}
```

**Usage in tests:**
```kotlin
rule.setContent {
    AppTheme {
        TestInterstitialScreen(
            navController = rememberNavController(),
            cacheVersion = 2  // NEW: Test V2 explicitly
        )
    }
}
```

## Recommended Solution

**Use Option 4 (Separate Test Composable)** for these reasons:

1. **No production code changes** - keeps AdCacheFactoryImpl, ServerlessConfigSettings, and core SDK untouched
2. **Test isolation** - V2 testing configuration lives in test code, not production
3. **Explicit control** - Each test can specify exactly which version to test
4. **Backward compatible** - Existing tests continue working with V1 by default
5. **Follows testing best practices** - Test fixtures separate from production UI

### Implementation Steps

1. **Create `/app/src/androidTestServerless/java/org/bidon/demoapp/TestInterstitialScreen.kt`:**
   - Copy InterstitialScreen composable
   - Add `cacheVersion: Int? = null` parameter
   - Apply `addExtra("cache_size", cacheVersion)` to interstitial instance

2. **Add V2-specific test cases in InterstitialTest.kt:**
   ```kotlin
   @Test
   fun interstitial_V2_WarmStart() {
       ServerlessConfigSettings.useAdapters("admob")
       ServerlessAuctionConfig.setLocalAuctionResponse(...)
       rule.setContent {
           AppTheme {
               TestInterstitialScreen(
                   navController = rememberNavController(),
                   cacheVersion = 2  // Force V2
               )
           }
       }
       // Test warm start scenario
   }
   ```

3. **Verify V2 activation in logs:**
   - Look for `[AdCacheDenisImpl]` tags instead of `[AdCacheImpl]`
   - Verify `[CoordinationLayer] determineStartState()` logs appear
   - Confirm `[RtbProcessor]` and `[CpmProcessor]` activity

## Expected Test Structure

```kotlin
// Existing tests - continue using V1
@Test
fun interstitial_OneRoundAdmob() {
    // Uses production InterstitialScreen → V1 by default
}

// NEW V2 tests
@Test
fun interstitial_V2_ColdStart() {
    ServerlessConfigSettings.useAdapters("admob")
    ServerlessAuctionConfig.setLocalAuctionResponse(...)
    rule.setContent {
        AppTheme {
            TestInterstitialScreen(
                navController = rememberNavController(),
                cacheVersion = 2
            )
        }
    }
    // Verify cold start behavior
}

@Test
fun interstitial_V2_WarmStart() {
    // Similar but test warm start after first load
}
```

## Files Requiring Changes

1. **NEW:** `/app/src/androidTestServerless/java/org/bidon/demoapp/TestInterstitialScreen.kt`
   - Test-specific composable with cacheVersion parameter

2. **MODIFY:** `/app/src/androidTestServerless/java/org/bidon/demoapp/InterstitialTest.kt`
   - Add new test methods using TestInterstitialScreen with cacheVersion = 2

3. **NO CHANGES NEEDED:**
   - AdCacheFactoryImpl (already supports version selection)
   - ServerlessConfigSettings (no modification needed)
   - ServerlessAuctionConfig (no modification needed)
   - Production InterstitialScreen (stays V1 compatible)

## Verification Checklist

- [ ] TestInterstitialScreen.kt created with cacheVersion parameter
- [ ] New test cases added for V2 scenarios (cold start, warm start)
- [ ] Logcat shows `[AdCacheDenisImpl]` tags during V2 tests
- [ ] Logcat shows `[CoordinationLayer]` and processor logs
- [ ] Existing V1 tests still pass unchanged
- [ ] V2 tests can verify warm start latency < 1000ms

## Additional Notes

**Why not use production backend extras?** The production backend doesn't currently send `cache_size` in auction responses. Even if it did, E2E tests run in serverless mode (no real backend calls), so they need local configuration anyway.

**Why not modify DemandAd globally?** Setting `cache_size` globally would affect ALL ad instances in the test app, not just the one being tested. Test-specific configuration is cleaner.

**Future improvement:** If the backend starts sending `cache_size` in auction responses, production apps could dynamically switch between V1 and V2. But for E2E testing, explicit test configuration is still needed.
