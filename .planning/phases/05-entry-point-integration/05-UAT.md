---
status: diagnosed
phase: 05-entry-point-integration
source:
  - 05-01-SUMMARY.md
  - 05-02-SUMMARY.md
started: 2026-02-05T20:15:00Z
updated: 2026-02-05T20:30:00Z
---

## Current Test

[testing complete]

## Tests

### 1. AdCacheDenisImpl implements complete AdCache interface
expected: AdCacheDenisImpl class exists with all 6 required methods (cache, peek, pop, poll, clear, withSettings). Code compiles without errors.
result: issue
reported: "you extend AdCacheFactoryImpl хотя это не нужно было делать постарайся не трогать factory так потому что туда присоединяться другие имплементации лучше все делать в пакете denis"
severity: major

### 2. cache() delegates to CoordinationLayer
expected: cache() method launches coroutine on lifecycleManager.getScope() and calls coordinationLayer.coordinateAuction() with callbacks
result: pass

### 3. pop() returns best ad and cancels auction
expected: pop() retrieves highest eCPM ad from ReadyToShowCache.getBest(), calls lifecycleManager.cancelAuction(), and returns the ad
result: pass

### 4. peek() provides non-destructive read
expected: peek() calls ReadyToShowCache.getBest() without removing the ad from cache
result: pass

### 5. poll() throws when cache empty
expected: poll() calls pop() and throws NoSuchElementException if cache is empty (different from V1 behavior which suspended)
result: issue
reported: "сохрани v1 но на что влияет посмотри в смысле ты не знаешь"
severity: major

### 6. clear() is NO-OP with logging
expected: clear() logs a message but does not clear caches (TTL-based eviction only, per design decision ENTRY-03)
result: pass

### 7. withSettings() configures cache capacity
expected: withSettings() accepts AdCacheSettings and calls ReadyToShowCache.updateCapacity() with the new limit
result: issue
reported: "withSettings не должно сейчас применяться можно закомнтить"
severity: minor

### 8. Factory creates V2 dependencies correctly
expected: AdCacheFactoryImpl.create() with version=V2 instantiates LifecycleManager, RtbProcessor, CpmProcessor, CallbackCoordinator, ParallelAuctionOrchestrator, CoordinationLayer, and AdCacheDenisImpl
result: pass

### 9. Both build variants compile successfully
expected: ./gradlew :bidon:assembleRelease succeeds for both production and serverless build variants without "No parameter" errors
result: pass

### 10. GetAuctionRequestUseCase registered in FlavoredDI only
expected: GetAuctionRequestUseCase has no duplicate registration in main DI.kt, only in FlavoredDI (production: 3-arg constructor, serverless: no-arg constructor)
result: pass

### 11. Lifecycle components are instance-scoped
expected: Each AdCacheDenisImpl instance gets its own LifecycleManager and CoordinationLayer (not singleton), but shares ReadyToShowCache, RtbPayloadCache, WeightModel singletons
result: pass

### 12. Known callback limitation documented
expected: SUMMARY.md documents the CallbackCoordinator issue (no-op callbacks at factory time, orchestrator shared across auctions)
result: issue
reported: "мы переделали get token а это общая часть мы не должны были или надо было создать новую логику именно для denis пакета не трогать основную логику"
severity: major

## Summary

total: 12
passed: 8
issues: 6
pending: 0
skipped: 0

## Gaps

- truth: "All V2 implementation changes should be isolated in .denis package without modifying shared AdCacheFactoryImpl"
  status: failed
  reason: "User reported: you extend AdCacheFactoryImpl хотя это не нужно было делать постарайся не трогать factory так потому что туда присоединяться другие имплементации лучше все делать в пакете denis"
  severity: major
  test: 1
  root_cause: "AdCacheFactoryImpl modified with 45 lines of V2 dependency creation logic (lines 43-87). V3/V4/V5 implementations will each add similar blocks, polluting shared factory."
  artifacts:
    - path: "bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheFactoryImpl.kt"
      issue: "V2 case contains 45 lines of dependency instantiation"
  missing:
    - "Create AdCacheDenisFactory.kt in .denis package"
    - "Move all dependency creation logic to AdCacheDenisFactory.create()"
    - "Revert AdCacheFactoryImpl V2 case to single line: AdCacheDenisFactory.create(...)"
  debug_session: ".planning/debug/factory-isolation.md"

