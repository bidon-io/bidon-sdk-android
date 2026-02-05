# Root Cause Analysis: GetTokensUseCase Interface Modification

**Discovered:** Phase 05 UAT, Test 12
**Status:** Root cause identified
**Impact:** Common SDK interface modified for V2-specific feature

---

## Summary

The `GetTokensUseCase` interface (common SDK code) was modified to add a `skipDemandIds` parameter in commit `78a3239a`. This violates the isolation principle: V2 ad caching logic should be contained in the `.denis` package without modifying shared SDK interfaces.

**Russian feedback translation:**
"мы переделали get token а это общая часть мы не должны были или надо было создать новую логику именно для denis пакета не трогать основную логику"

Translation: "we modified get token and this is common code, we should not have done this, or we should have created new logic specifically for denis package without touching the main logic"

---

## Files Modified (Common SDK Code)

### 1. GetTokensUseCase.kt
**Location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt`

**Change:** Added `skipDemandIds: Set<String> = emptySet()` parameter

```kotlin
// BEFORE (common SDK interface)
internal interface GetTokensUseCase {
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
    ): Map<String, TokenInfo>
}

// AFTER (modified for V2)
internal interface GetTokensUseCase {
    /**
     * Collect tokens from bidding adapters.
     *
     * @param adTypeParam Ad type parameters
     * @param adaptersSource Source of adapters to collect tokens from
     * @param tokenTimeout Timeout for token collection per adapter
     * @param skipDemandIds Set of demand IDs to skip (cached RTB payloads)  ← V2-SPECIFIC
     * @return Map of demandId to TokenInfo
     */
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String> = emptySet(),  ← V2-SPECIFIC PARAMETER
    ): Map<String, TokenInfo>
}
```

**Commit:** `78a3239a feat(03-02): add skipDemandIds parameter to GetTokensUseCase`

### 2. GetTokensUseCaseImpl.kt
**Location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt`

**Change:** Implementation now filters out cached demand IDs

```kotlin
override suspend fun invoke(
    adTypeParam: AdTypeParam,
    adaptersSource: AdaptersSource,
    tokenTimeout: Long,
    skipDemandIds: Set<String>  ← V2-SPECIFIC PARAMETER
): Map<String, TokenInfo> = withContext(SdkDispatchers.Default) {
    // Filter bidding adapters AND skip cached ones
    val allBiddingAdapters = adaptersSource.adapters
        .filterIsInstance<Adapter.Bidding>()

    val biddingAdapters = allBiddingAdapters
        .filter { it.demandId.demandId !in skipDemandIds }  ← V2-SPECIFIC LOGIC
        .onEach(Adapter::applyRegulation)

    // Log skipped adapters for debugging (per 03-CONTEXT.md decision)
    val skippedCount = allBiddingAdapters.size - biddingAdapters.size
    if (skippedCount > 0) {
        logInfo(TAG, "Token collection: ${biddingAdapters.size} adapters, $skippedCount skipped (cached RTB payloads)")  ← V2-SPECIFIC LOGGING
        skipDemandIds.forEach { demandId ->
            logInfo(TAG, "Skipped token collection for demandId=$demandId (cached payload)")
        }
    } else {
        logInfo(TAG, "Token collection: ${biddingAdapters.size} adapters, 0 skipped")
    }

    // ... rest of implementation
}
```

**Commit:** `82ed3da6 feat(03-02): implement skipDemandIds filtering in GetTokensUseCaseImpl`

---

## Where This Interface Is Used

### 1. AuctionImpl.kt (Original SDK, line 83)
**Location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt`

```kotlin
internal class AuctionImpl(
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,  ← Injected via constructor
    // ... other dependencies
) : Auction {
    // ...

    override suspend fun load(adTypeParam: AdTypeParam, demandAd: DemandAd?): AuctionResult {
        // ...
        val tokens = getTokens(  ← Uses default emptySet() for skipDemandIds
            adTypeParam = adTypeParam,
            adaptersSource = adaptersSource,
            tokenTimeout = biddingConfig.tokenTimeout
        )
        // ...
    }
}
```

**Impact:** Still works because of default parameter value, but semantically couples original SDK to V2 concept.

### 2. CoordinationLayer.kt (V2 Implementation, line 252)
**Location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt`

