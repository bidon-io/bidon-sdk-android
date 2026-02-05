# Architecture

**Analysis Date:** 2026-02-05

## Pattern Overview

**Overall:** Layered Mediator Pattern with Clean Architecture

The Bidon SDK implements a **mediator-based ad network aggregation** architecture with clear separation of concerns across functional layers. The SDK acts as a transparent intermediary between game publishers and multiple ad networks, executing fair auctions to determine which network serves ads.

**Key Characteristics:**
- Clean separation between public SDK API, internal logic, and adapter integrations
- Auction-driven ad selection using price-based winner determination
- Adapter abstraction enabling pluggable ad network implementations
- Configuration-driven initialization and auction pipeline
- Built-in caching mechanism for pre-loaded ads
- State management for SDK lifecycle and ad objects

## Layers

**API Layer (Public Interface):**
- Purpose: Exposes SDK functionality to publishers and ad format implementations
- Location: `bidon/src/main/java/org/bidon/sdk/BidonSdk.kt`
- Contains: Public object singleton, configuration methods, adapter registration, ad format access
- Depends on: Internal Bidon implementation
- Used by: Publishers via Android applications and demo app at `app/src/main`

**Initialization & Configuration Layer:**
- Purpose: Handle SDK initialization, adapter registration, and configuration fetching from server
- Location: `bidon/src/main/java/org/bidon/sdk/config/`
- Contains: `BidonInitializer`, `BidonInitializerImpl`, configuration models, initialization use cases
- Key files: `BidonInitializer.kt`, `BidonInitializerImpl.kt`, `InitAndRegisterAdaptersUseCase.kt`, `ConfigResponse.kt`
- Depends on: Adapter interfaces, networking utilities, data binders
- Used by: BidonSdk public API, auction pipeline

**Adapter Interface Layer:**
- Purpose: Define contracts for ad network adapters (Network and Bidding adapters)
- Location: `bidon/src/main/java/org/bidon/sdk/adapter/`
- Contains: Core adapter interfaces (`Adapter.kt`, `AdProvider.kt`, `AdSource.kt`), adapter registry
- Key abstractions: `Adapter.Network`, `Adapter.Bidding`, `AdProvider` (Banner, Interstitial, Rewarded)
- Depends on: Auction models, initialization contracts
- Used by: Concrete adapter implementations in `adapter/` modules

**Auction Execution Layer:**
- Purpose: Execute auctions across registered adapters and determine winners
- Location: `bidon/src/main/java/org/bidon/sdk/auction/`
- Contains: Auction orchestration, token collection, ad unit requests, price-based resolution
- Key files: `Auction.kt`, `AuctionResolver.kt`, `ExecuteAuctionUseCase.kt`, `GetTokensUseCase.kt`, `RequestAdUnitUseCase.kt`
- Depends on: Adapters, ad cache, stats collectors
- Used by: Ad format implementations (Banner, Interstitial, Rewarded)

**Ad Format Layer:**
- Purpose: Implement specific ad formats (Banner, Interstitial, Rewarded) with auction integration
- Location: `bidon/src/main/java/org/bidon/sdk/ads/`
- Contains: Ad format interfaces, implementations, cache management, auto-refresh for banners
- Key files: `banner/BannerAd.kt`, `interstitial/InterstitialAd.kt`, `rewarded/RewardedAd.kt`, `cache/AdCache.kt`
- Depends on: Auction layer, adapters, data binders
- Used by: Publishers directly via BidonSdk.banner(), .interstitial(), .rewarded()

**Data Binding & Configuration Layer:**
- Purpose: Collect device, app, user, and session data for requests
- Location: `bidon/src/main/java/org/bidon/sdk/databinders/`
- Contains: Data providers, binders for app/device/placement/segment/session/token/regulation data
- Key files: `DataProvider.kt`, `AppBinder.kt`, `DeviceBinder.kt`, `PlacementDataSource.kt`, `SessionTracker.kt`
- Depends on: Android context, device utilities
- Used by: Configuration and auction request builders

**Statistics & Reporting Layer:**
- Purpose: Track ad impressions, wins/losses, and performance metrics
- Location: `bidon/src/main/java/org/bidon/sdk/stats/`
- Contains: Stats models, request body builders, impression tracking
- Key files: `ImpressionRequestBody.kt`, `StatsRequestUseCase.kt`, `RoundStat.kt`
- Depends on: Auction results, adapters
- Used by: Auction pipeline for post-auction reporting

