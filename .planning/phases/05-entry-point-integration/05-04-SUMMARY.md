---
phase: 05-entry-point-integration
plan: 04
subsystem: coordination-layer
completed: 2026-02-05
duration: 4min
tags: [refactoring, isolation, wrapper-pattern, kotlin, gap-closure]

requires:
  - 03-02-PLAN.md (token collection with skip - REVERTED)
  - 03-03-PLAN.md (CoordinationLayer orchestration)
  - 05-03-PLAN.md (AdCacheDenisFactory)

provides:
  - GetTokensUseCase reverted to original 3-param interface (no V2 pollution)
  - GetTokensWithSkipUseCase wrapper in denis package (V2 skip logic isolated)
  - FilteredAdaptersSource helper for pre-filtering adapters
  - CoordinationLayer uses V2-specific wrapper
  - AuctionImpl (V1) completely unaffected

affects:
  - None - isolation fix with no downstream impact

tech-stack:
  added: []
  patterns:
    - "Wrapper pattern for feature isolation"
    - "Filtered delegate pattern (FilteredAdaptersSource)"

key-files:
  created:
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensWithSkipUseCase.kt
  modified:
    - bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt (reverted)
    - bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt (reverted)
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt
    - bidon/src/main/java/org/bidon/sdk/ads/cache/denis/AdCacheDenisFactory.kt

decisions:
  - decision: "Wrapper pattern chosen over separate V2 implementation"
    rationale: "Minimal code duplication, clear ownership, zero impact on common SDK"
    context: "Root cause analysis recommended wrapper vs separate implementation"
  - decision: "FilteredAdaptersSource is private class in same file"
    rationale: "Simple helper with single use case, no need for separate file"
  - decision: "Wrapper created in factory, not via DI"
    rationale: "V2-specific component, no need to register globally"
  - decision: "GetTokensUseCase DI registration unchanged"
    rationale: "AuctionImpl (V1) continues using original use case"
---

# Phase 05 Plan 04: GetTokensUseCase Interface Isolation Summary

**One-liner:** Wrapper pattern isolates V2 skip logic in denis package, reverting common SDK pollution

## What Was Built

### Architecture Changes
Implemented wrapper pattern to isolate V2-specific token skip optimization from common SDK interface:

**Before (Plan 03-02):**
```
GetTokensUseCase (common SDK)
├── invoke(..., skipDemandIds: Set<String>)  ← V2-SPECIFIC PARAMETER
└── GetTokensUseCaseImpl
    ├── Filter skipDemandIds  ← V2-SPECIFIC LOGIC
    └── Log skipped adapters  ← V2-SPECIFIC LOGGING

AuctionImpl (V1) → GetTokensUseCase (uses default skipDemandIds = emptySet())
CoordinationLayer (V2) → GetTokensUseCase (passes skipDemandIds)
```

**After (Plan 05-04):**
```
GetTokensUseCase (common SDK - REVERTED)
└── invoke(adTypeParam, adaptersSource, tokenTimeout)  ← ORIGINAL 3-PARAM

GetTokensWithSkipUseCase (denis package - NEW)
└── invoke(..., skipDemandIds)  ← V2-SPECIFIC
    ├── FilteredAdaptersSource  ← Filters before delegation
    └── delegate → GetTokensUseCase  ← Calls original use case

AuctionImpl (V1) → GetTokensUseCase (direct, unchanged)
CoordinationLayer (V2) → GetTokensWithSkipUseCase → GetTokensUseCase (wrapped)
```

### Implementation Details

**GetTokensWithSkipUseCase.kt:**
- V2-specific wrapper class in `org.bidon.sdk.ads.cache.denis.usecases` package
- Accepts `skipDemandIds` parameter (V2 optimization)
- Short-circuits to delegate if skipDemandIds is empty (optimization)
- Logs skip information for debugging (V2-specific logging)
- Creates `FilteredAdaptersSource` to exclude cached demand IDs
- Delegates to original `GetTokensUseCase` with filtered adapters

**FilteredAdaptersSource:**
- Private helper class in same file
- Implements `AdaptersSource` interface
- Overrides `adapters` property to filter by excludeDemandIds
- Delegates `add()` to underlying source (no filtering on add)

**Wiring Changes:**
- **CoordinationLayer:** Constructor parameter changed from `GetTokensUseCase` to `GetTokensWithSkipUseCase`
- **AdCacheDenisFactory:** Creates wrapper locally (`GetTokensWithSkipUseCase(delegate = getTokens)`)
- **DI (unchanged):** Original `GetTokensUseCase` registration remains for V1 (AuctionImpl)

### Files Created
1. **bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensWithSkipUseCase.kt** (65 lines)
   - GetTokensWithSkipUseCase class
   - FilteredAdaptersSource private class
   - Companion object with TAG

### Files Reverted
1. **bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt**
   - Removed `skipDemandIds` parameter
   - Removed V2-specific KDoc comments
   - Restored original 3-parameter interface

2. **bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt**
   - Removed `skipDemandIds` parameter from override
   - Removed filtering logic (lines that checked `it.demandId.demandId !in skipDemandIds`)
   - Removed V2-specific logging
   - Restored original implementation structure