```kotlin
internal class CoordinationLayer(
    private val adaptersSource: AdaptersSource,
    private val getTokens: GetTokensUseCase,  ← Injected via constructor
    private val getAuctionRequest: GetAuctionRequestUseCase,
    private val orchestrator: ParallelAuctionOrchestrator,
    private val lifecycleManager: LifecycleManager,
) {
    // ...

    suspend fun coordinateAuction(
        adTypeParam: AdTypeParam,
        tokenTimeout: Long,
        demandAd: DemandAd?,
    ): AuctionCompletionType {
        // ...

        // Step 1: Collect tokens (with skip optimization)
        val tokens = getTokens(  ← USES V2-SPECIFIC PARAMETER
            adTypeParam = adTypeParam,
            adaptersSource = adaptersSource,
            tokenTimeout = tokenTimeout,
            skipDemandIds = skipDemandIds,  ← V2 passes cached demand IDs
        )
        // ...
    }
}
```

**This is the V2-specific usage that should have been isolated.**

### 3. DI Registration
**Location:** `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/utils/di/DI.kt`

```kotlin
singleton<GetTokensUseCase> { GetTokensUseCaseImpl() }
```

Single global implementation shared by both V1 (AuctionImpl) and V2 (CoordinationLayer).

---

## Why This Is A Problem

### 1. Violates Isolation Principle
- **Requirement:** V2 ad caching should be in `org.bidon.sdk.ads.cache.denis` package
- **Reality:** Common SDK interface modified to support V2 feature
- **Impact:** V1 code (AuctionImpl) now has semantic dependency on V2 concept (skipDemandIds)

### 2. Breaks Clean Architecture
- **Interface pollution:** Common interface carries V2-specific parameter
- **Documentation leakage:** Interface docs mention "cached RTB payloads" (V2 concept)
- **Log pollution:** Implementation logs V2-specific messages even when V1 uses it

### 3. Future Maintenance Risk
- If V2 is removed/refactored, common interface has orphaned parameter
- Other developers might misunderstand skipDemandIds purpose in V1 context
- Testing complexity: V1 tests now see V2-related parameter

---

## Root Cause: Why This Happened

### Planning Decision (03-CONTEXT.md)
From `/Users/glavatskikh/StudioProjects/bidon-sdk-android/.planning/phases/03-coordination-layer/03-CONTEXT.md`:

```markdown
### Token Collection Skipping
- Skip token collection for ALL adapters with valid RTB_PAYLOAD cache entries
- Log skipped tokens in stats (no separate SkippedTokens event sent to /v2/stats)
- Token collection failure cannot happen for adapters with cached payloads
```

**Decision:** Extend existing GetTokensUseCase interface instead of creating V2-specific abstraction.

### Implementation Plan (03-02-PLAN.md)
From `/Users/glavatskikh/StudioProjects/bidon-sdk-android/.planning/phases/03-coordination-layer/03-02-PLAN.md`:

```markdown
<objective>
Extend GetTokensUseCase interface and implementation to support skipping token
collection for adapters with cached RTB payloads.
</objective>
```

**Approach:** Direct modification of common interface with backward-compatible default.

### Why It Seemed Reasonable At The Time
1. **Backward compatibility:** Default parameter `emptySet()` means existing code still works
2. **Code reuse:** Avoid duplicating token collection logic
3. **Simplicity:** Single implementation instead of V1/V2 variants
4. **Performance:** Direct parameter passing instead of abstraction layers

---

## Correct Isolation Strategy

### Option 1: Wrapper Pattern (Recommended)

Create V2-specific wrapper in `.denis` package that delegates to original use case:

```kotlin
// NEW FILE: bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensWithSkipUseCase.kt
package org.bidon.sdk.ads.cache.denis.usecases

internal class GetTokensWithSkipUseCase(
    private val delegate: GetTokensUseCase,
) {
    /**
     * V2-specific token collection with skip optimization.
     * Filters out demand IDs with cached RTB payloads before delegating.
     */
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String>,
    ): Map<String, TokenInfo> {
        // V2-specific logging
        if (skipDemandIds.isNotEmpty()) {
            logInfo(TAG, "V2: Skipping token collection for ${skipDemandIds.size} cached adapters")
        }

        // Filter adapters BEFORE calling delegate
        val filteredAdapters = AdaptersSource.filtered(
            adaptersSource,
            excludeDemandIds = skipDemandIds
        )

        // Delegate to original use case with filtered adapters
        return delegate(
            adTypeParam = adTypeParam,
            adaptersSource = filteredAdapters,
            tokenTimeout = tokenTimeout
        )
    }
}
```

