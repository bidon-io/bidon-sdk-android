# Codebase Concerns

**Analysis Date:** 2026-02-05

## Tech Debt

### Unimplemented Ad Cache Strategy Classes
- **Issue:** Multiple ad cache implementation variants (`AdCacheAlexImpl`, `AdCacheVladimirImpl`, `AdCacheDenisImpl`, `AdCacheAndreiImpl`) are fully stubbed with `TODO("Not yet implemented")` across all methods
- **Files:**
  - `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheAlexImpl.kt` (46 lines, 6 TODOs)
  - `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheVladimirImpl.kt` (46 lines, 6 TODOs)
  - `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheDenisImpl.kt` (46 lines, 6 TODOs)
  - `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/AdCacheAndreiImpl.kt` (46 lines, 6 TODOs)
- **Impact:** Multiple ad cache strategies cannot be used; factory pattern in `AdCacheFactoryImpl.kt` cannot properly route to versioned implementations. Ad caching behavior is unreliable if these implementations are selected.
- **Fix approach:** Implement each cache strategy class with proper cache lifecycle management (cache, peek, pop, poll, clear, withSettings). Consider consolidating similar implementations to reduce code duplication.

### Placement Data Source Not Implemented
- **Issue:** `PlacementDataSourceImpl` has all 5 methods stubbed with `TODO("Not yet implemented")`
- **Files:** `bidon/src/main/java/org/bidon/sdk/databinders/placement/PlacementDataSourceImpl.kt`
- **Impact:** Placement configuration (name, reward amount, reward type, capping settings) cannot be retrieved. This breaks rewarded ad personalization and capping logic.
- **Fix approach:** Implement methods to retrieve placement metadata from SDK configuration or ad request response. Coordinate with `PlacementBinder` to ensure data flow is complete.

### Incomplete Configuration Changes Logic
- **Issue:** Activity configuration changes (e.g., device rotation) are detected but not handled
- **Files:** `bidon/src/main/java/org/bidon/sdk/ads/banner/render/LifecycleObserver.kt:21` - `onConfigurationChanged` callback is empty with TODO comment
- **Impact:** Banner ads may display incorrectly during device rotation or configuration changes. Layout calculations might not update properly.
- **Fix approach:** Implement layout recalculation and re-render logic in response to configuration changes. Test across multiple device rotations and screen sizes.

### Unclear Model Definition
- **Issue:** `Capping` model has a TODO comment indicating the model definition needs clarification
- **Files:** `bidon/src/main/java/org/bidon/sdk/config/models/Capping.kt:6`
- **Impact:** Unclear semantics around capping strategy implementation. Potential misalignment between SDK and backend expectations for capping behavior.
- **Fix approach:** Document capping model semantics, validate against backend schema, and add unit tests for capping calculations.

### Incomplete JSON Parser Implementation
- **Issue:** `BidonParser` object is marked "in progress" with incomplete type handling, debug print statements, and commented-out code
- **Files:** `bidon/src/main/java/org/bidon/sdk/utils/serializer/BidonParser.kt`
- **Impact:** Custom JSON serialization for complex types is broken. Debug output (`println`) pollutes logs. Parser cannot handle nested objects or complex collections properly.
- **Fix approach:** Complete the type resolution logic, remove debug prints, handle nested Serializable types, and add comprehensive test coverage for all JSON scenarios.

## Known Bugs

### Missing Overlap Detection Implementation
- **Symptoms:** Ad visibility tracking may incorrectly report visibility if configured to track overlaps
- **Files:** `bidon/src/main/java/org/bidon/sdk/utils/visibilitytracker/TrackingHolderExt.kt:65` - TODO comment exists but method returns early without processing
- **Trigger:** When `ignoreOverlap` is false and `hasOverlap()` returns true
- **Workaround:** Currently the overlap check returns false (allowed). Set `ignoreOverlap = true` to bypass overlap detection entirely.

### Deprecated Methods with Unclear Semantics
- **Symptoms:** Auction result collection behavior changes unexpectedly after server bidding completion
- **Files:**
  - `bidon/src/main/java/org/bidon/sdk/auction/impl/ResultsCollectorImpl.kt:27-80` - `serverBiddingStarted()` and `serverBiddingFinished()` marked `@Deprecated("")`
  - `bidon/src/main/java/org/bidon/sdk/auction/ResultsCollector.kt` - Interface methods also deprecated