**Regulation & Consent Layer:**
- Purpose: Handle GDPR, CCPA, and consent management
- Location: `bidon/src/main/java/org/bidon/sdk/regulation/`
- Contains: Consent interface, IAB consent implementation, regulation data collection
- Key files: `Consent.kt`, `ConsentImpl.kt`, `IabConsent.kt`, `Regulation.kt`
- Depends on: Android context, SharedPreferences
- Used by: Config requests, adapters supporting regulation

**Segmentation & Targeting Layer:**
- Purpose: Provide user segmentation and custom attributes
- Location: `bidon/src/main/java/org/bidon/sdk/segment/`
- Contains: User segment tracking, segment attributes, synchronization
- Key files: `Segmentation.kt`, `SegmentationImpl.kt`, `SegmentSynchronizer.kt`, `SegmentAttributes.kt`
- Depends on: Networking utilities
- Used by: Configuration requests, auction customization

**Utilities Layer:**
- Purpose: Provide cross-cutting utilities for networking, serialization, logging
- Location: `bidon/src/main/java/org/bidon/sdk/utils/`
- Contains: HTTP client, JSON parsing, key-value storage, logging framework
- Key files: `networking/HttpClient.kt`, `serializer/Serializer.kt`, `logs/logging/Logger.kt`, `keyvaluestorage/KeyValueStorage.kt`
- Depends on: Android framework, standard Kotlin/Java libraries
- Used by: All layers for network operations and data serialization

## Data Flow

**SDK Initialization:**

1. Publisher calls `BidonSdk.initialize(context, appKey)`
2. `BidonInitializer.initialize()` triggers:
   - Adapter registration (via `InitAndRegisterAdaptersUseCase`)
   - Configuration fetch from server (via `GetConfigRequestUseCase`)
   - Config models deserialization and caching
   - Adapter initialization with parsed config params
3. SDK state transitions to `INITIALIZED`
4. `InitializationCallback.onFinished()` invoked

**Auction Request Flow (when publisher calls loadAd()):**

1. Publisher calls `BannerAd.loadAd(activity, pricefloor)`
2. `AdCache.cache()` initiates auction:
   - Collects device/app/user data via `DataBinder`s
   - Builds auction request via `GetAuctionRequestUseCase`
   - Sends to Bidon server, receives `AuctionResponse` with `AdUnit`s list
3. `ExecuteAuctionUseCase` orchestrates multi-round auction:
   - **Token Collection Round:** For bidding adapters, calls `GetTokensUseCase` → `adapter.getToken()`
   - **Bidding Round:** Calls bidding adapters for token-based bids
   - **Network Round:** Calls network adapters requesting ads via `RequestAdUnitUseCase` → `adSource.requestAd(params)`
   - **Results Collection:** Gathers responses, determines winner via `AuctionResolver` (price-based)
4. Winner cached in `AdCache` for later display
5. Statistics collected via `StatsRequestUseCase` for server reporting
6. Success callback triggered: `BannerListener.onAdLoaded()`

**Ad Display Flow:**

1. Publisher calls `BannerAd.isReady()` to check if ad is cached
2. Publisher calls `BannerAd.showAd()` to render winning ad
3. `RenderInspector` monitors visibility for impression tracking
4. Win notification sent to winning adapter
5. Loss notifications sent to non-winning adapters

**State Management:**

```
SDK State: NOT_INITIALIZED → INITIALIZING → INITIALIZED → DESTROYED
Ad State: LOADING → CACHED → SHOWN → EXPIRED
Auction State: Initialized → InProgress → Finished
```

## Key Abstractions

**Adapter (Network and Bidding):**
- Purpose: Abstract ad network SDK capabilities
- Examples: `admob/AdmobAdapter.kt`, `applovin/ApplovinAdapter.kt`, `bidmachine/BidmachineAdapter.kt`
- Pattern: Each adapter implements `Adapter.Network` or `Adapter.Bidding`, plus `AdProvider` interfaces for supported formats
- Integration: Registered via `BidonSdk.registerAdapters()`, discovered by `AdaptersSource`