**Benefits:**
- Original `GetTokensUseCase` interface unchanged
- V2 logic isolated in `.denis` package
- Clear ownership: wrapper is V2-specific, delegate is common
- Easy to test independently

**Changes Required:**
1. Revert `GetTokensUseCase.kt` to original (remove skipDemandIds)
2. Revert `GetTokensUseCaseImpl.kt` to original (remove filtering logic)
3. Create `GetTokensWithSkipUseCase.kt` in `.denis.usecases` package
4. Update `CoordinationLayer` to use wrapper instead of delegate
5. Create `AdaptersSource.filtered()` extension or helper

### Option 2: Separate V2 Implementation

Create complete V2 token collection implementation:

```kotlin
// NEW FILE: bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensUseCaseV2.kt
package org.bidon.sdk.ads.cache.denis.usecases

internal interface GetTokensUseCaseV2 {
    suspend operator fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        skipDemandIds: Set<String>,
    ): Map<String, TokenInfo>
}

internal class GetTokensUseCaseV2Impl : GetTokensUseCaseV2 {
    // Complete implementation with V2-specific logic
    // Copy-paste from GetTokensUseCaseImpl but with skipDemandIds support
}
```

**Benefits:**
- Complete isolation (no shared code)
- V2 can evolve independently
- Clear V1 vs V2 boundary

**Drawbacks:**
- Code duplication (30-50 lines)
- Two implementations to maintain
- Potential drift between V1 and V2

### Option 3: Strategy Pattern

Keep single implementation but extract filtering strategy:

```kotlin
// NEW FILE: bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/TokenCollectionFilter.kt
package org.bidon.sdk.ads.cache.denis.usecases

internal interface TokenCollectionFilter {
    fun shouldCollectToken(demandId: String): Boolean
}

internal class SkipCachedFilter(
    private val skipDemandIds: Set<String>
) : TokenCollectionFilter {
    override fun shouldCollectToken(demandId: String): Boolean {
        return demandId !in skipDemandIds
    }
}

// Original GetTokensUseCaseImpl accepts optional filter
internal class GetTokensUseCaseImpl : GetTokensUseCase {
    override suspend fun invoke(
        adTypeParam: AdTypeParam,
        adaptersSource: AdaptersSource,
        tokenTimeout: Long,
        filter: TokenCollectionFilter = AcceptAllFilter,  // V1 uses default
    ): Map<String, TokenInfo> {
        // Filter using strategy
        val biddingAdapters = adaptersSource.adapters
            .filterIsInstance<Adapter.Bidding>()
            .filter { filter.shouldCollectToken(it.demandId.demandId) }
        // ...
    }
}
```

**Benefits:**
- Single implementation
- Extensible filtering
- Clear responsibility separation

**Drawbacks:**
- More complex interface
- Still modifies common code (filter parameter)

---

## Recommended Solution: Option 1 (Wrapper Pattern)

### Why Wrapper Pattern Is Best

1. **Zero impact on common SDK:** GetTokensUseCase reverts to original
2. **Clear ownership:** Wrapper lives in `.denis` package, owned by V2
3. **Minimal code duplication:** Only wrapper logic, not token collection
4. **Easy testing:** Mock delegate for wrapper tests
5. **Clean migration:** If V2 becomes default, wrapper becomes unnecessary but harmless

### Implementation Steps

1. **Revert common SDK changes**
   - Remove skipDemandIds parameter from GetTokensUseCase interface
   - Remove filtering logic from GetTokensUseCaseImpl
   - Remove V2-specific logging from GetTokensUseCaseImpl

2. **Create wrapper in .denis package**
   - New file: `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensWithSkipUseCase.kt`
   - Implement adapter filtering before delegation
   - Add V2-specific logging in wrapper

3. **Update CoordinationLayer**
   - Change dependency from `GetTokensUseCase` to `GetTokensWithSkipUseCase`
   - Pass skipDemandIds to wrapper instead of delegate

4. **Update DI registration**
   - Keep original `GetTokensUseCase` registration for AuctionImpl
   - Add `GetTokensWithSkipUseCase` registration for CoordinationLayer
   - Wire wrapper to delegate via DI

### Helper: AdaptersSource Filtering

Need helper to create filtered AdaptersSource:

```kotlin
// Location: bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/AdaptersSourceExt.kt
package org.bidon.sdk.ads.cache.denis.usecases

internal class FilteredAdaptersSource(
    private val delegate: AdaptersSource,
    private val excludeDemandIds: Set<String>,
) : AdaptersSource {
    override val adapters: List<Adapter>
        get() = delegate.adapters.filter { adapter ->
            adapter.demandId.demandId !in excludeDemandIds
        }
}

internal fun AdaptersSource.withExcludedDemandIds(
    excludeDemandIds: Set<String>
): AdaptersSource {
    if (excludeDemandIds.isEmpty()) return this
    return FilteredAdaptersSource(this, excludeDemandIds)
}
```

---

## Files To Modify (Isolation Fix)

### Files to Revert (Common SDK)
1. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt`
   - Remove skipDemandIds parameter
   - Remove V2-specific documentation

2. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt`
   - Remove skipDemandIds parameter from override
   - Remove filtering logic (lines 27-44)
   - Remove V2-specific logging

### Files to Create (V2 Package)
3. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensWithSkipUseCase.kt`
   - New wrapper class with skipDemandIds support
   - V2-specific logging
   - Delegation to GetTokensUseCase

4. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/FilteredAdaptersSource.kt`
   - Helper to filter adapters by demand ID
   - Extension function for convenient usage

### Files to Update (V2 Integration)
5. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt`
   - Change constructor parameter from `GetTokensUseCase` to `GetTokensWithSkipUseCase`
   - Update call site (line 252)

6. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/utils/di/DI.kt`
   - Keep original `GetTokensUseCase` registration
   - Add `GetTokensWithSkipUseCase` registration
   - Wire wrapper to delegate

7. `/Users/glavatskikh/StudioProjects/bidon-sdk-android/bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheFactoryImpl.kt`
   - Update CoordinationLayer instantiation to inject wrapper

---

## Impact Assessment

### Breaking Changes
- **None:** Wrapper pattern is transparent to users
- **V1 (AuctionImpl):** No changes, uses original GetTokensUseCase
- **V2 (CoordinationLayer):** Uses wrapper, functionally identical

### Testing Impact
- **V1 tests:** No changes needed (unchanged interface)
- **V2 tests:** Add tests for wrapper filtering logic
- **Integration tests:** No changes (same behavior)

### Performance Impact
- **Negligible:** One extra method call (wrapper → delegate)
- **Memory:** One additional object per CoordinationLayer instance
- **Runtime:** <1ms overhead for filtering adapters

---

## Lessons Learned

### What Went Wrong
1. **Premature optimization:** Chose code reuse over isolation
2. **Backward compatibility trap:** Default parameter masked interface pollution
3. **Missing design review:** No discussion of isolation strategies

### What Should Have Happened
1. **Phase 03 planning:** Discuss isolation vs. reuse trade-offs
2. **Architecture review:** Evaluate wrapper/strategy patterns before implementation
3. **UAT phase:** Catch interface pollution during verification

### Design Principles Violated
1. **Single Responsibility:** GetTokensUseCase now serves V1 and V2 needs
2. **Open/Closed:** Modified existing interface instead of extending
3. **Dependency Inversion:** V2 depends on common interface, should depend on abstraction

---

## Next Steps

1. **Create gap closure plan** (via `plan-phase --gaps`)
   - Design wrapper pattern details
   - Verify DI wiring approach
   - Plan testing strategy

2. **Implement isolation fix**
   - Revert common SDK changes
   - Create wrapper in .denis package
   - Update CoordinationLayer and DI

3. **Verify isolation**
   - Confirm GetTokensUseCase has no V2 concepts
   - Confirm AuctionImpl unchanged
   - Confirm CoordinationLayer uses wrapper

4. **Update documentation**
   - Update ROADMAP.md with lessons learned
   - Update ARCHITECTURE.md with wrapper pattern
   - Update 03-VERIFICATION.md with corrected design

---

## References

### Commits
- `78a3239a` - feat(03-02): add skipDemandIds parameter to GetTokensUseCase
- `82ed3da6` - feat(03-02): implement skipDemandIds filtering in GetTokensUseCaseImpl
- `62efa917` - feat(03-03): complete CoordinationLayer with full auction orchestration

### Planning Documents
- `.planning/phases/03-coordination-layer/03-CONTEXT.md` - Token skip decision
- `.planning/phases/03-coordination-layer/03-02-PLAN.md` - Interface modification plan
- `.planning/REQUIREMENTS.md` - INT-03: "Переиспользование GetTokensUseCase с поддержкой skipDemandIds"

### Related Files
- `bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt`
- `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt`
- `bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt`
- `bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt`

---

**Analysis completed:** 2026-02-05
**Next action:** Create gap closure plan with wrapper pattern implementation