- **Trigger:** When deprecated server bidding lifecycle methods are called in sequence
- **Impact:** No bid processing may be lost if adUnits list from server is not handled correctly
- **Workaround:** Use new auction flow without server bidding deprecation methods. Comment at line 48 suggests NO_BIDS adUnits should not be processed for stats.

## Performance Bottlenecks

### Large Implementation Files with Complex State Management
- **Problem:** Several core files exceed 300 lines with dense logic and complex state flows
- **Files:**
  - `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/AuctionStatImpl.kt` (298 lines)
  - `bidon/src/main/java/org/bidon/sdk/auction/usecases/impl/ExecuteAuctionUseCaseImpl.kt` (297 lines)
  - `bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt` (318 lines)
  - `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerView.kt` (397 lines)
  - `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerManager.kt` (318 lines)
- **Cause:** Multiple responsibilities bundled (state management, networking, logging, UI updates). Complex winner selection and stats tracking logic inline.
- **Improvement path:** Extract auction winner resolution into dedicated service. Separate stats calculation from auction execution. Split banner lifecycle management from view rendering.

### Repeated Visibility Calculations in Banner Rendering
- **Problem:** Multiple visibility checks and rect calculations performed on each tracking event
- **Files:** `bidon/src/main/java/org/bidon/sdk/utils/visibilitytracker/TrackingHolderExt.kt` - `isOnTop()` performs 10+ rect operations per call
- **Cause:** No caching of view hierarchy state or visibility results
- **Improvement path:** Cache visibility state with invalidation on configuration/layout changes. Batch visibility checks with debouncing.

### Coroutine Scope Created Per Ad Instance
- **Problem:** Each banner view creates its own `CoroutineScope(SdkDispatchers.Main)` lazily
- **Files:** `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerView.kt:71` and similar patterns across ad types
- **Cause:** Scope is tied to ad lifecycle for cleanup, but creates overhead if many ads are instantiated
- **Improvement path:** Use application-level scope with proper job cancellation instead of per-instance scopes.

## Fragile Areas

### Auction State Machine with Race Conditions
- **Files:** `bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt:50-68`
- **Why fragile:** State transitions use `compareAndSet()` but multiple fields (`_auctionDataResponse`, `_demandAd`, `job`) are updated without atomic guarantees. Job activity checked separately from state, creating window for re-entrant auction starts.
- **Safe modification:** Ensure all mutable fields are updated atomically with state, or use synchronized state update pattern. Add integration tests for concurrent auction requests.
- **Test coverage:** No test for rapid successive auction requests on same instance.

### Results Collection with Mutable StateFlow
- **Files:** `bidon/src/main/java/org/bidon/sdk/auction/impl/ResultsCollectorImpl.kt:24-25`
- **Why fragile:** `MutableStateFlow` for auction results and round results can be corrupted if `update` lambda throws exception or is interrupted mid-update. Winner notification (lines 186-197) relies on external ad source state that may change.
- **Safe modification:** Wrap StateFlow updates in try-catch with rollback. Snapshot ad source state when marking loss before notifying.
- **Test coverage:** No test for exception handling during result collection or notification failures.

### Banner View Lifecycle with Async Operations
- **Files:** `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerView.kt:74-84`
- **Why fragile:** Winner assignment can occur after view is destroyed. `wasNotified` AtomicBoolean may not prevent double notification if `winnerSubscriberJob` completes after view detach.
- **Safe modification:** Check view attachment state before notification. Cancel winner subscriber job in destroy/detach lifecycle. Use WeakReference for view context in listeners.
- **Test coverage:** No test for notifications after destroy or rapid attach/detach cycles.

### Server Bidding Result Processing
- **Files:** `bidon/src/main/java/org/bidon/sdk/auction/impl/ResultsCollectorImpl.kt:40-80` (deprecated methods)
- **Why fragile:** No validation that adUnits list matches expected format. Timestamp fields can be null but used without null check (line 216). State transitions assume specific sequence.
- **Safe modification:** Validate adUnits list structure before processing. Use default timestamps if null. Add state transition guards.
- **Test coverage:** No test for invalid or empty adUnits lists from server.