**AdSource (Banner, Interstitial, Rewarded):**
- Purpose: Define how adapters request and provide specific ad formats
- Examples: `adapter/AdSource.kt` defines interfaces; implemented in each adapter's `impl/` package
- Pattern: Adapters return `AdSource.Banner`, `.Interstitial`, or `.Rewarded` instances via `AdProvider` methods

**Ad Cache:**
- Purpose: Store and manage pre-loaded ads before display
- Files: `ads/cache/AdCache.kt`, `ads/cache/impl/` implementations
- Pattern: Provides `peek()` (non-destructive read), `pop()` (consume), `poll()` (await and consume)

**Use Cases:**
- Purpose: Encapsulate business logic for specific operations
- Examples: `ExecuteAuctionUseCase`, `GetTokensUseCase`, `RequestAdUnitUseCase`, `GetConfigRequestUseCase`
- Pattern: Each use case is an interface with `impl` implementation, typically invoked via `suspend operator fun invoke()`

**Data Binders:**
- Purpose: Collect and format device/app/user/session data
- Examples: `AppBinder.kt`, `DeviceBinder.kt`, `PlacementDataSource.kt`, `SessionTracker.kt`
- Pattern: Implement `DataBinder` or `DataSource` interfaces, composed into `DataProvider`

## Entry Points

**BidonSdk Object:**
- Location: `bidon/src/main/java/org/bidon/sdk/BidonSdk.kt`
- Triggers: Public API entry point for all publisher interactions
- Responsibilities: Configuration, adapter registration, ad format access, SDK state queries

**BidonInitializer:**
- Location: `bidon/src/main/java/org/bidon/sdk/config/BidonInitializer.kt`
- Triggers: Called during `BidonSdk.initialize(context, appKey)`
- Responsibilities: Adapter discovery/registration, configuration server communication, SDK initialization

**Ad Format Entry Points:**
- `BannerAd.loadAd()` - `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerAd.kt`
- `InterstitialAd.loadAd()` - `bidon/src/main/java/org/bidon/sdk/ads/interstitial/InterstitialAd.kt`
- `RewardedAd.loadAd()` - `bidon/src/main/java/org/bidon/sdk/ads/rewarded/RewardedAd.kt`
- Triggers: Called when publisher wants to fetch new ad
- Responsibilities: Orchestrate auction and cache winning ad

**Adapter Implementations:**
- Location: `adapter/{name}/src/main/java/org/bidon/{name}/{Name}Adapter.kt`
- Triggers: Instantiated during initialization, called during auction execution
- Responsibilities: Wrap network adapter SDK, provide tokens/ads for specific formats

## Error Handling

**Strategy:** Graceful degradation with fallback to alternative adapters

**Patterns:**

- **Timeout-Based:** Each ad unit has configurable timeout in seconds. Slow adapters excluded from results
- **Try-Catch with Logging:** Adapter failures logged but don't stop auction; remaining adapters continue
- **Callback-Based:** Errors reported via listeners (`BannerListener.onAdLoadFailed(error)`)
- **BidonError Type:** Errors typed as `BidonError` with categories: `NetworkError`, `AdapterNotInitialized`, `NoFill`, `InvalidRequest`

Location: `bidon/src/main/java/org/bidon/sdk/config/BidonError.kt`

## Cross-Cutting Concerns

**Logging:**
- Framework: Custom `Logger` interface at `logs/logging/Logger.kt`
- Implementation: `LoggerImpl.kt` delegates to Android Log
- Usage: Accessible via `BidonSdk.setLoggerLevel()`, logged with tag prefix "Bidon"

**Validation:**
- Pattern: Use case methods validate request parameters before processing
- Example: `GetAuctionRequestUseCase` validates placement ID and demand IDs

**Authentication:**
- Pattern: App key passed to `BidonSdk.initialize(context, appKey)` included in all server requests
- Location: `ConfigRequestBody`, `StatsRequestBody` include app key

**Build Variants:**
- Pattern: SDK supports `production` and `serverless` variants via source sets
- Location: `bidon/src/production/`, `bidon/src/serverless/`
- Purpose: Different DI configurations for production vs. test scenarios

---

*Architecture analysis: 2026-02-05*