- truth: "poll() should preserve V1 behavior (suspending until first result available)"
  status: failed
  reason: "User reported: сохрани v1 но на что влияет посмотри в смысле ты не знаешь"
  severity: major
  test: 5
  root_cause: "V1 poll() suspends with results.first { it.isNotEmpty() } (wait-until-ready). V2 poll() calls pop() immediately and throws (fail-fast). Semantic difference breaks API contract even though poll() not currently used in production."
  artifacts:
    - path: "bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheDenisImpl.kt"
      issue: "poll() throws NoSuchElementException instead of suspending"
    - path: "bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheImpl.kt"
      issue: "V1 reference - uses results.first() suspending operator"
  missing:
    - "Implement suspending wait for ReadyToShowCache to contain ads"
    - "Observe cache state changes until ad becomes available"
    - "Match V1 'wait-until-ready' semantics"
  debug_session: ".planning/debug/poll-v1-behavior.md"

- truth: "withSettings() should not be active in V2 implementation"
  status: failed
  reason: "User reported: withSettings не должно сейчас применяться можно закомнтить"
  severity: minor
  test: 7
  root_cause: "Architectural mismatch - V1 uses instance-scoped cache with per-instance capacity, V2 uses application-wide singleton ReadyToShowCache. Calling withSettings() on one AdCache instance modifies global cache affecting all instances."
  artifacts:
    - path: "bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheDenisImpl.kt"
      issue: "withSettings() calls ReadyToShowCache.setCapacity() (singleton)"
  missing:
    - "Convert withSettings() to NO-OP with explanatory logging"
    - "Match pattern established by clear() method"
  debug_session: ".planning/debug/withsettings-active.md"

- truth: "GetTokensUseCase interface should not be modified - V2 logic must be isolated in .denis package"
  status: failed
  reason: "User reported: мы переделали get token а это общая часть мы не должны были или надо было создать новую логику именно для denis пакета не трогать основную логику"
  severity: major
  test: 12
  root_cause: "GetTokensUseCase interface (common SDK) modified to add skipDemandIds parameter for V2 caching optimization. Common interface semantically coupled to V2 implementation even though V1 uses default emptySet()."
  artifacts:
    - path: "bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt"
      issue: "Added skipDemandIds parameter with V2-specific documentation"
    - path: "bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/GetTokensUseCaseImpl.kt"
      issue: "Added filtering logic and V2-specific logging"
  missing:
    - "Create GetTokensWithSkipUseCase wrapper in denis/usecases/"
    - "Revert GetTokensUseCase interface to original"
    - "Wrapper filters adapters before delegating to original use case"
  debug_session: ".planning/debug/gettokens-root-cause.md"

- truth: "CallbackCoordinator should be created per-auction with actual callbacks, not shared with no-ops"
  status: failed
  reason: "Known issue from SUMMARY.md: CallbackCoordinator created with no-op callbacks at factory time, orchestrator shared across auctions. Multiple cache() calls won't fire callbacks correctly."
  severity: major
  test: 12
  root_cause: "Scope mismatch - CallbackCoordinator created at factory time (instance-scoped) with no-op callbacks, but actual callbacks are per-auction (cache()-call scoped). CoordinationLayer.handleColdStart() receives real callbacks but never passes them to orchestrator."
  artifacts:
    - path: "bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheFactoryImpl.kt"
      issue: "Creates CallbackCoordinator with no-ops at factory time (lines 57-63)"
    - path: "bidon/src/main/java/org/bidon/sdk/ads/cache/denis/orchestration/CoordinationLayer.kt"
      issue: "handleColdStart() receives callbacks but doesn't pass to orchestrator (line 294)"
  missing:
    - "Remove CallbackCoordinator and ParallelAuctionOrchestrator from factory"
    - "Pass rtbProcessor and cpmProcessor directly to CoordinationLayer"
    - "Create CallbackCoordinator per-auction in handleColdStart() with actual callbacks"
    - "Create ParallelAuctionOrchestrator per-auction with fresh coordinator"
  debug_session: ".planning/debug/callback-coordinator-per-auction.md"

- truth: "E2E tests need hardcoded configuration for V2 testing"
  status: failed
  reason: "User reported: @docs/testing/E2E_TEST_REPORT.md тут еще проблема в тесатах надо захардкодить для тестов"
  severity: major
  test: 12
  root_cause: "E2E tests use V1 AdCacheImpl because cache_size extra never set. AdCacheFactoryImpl reads demandAd.getExtras()['cache_size'] to determine version - null defaults to V1. Tests have no access to InterstitialAd instance to set extras."
  artifacts:
    - path: "app/src/androidTestServerless/java/org/bidon/demoapp/InterstitialTest.kt"
      issue: "Uses production InterstitialScreen composable without cache_size configuration"
    - path: "docs/testing/E2E_TEST_REPORT.md"
      issue: "Documents V1-only test coverage"
  missing:
    - "Create TestInterstitialScreen.kt composable with cacheVersion parameter"
    - "Add V2 test cases to InterstitialTest.kt using TestInterstitialScreen"
    - "Call addExtra('cache_size', 2) before loadAd() in test composable"
  debug_session: ".planning/debug/e2e-test-hardcoding.md"