## Scaling Limits

### Auction Results Storage Unbounded
- **Current capacity:** Limited only by available heap memory
- **Limit:** If many auctions run in succession, `auctionResults` StateFlow keeps growing (though capped by `MaxAuctionResultsAmount`). Memory not reclaimed until next successful round.
- **Scaling path:** Implement result TTL and automatic cleanup. Consider LRU eviction for result history.

### Visibility Tracking View Hierarchy Traversal
- **Current capacity:** Traverses entire parent hierarchy + all sibling views on each visibility check
- **Limit:** Complex nested layouts (10+ levels) can cause slow visibility checks on frequent banner updates
- **Scaling path:** Cache hierarchy snapshot, invalidate only on layout changes. Use ViewTreeObserver for change events.

## Dependencies at Risk

### Deprecated Adapter Method Blocking CI
- **Risk:** `ci-adapter-quality.yml` fails adapter builds if deprecated warnings are generated
- **Impact:** Cannot use deprecated APIs in adapters; blocks migration to new patterns. Deprecated code in core SDK (`serverBiddingStarted`, `serverBiddingFinished`) must be maintained but cannot be used safely in production adapters.
- **Migration plan:** Implement new server bidding flow without deprecated methods. Run deprecation fixes using `claude-fix-deprecated.yml` workflow (requires ANTHROPIC_API_KEY).

## Missing Critical Features

### No Banner Rotation Detection
- **Problem:** Configuration changes are detected but not acted upon
- **Blocks:** Adaptive banner layouts that resize based on orientation. Banner refresh on rotation.

### Placement-Based Features Incomplete
- **Problem:** Placement data source is stubbed
- **Blocks:** Placement name display, reward customization per placement, placement-specific capping.

### Incomplete JSON Schema Parser
- **Problem:** BidonParser cannot handle nested objects
- **Blocks:** Complex response parsing, custom data type support.

## Test Coverage Gaps

### Cache Implementation Strategy Selection
- **What's not tested:** Fallback behavior when cache strategy is not implemented. Selection logic between Alex/Vladimir/Denis/Andrei implementations.
- **Files:** `bidon/src/main/java/org/bidon/sdk/ads/cache/impl/`
- **Risk:** Production crash if cache factory routes to unimplemented strategy. No way to verify which strategy is active.
- **Priority:** High - cache is core ad mediation feature

### Visibility Tracking Edge Cases
- **What's not tested:** Transparent views with alpha = 0.0, view detach during visibility check, nested FrameLayout edge cases, maxCountOverlappedViews boundary conditions
- **Files:** `bidon/src/main/java/org/bidon/sdk/utils/visibilitytracker/TrackingHolderExt.kt`
- **Risk:** Incorrect visibility tracking leads to missed impressions or phantom impressions
- **Priority:** High - affects monetization accuracy

### Auction Cancellation Race Conditions
- **What's not tested:** Rapid fire auction.cancel() before auction starts, concurrent requests on same `DemandAd` instance, cancellation during stats transmission
- **Files:** `bidon/src/main/java/org/bidon/sdk/auction/impl/AuctionImpl.kt`
- **Risk:** Memory leaks, duplicate impressions, stats loss
- **Priority:** High - impacts user experience and monetization

### Coroutine Scope Lifecycle
- **What's not tested:** Scope cleanup after view destruction, job cancellation on rapid destroy/recreate, scope disposal in low-memory conditions
- **Files:** `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerView.kt` and similar
- **Risk:** Coroutine leaks accumulate over time, memory exhaustion in long-running sessions
- **Priority:** Medium - affects long-term stability

### Winner Notification Failure Handling
- **What's not tested:** Ad source destruction during loss notification, exception in WinLossNotifiable.notifyLoss(), notification timeout
- **Files:** `bidon/src/main/java/org/bidon/sdk/auction/impl/ResultsCollectorImpl.kt:186-197`
- **Risk:** Silent notification failures, inconsistent bidder state
- **Priority:** Medium - affects multi-round auction fairness

---

*Concerns audit: 2026-02-05*