### Files Modified
1. **bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt**
   - Import: `GetTokensWithSkipUseCase` (denis.usecases) instead of `GetTokensUseCase` (auction.usecases)
   - Constructor: `getTokensWithSkip: GetTokensWithSkipUseCase` parameter
   - Call site (line 252): `getTokensWithSkip(...)` instead of `getTokens(...)`

2. **bidon/src/main/java/org/bidon/sdk/ads/cache/denis/AdCacheDenisFactory.kt**
   - Import: Added `GetTokensWithSkipUseCase`
   - Instantiation: `val getTokensWithSkip = GetTokensWithSkipUseCase(delegate = getTokens)`
   - Wiring: Pass `getTokensWithSkip` to CoordinationLayer constructor

## Decisions Made

### Wrapper Pattern Over Separate Implementation
**Chose:** Wrapper pattern that delegates to original GetTokensUseCase
**Rejected:** Separate V2 implementation with duplicated token collection logic
**Reason:**
- Minimal code duplication (only filtering logic, not token collection)
- Clear ownership boundaries (wrapper in .denis, delegate in common SDK)
- Easy to test independently (mock delegate)
- Zero impact on common SDK interface
- If V2 becomes default, wrapper becomes unnecessary but harmless

**Trade-offs:**
- One extra method call (negligible performance impact)
- Slightly more complex dependency graph (factory creates wrapper)

### FilteredAdaptersSource as Private Class
**Chose:** Private class in GetTokensWithSkipUseCase.kt
**Rejected:** Public utility class in separate file
**Reason:**
- Single use case (only used by wrapper)
- Simple implementation (8 lines of logic)
- No need for reusability or testing in isolation
- Keeps wrapper file self-contained

### Local Wrapper Creation in Factory
**Chose:** Create wrapper in AdCacheDenisFactory.create()
**Rejected:** Register wrapper in DI as singleton
**Reason:**
- V2-specific component (no reason for global registration)
- Factory already creates instance-scoped components
- Keeps DI simple (one less registration)
- Wrapper lifetime matches CoordinationLayer (both created per ad instance)

**Note:** Original GetTokensUseCase DI registration unchanged - AuctionImpl (V1) continues using it directly.

## Root Cause: Why This Fix Was Needed

### UAT Feedback (05-UAT.md, Test 12)
User explicitly stated: "мы переделали get token а это общая часть мы не должны были или надо было создать новую логику именно для denis пакета не трогать основную логику"

Translation: "we modified get token and this is common code, we should not have done this, or we should have created new logic specifically for denis package without touching the main logic"

### What Went Wrong in Phase 03
**Plan 03-02** modified `GetTokensUseCase` interface to add `skipDemandIds` parameter:
- **Interface pollution:** Common SDK interface carried V2-specific parameter
- **Documentation leakage:** Interface docs mentioned "cached RTB payloads" (V2 concept)
- **Log pollution:** Implementation logged V2-specific messages even when V1 used it
- **Semantic coupling:** V1 code (AuctionImpl) had dependency on V2 concept (default skipDemandIds = emptySet())

### Why It Seemed Reasonable At The Time
1. **Backward compatibility:** Default parameter meant existing code still worked
2. **Code reuse:** Avoided duplicating token collection logic
3. **Simplicity:** Single implementation instead of V1/V2 variants
4. **Performance:** Direct parameter passing instead of abstraction layers

### Design Principles Violated
1. **Single Responsibility:** GetTokensUseCase served both V1 and V2 needs
2. **Open/Closed:** Modified existing interface instead of extending
3. **Dependency Inversion:** V2 depended on common interface, should have depended on abstraction

## Deviations from Plan

None - plan executed exactly as written.

Plan correctly identified wrapper pattern approach from root cause analysis (gettokens-root-cause.md).

## Technical Validation

### Build Verification
```bash
./gradlew :bidon:assembleRelease
```
**Result:** BUILD SUCCESSFUL - both productionRelease and serverlessRelease variants compile

### Interface Verification
```bash
grep "skipDemandIds" GetTokensUseCase.kt
# Output: (empty - no matches)

grep "skipDemandIds" GetTokensUseCaseImpl.kt
# Output: (empty - no matches)

grep "skipDemandIds" GetTokensWithSkipUseCase.kt
# Output: 5 matches (V2 wrapper only)
```

### Import Verification
```bash
grep "import.*denis.usecases" CoordinationLayer.kt
# Output: import org.bidon.sdk.ads.cache.denis.usecases.GetTokensWithSkipUseCase
```

### V1 Path Verification
**AuctionImpl.kt (line 83-87):**
```kotlin
val tokens = getTokens(
    adTypeParam = adTypeParam,
    adaptersSource = adaptersSource,
    tokenTimeout = biddingConfig.tokenTimeout
)
```
**Status:** Unchanged - still uses original GetTokensUseCase with 3 parameters

### V2 Path Verification
**CoordinationLayer.kt (line 252-257):**
```kotlin
val tokens = getTokensWithSkip(
    adTypeParam = adTypeParam,
    adaptersSource = adaptersSource,
    tokenTimeout = tokenTimeout,
    skipDemandIds = skipDemandIds,
)
```
**Status:** Now uses wrapper with V2-specific skipDemandIds parameter

## Task Commits

| Task | Description | Commit | Files |
|------|-------------|--------|-------|
| 1 | Revert GetTokensUseCase interface and implementation to original | 984391d5 | GetTokensUseCase.kt, GetTokensUseCaseImpl.kt |
| 2 | Create GetTokensWithSkipUseCase wrapper and update wiring | 2442881d | GetTokensWithSkipUseCase.kt, CoordinationLayer.kt, AdCacheDenisFactory.kt |

**Total commits:** 2 (atomic per-task commits)

## Integration Points

### Unchanged (V1 Path)
- **AuctionImpl** → **GetTokensUseCase** (direct usage, 3 parameters)
- **DI registration:** `singleton<GetTokensUseCase> { GetTokensUseCaseImpl() }`
- **Token collection:** Standard flow with no skip optimization

### Updated (V2 Path)
- **AdCacheDenisFactory** creates **GetTokensWithSkipUseCase(delegate = getTokens)**
- **CoordinationLayer** uses **getTokensWithSkip** (wrapper, 4 parameters)
- **Token collection:** Filters adapters by skipDemandIds before delegating

### Isolation Achieved
- **Common SDK:** GetTokensUseCase has no V2 concepts
- **V2 Package:** All skip logic in denis/usecases/GetTokensWithSkipUseCase
- **Clear boundary:** Wrapper pattern makes V2 feature explicit and contained

## Lessons Learned

### What Went Well
1. **Root cause analysis effective:** gettokens-root-cause.md identified exact problem and solution
2. **Wrapper pattern successful:** Clean isolation with minimal code duplication
3. **Git history helpful:** Used `git show 78a3239a^:...` to restore pre-V2 version
4. **UAT caught the issue:** User feedback prevented shipping interface pollution

### What Could Be Better
1. **Earlier architecture review:** Plan 03-02 should have discussed isolation strategies
2. **Principle enforcement:** Should have questioned modifying common SDK interface during planning
3. **Pattern library:** Document wrapper pattern for future similar scenarios

### Design Pattern: Feature Isolation via Wrapper
**Problem:** Feature-specific logic needs to augment common interface without modifying it

**Solution:**
1. Keep common interface unchanged (3-param GetTokensUseCase)
2. Create feature-specific wrapper in feature package (GetTokensWithSkipUseCase)
3. Wrapper accepts feature parameters (skipDemandIds)
4. Wrapper filters/transforms inputs before delegating
5. Feature code uses wrapper, common code uses original

**Benefits:**
- Zero impact on common code
- Clear ownership boundaries
- Easy to remove feature (delete wrapper, unwire)
- Testable in isolation

**Applicable to:** Cross-cutting optimizations, experimental features, version-specific logic

## Next Phase Readiness

### Phase 5 Status
- ✅ Plan 01: AdCacheDenisImpl entry point created
- ✅ Plan 02: DI wiring and factory integration complete
- ✅ Plan 03: Factory isolation and API contract fixes
- ✅ Plan 04: GetTokensUseCase interface isolation (this plan)
- ⏳ Plan 05: E2E validation and callback wiring (pending)

### Remaining Work
**Plan 05-05 (Final):**
- Fix CallbackCoordinator no-op callback issue
- E2E validation of full V2 flow
- Production readiness checklist

### Known Issues
From STATE.md:
```
🔴 CRITICAL: CallbackCoordinator created with no-op callbacks
  - Impact: Multiple cache() calls won't fire callbacks correctly
  - Solution needed: Create orchestrator per-auction with actual callbacks
  - Priority: HIGH - blocks multi-auction scenarios
```

**This plan did NOT fix callback issue** - still pending for 05-05.

### Integration Dependencies
None - this was a refactoring/isolation fix with no new features.

### Testing Recommendations
For Plan 05-05:
1. **V1 regression test:** Verify AuctionImpl still works with reverted GetTokensUseCase
2. **V2 skip logic test:** Verify wrapper correctly filters cached demand IDs
3. **Logging test:** Verify skip logs appear only in V2 path, not V1
4. **Performance test:** Verify wrapper overhead is negligible (<1ms)

## Self-Check: PASSED

**Created files verification:**
```bash
[ -f "bidon/src/main/java/org/bidon/sdk/ads/cache/denis/usecases/GetTokensWithSkipUseCase.kt" ] && echo "FOUND"
# Result: FOUND
```

**Commit verification:**
```bash
git log --oneline --all | grep -E "984391d5|2442881d"
# Result:
# 2442881d feat(05-04): isolate V2 skip logic in GetTokensWithSkipUseCase wrapper
# 984391d5 refactor(05-04): revert GetTokensUseCase to original interface
```

All claims in summary verified against actual repository state.

---

**Summary completed:** 2026-02-05
**Duration:** 4 minutes
**Status:** All tasks complete, isolation successful, build verified
