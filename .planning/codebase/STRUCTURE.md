# Codebase Structure

**Analysis Date:** 2026-02-05

## Directory Layout

```
bidon-sdk-android/
├── bidon/                           # Core SDK module (org.bidon:bidon-sdk)
│   ├── src/main/java/org/bidon/sdk/
│   │   ├── BidonSdk.kt             # Public API entry point
│   │   ├── adapter/                # Adapter interfaces and registry
│   │   ├── ads/                    # Ad format implementations
│   │   ├── auction/                # Auction orchestration
│   │   ├── config/                 # Initialization and configuration
│   │   ├── databinders/            # Device/app/user data collection
│   │   ├── logs/                   # Logging framework
│   │   ├── regulation/             # GDPR/consent management
│   │   ├── segment/                # User segmentation
│   │   ├── stats/                  # Impression tracking
│   │   └── utils/                  # Networking, serialization, storage
│   ├── src/test/java/              # Unit tests
│   ├── src/production/java/        # Production variant DI
│   ├── src/serverless/java/        # Serverless variant DI
│   └── build.gradle.kts
├── adapter/                         # Ad network adapters
│   ├── admob/                      # AdMob adapter
│   ├── applovin/                   # AppLovin adapter
│   ├── amazon/                     # Amazon adapter
│   ├── bidmachine/                 # BidMachine adapter
│   ├── bigoads/                    # BigAds adapter
│   ├── chartboost/                 # Chartboost adapter
│   ├── meta/                       # Meta adapter
│   ├── unityads/                   # Unity Ads adapter
│   ├── yandex/                     # Yandex adapter
│   └── {other adapters}/           # Additional network adapters
├── thirdPartyMediationAdapters/    # Third-party mediation adapters
│   ├── applovin_max/               # AppLovin MAX adapter
│   └── level_play/                 # Level Play adapter
├── app/                             # Demo application
│   ├── src/main/                   # Production build variant
│   └── src/androidTestServerless/  # Serverless variant tests
├── build-logic/                    # Gradle convention plugins
│   └── convention-plugins/         # Reusable build plugins
├── .github/workflows/              # CI/CD pipelines
├── build.gradle.kts                # Root build configuration
└── settings.gradle.kts             # Gradle module configuration
```

## Directory Purposes

**bidon/ - Core SDK Module:**
- Purpose: Main SDK implementation providing mediation, auction, and ad format support
- Contains: Public API, internal business logic, interfaces, models
- Key files: `BidonSdk.kt` (entry point), `config/BidonInitializer.kt`, `auction/Auction.kt`

**bidon/src/main/java/org/bidon/sdk/adapter/ - Adapter Interfaces:**
- Purpose: Contracts for ad network adapters and adapter registry
- Contains: `Adapter.kt`, `AdProvider.kt`, `AdSource.kt`, `AdaptersSource.kt`, adapter models
- Key abstractions: `Adapter.Network`, `Adapter.Bidding`, `AdProvider` (format interfaces)

**bidon/src/main/java/org/bidon/sdk/ads/ - Ad Formats:**
- Purpose: Implement banner, interstitial, and rewarded ad formats
- Subdirectories:
  - `banner/` - Banner ad implementation with auto-refresh and lifecycle management
  - `interstitial/` - Full-screen interstitial ads
  - `rewarded/` - Rewarded video ads
  - `cache/` - Ad caching mechanism (interface in `AdCache.kt`, implementations in `impl/`)
  - `ext/` - Extension functions for ad utilities

**bidon/src/main/java/org/bidon/sdk/auction/ - Auction Pipeline:**
- Purpose: Orchestrate ad auctions across adapters
- Contains: `Auction.kt` interface, `AuctionResolver.kt` (price-based winner selection)
- Subdirectories:
  - `usecases/` - Business logic: `ExecuteAuctionUseCase`, `GetTokensUseCase`, `RequestAdUnitUseCase`
  - `usecases/impl/` - Concrete implementations
  - `models/` - Data models: `AuctionResponse.kt`, `AuctionResult.kt`, `AdUnit.kt`
  - `ext/` - Auction utility extensions

**bidon/src/main/java/org/bidon/sdk/config/ - Initialization & Configuration:**
- Purpose: SDK setup, adapter registration, server configuration
- Contains: `BidonInitializer.kt` interface, `BidonInitializerImpl.kt` implementation
- Subdirectories:
  - `usecases/` - Business logic: `InitAndRegisterAdaptersUseCase`, `GetConfigRequestUseCase`
  - `impl/` - Implementations
  - `models/` - Configuration models: `ConfigResponse.kt`, `ConfigRequestBody.kt`, data classes

**bidon/src/main/java/org/bidon/sdk/databinders/ - Data Collection:**
- Purpose: Gather device, app, user, placement, and session data for requests
- Contains: `DataProvider.kt` (coordinator), `DataBinder.kt` interface
- Subdirectories:
  - `app/` - Application info: `AppBinder.kt`, `AppDataSource.kt`
  - `device/` - Device info: `DeviceBinder.kt`, `DeviceDataSource.kt`
  - `placement/` - Ad placement data: `PlacementDataSource.kt`
  - `session/` - Session tracking: `SessionTracker.kt`, `SessionDataSource.kt`
  - `token/` - Token generation: `TokenDataSource.kt`
  - `user/` - User/advertising data: `AdvertisingData.kt`, `TrackingAuthorizationStatus.kt`
  - `extras/` - Custom key-value extras: `ExtrasImpl.kt`
  - `segment/` - Segment data: `SegmentRequestBody.kt`
  - `reg/` - Regulation data: `RegulationDataSource.kt`
  - `test/` - Test mode configuration: `TestModeBinder.kt`
  - `geo/` - Geolocation data: `GeoBinder.kt`

**bidon/src/main/java/org/bidon/sdk/regulation/ - Consent & Compliance:**
- Purpose: Handle GDPR/CCPA consent and regulation compliance
- Contains: `Consent.kt` interface, `Regulation.kt` interface
- Key files: `ConsentImpl.kt`, `IabConsentImpl.kt`, `RegulationImpl.kt`, `Iab.kt`, `IabConsent.kt`

**bidon/src/main/java/org/bidon/sdk/segment/ - User Segmentation:**
- Purpose: Track and manage user segments for targeting
- Contains: `Segmentation.kt` interface, `SegmentationImpl.kt`
- Key files: `SegmentSynchronizer.kt`, `SegmentAttributes.kt`, segment request models

**bidon/src/main/java/org/bidon/sdk/stats/ - Statistics & Reporting:**
- Purpose: Track ad impressions, wins/losses for server-side analytics
- Contains: Statistics request models and use cases
- Key files: `ImpressionRequestBody.kt`, `StatsRequestUseCase.kt`, `StatsRequestBody.kt`, `RoundStat.kt`, `Loss.kt`

**bidon/src/main/java/org/bidon/sdk/logs/ - Logging:**
- Purpose: Centralized logging framework
- Contains: `Logger.kt` interface, `LoggerImpl.kt` Android implementation

**bidon/src/main/java/org/bidon/sdk/utils/ - Utilities:**
- Purpose: Cross-cutting utilities for HTTP, serialization, storage
- Subdirectories:
  - `networking/` - HTTP client and network utilities: `HttpClient.kt`, `JsonHttpRequest.kt`
  - `networking/impl/` - Concrete implementations: `BidonEndpointsImpl.kt`, `RawRequest.kt`, `RawResponse.kt`
  - `networking/encoders/` - Request encoding: `GZIPRequestDataEncoder.kt`
  - `networking/requests/` - Request building: `CreateRequestBodyUseCase.kt`
  - `serializer/` - JSON serialization: `Serializer.kt`
  - `json/` - JSON utilities: `JsonParsers.kt`
  - `keyvaluestorage/` - Persistent storage: `KeyValueStorage.kt`, `KeyValueStorageImpl.kt`
  - `ext/` - Extension functions
  - `visibilitytracker/` - Ad visibility detection: `VisibilityParams.kt`

**bidon/src/test/java/ - Unit Tests:**
- Purpose: Test core SDK functionality
- Structure mirrors main source structure with `Test` suffix
- Key tests: `ExecuteAuctionUseCaseImplTest.kt`, `BidonSerializerTest.kt`, adapter initialization tests

**bidon/src/production/java/ - Production Variant:**
- Purpose: Production-specific dependency injection and configuration
- Contains: Variant-specific implementations of DI setup

**bidon/src/serverless/java/ - Serverless Variant:**
- Purpose: Test/serverless variant for demo app without full backend
- Contains: Alternative implementations for testing without production server

**adapter/{name}/ - Ad Network Adapter Module:**
- Purpose: Specific adapter implementation for one ad network
- Structure (example: admob/):
  ```
  adapter/admob/
  ├── src/main/java/org/bidon/admob/
  │   ├── AdmobAdapter.kt           # Main adapter class
  │   ├── AdmobInitParameters.kt    # Adapter config params
  │   ├── ext/                      # Extensions and utilities
  │   │   ├── AdValueExt.kt
  │   │   ├── Ext.kt
  │   │   └── RegulationExt.kt
  │   └── impl/                     # Format implementations
  │       ├── AdmobBannerImpl.kt
  │       ├── AdmobInterstitialImpl.kt
  │       ├── AdmobRewardedImpl.kt
  │       └── usecases/             # Adapter-specific logic
  ├── src/test/java/               # Adapter tests
  └── build.gradle.kts             # Adapter build config
  ```

**app/ - Demo Application:**
- Purpose: Reference app demonstrating SDK usage
- Contains: Activities, fragments, ad format examples
- Variants: `production` (production backend), `serverless` (local testing)

**build-logic/ - Gradle Convention Plugins:**
- Purpose: Reusable Gradle build configuration
- Contains: Plugins for SDK modules, adapters, publication setup
- Key plugins: `core`, `adapter`, `common`, `publishAdapter`

**.github/workflows/ - CI/CD Pipelines:**
- Purpose: Automated testing and publishing
- Key workflows:
  - `ci-pull-request.yml` - PR validation
  - `ci-adapter-quality.yml` - Adapter testing on changes
  - `sdk-size-check.yml` - APK size regression detection
  - `automation-publish-adapters.yml` - Auto-publish on develop
  - `release-adapter.yml` - Manual adapter release
  - `release-sdk.yml` - Manual SDK release

## Key File Locations

**Entry Points:**
- `bidon/src/main/java/org/bidon/sdk/BidonSdk.kt` - Public SDK object
- `bidon/src/main/java/org/bidon/sdk/config/impl/BidonInitializer.kt` - Initialization logic
- `bidon/src/main/java/org/bidon/sdk/ads/banner/BannerAd.kt` - Banner ad interface
- `bidon/src/main/java/org/bidon/sdk/ads/interstitial/InterstitialAd.kt` - Interstitial interface
- `bidon/src/main/java/org/bidon/sdk/ads/rewarded/RewardedAd.kt` - Rewarded interface

**Core Interfaces:**
- `bidon/src/main/java/org/bidon/sdk/adapter/Adapter.kt` - Adapter contract
- `bidon/src/main/java/org/bidon/sdk/adapter/AdProvider.kt` - Format provider contract
- `bidon/src/main/java/org/bidon/sdk/adapter/AdSource.kt` - Ad source contract
- `bidon/src/main/java/org/bidon/sdk/auction/Auction.kt` - Auction orchestration
- `bidon/src/main/java/org/bidon/sdk/ads/cache/AdCache.kt` - Cache interface

**Configuration & Models:**
- `bidon/src/main/java/org/bidon/sdk/config/models/ConfigResponse.kt` - Server config model
- `bidon/src/main/java/org/bidon/sdk/config/models/ConfigRequestBody.kt` - Config request
- `bidon/src/main/java/org/bidon/sdk/auction/models/AuctionResponse.kt` - Auction response model
- `bidon/src/main/java/org/bidon/sdk/auction/models/AuctionResult.kt` - Auction winner result
- `bidon/src/main/java/org/bidon/sdk/auction/models/AdUnit.kt` - Ad unit config

**Use Cases:**
- `bidon/src/main/java/org/bidon/sdk/auction/usecases/ExecuteAuctionUseCase.kt` - Auction execution
- `bidon/src/main/java/org/bidon/sdk/auction/usecases/GetTokensUseCase.kt` - Bidding token collection
- `bidon/src/main/java/org/bidon/sdk/auction/usecases/RequestAdUnitUseCase.kt` - Ad unit requests
- `bidon/src/main/java/org/bidon/sdk/config/usecases/InitAndRegisterAdaptersUseCase.kt` - Adapter init

**Testing:**
- `bidon/src/test/java/org/bidon/sdk/auction/impl/ExecuteAuctionUseCaseImplTest.kt` - Auction tests
- `bidon/src/test/java/org/bidon/sdk/config/models/serializer/BidonSerializerTest.kt` - Serialization tests

## Naming Conventions

**Files:**
- **Adapter implementations:** `{NetworkName}Adapter.kt` (e.g., `AdmobAdapter.kt`)
- **Implementation files:** `{ClassName}Impl.kt` (e.g., `BidonInitializerImpl.kt`, `LoggerImpl.kt`)
- **Interface files:** `{ClassName}.kt` (e.g., `Adapter.kt`, `Auction.kt`)
- **Test files:** `{ClassName}Test.kt` (e.g., `ExecuteAuctionUseCaseImplTest.kt`)
- **Extension files:** `{Name}Ext.kt` (e.g., `AdapterExt.kt`, `RegulationExt.kt`)
- **Models/Data:** `{EntityName}.kt` (e.g., `ConfigResponse.kt`, `AuctionResult.kt`)
- **Use cases:** `{Action}UseCase.kt` (e.g., `ExecuteAuctionUseCase.kt`)
- **Request bodies:** `{Name}RequestBody.kt` (e.g., `ConfigRequestBody.kt`)

**Directories:**
- **Implementation dirs:** `impl/` for concrete implementations of interfaces
- **Extension dirs:** `ext/` for extension functions and utilities
- **Model dirs:** `models/` for data classes and models
- **Use case dirs:** `usecases/` for use case definitions and implementations
- **Test dirs:** `test/` for unit tests (Gradle source set)

**Packages:**
- Core: `org.bidon.sdk.*` (all core SDK packages)
- Adapters: `org.bidon.{adnetwork}` (e.g., `org.bidon.admob`, `org.bidon.applovin`)
- Third-party adapters: `org.bidon.mediationadapter.*`

**Classes:**
- **Interfaces:** Standard names (e.g., `Adapter`, `Auction`, `Logger`)
- **Implementations:** `{InterfaceName}Impl` (e.g., `AdapterImpl`, `BidonInitializerImpl`)
- **Adapters:** `{NetworkName}Adapter` (e.g., `AdmobAdapter`)
- **Use Cases:** `{Verb}{Noun}UseCase` (e.g., `ExecuteAuctionUseCase`, `GetTokensUseCase`)

**Variables & Parameters:**
- **Functions:** camelCase, preferring descriptive names (e.g., `loadAd()`, `executeAuction()`)
- **Variables:** camelCase (e.g., `auctionId`, `demandId`, `adapterInfo`)
- **Constants:** UPPER_SNAKE_CASE (e.g., `DEFAULT_PRICE_FLOOR`, `AUCTION_TIMEOUT_MS`)
- **Private properties:** prefix underscore (Kotlin convention omits prefix but use private keyword)

## Where to Add New Code

**New Feature (e.g., new ad format like Native):**
- Primary code: `bidon/src/main/java/org/bidon/sdk/ads/native/`
- Create: `NativeAd.kt` interface, `impl/NativeAdImpl.kt` implementation
- Create: Cache implementation if needed in `ads/cache/impl/`
- Tests: `bidon/src/test/java/org/bidon/sdk/ads/native/`
- Update: `BidonSdk.kt` to expose new format via public property/method

**New Adapter (e.g., IronSource adapter):**
- Create module: `adapter/ironsource/` directory at project root
- Structure: Mirror `adapter/admob/` structure
- Main class: `adapter/ironsource/src/main/java/org/bidon/ironsource/IronSourceAdapter.kt`
- Implementations: `IronSourceBannerImpl.kt`, `IronSourceInterstitialImpl.kt`, `IronSourceRewardedImpl.kt`
- Tests: `adapter/ironsource/src/test/java/org/bidon/ironsource/impl/`
- Build: `adapter/ironsource/build.gradle.kts` (copy from admob, update dependencies)
- Registration: Include in `DefaultAdapters.kt` if providing default list

**New Data Binder (e.g., collect custom app data):**
- Interface: `bidon/src/main/java/org/bidon/sdk/databinders/custom/CustomDataSource.kt`
- Implementation: `CustomDataSourceImpl.kt`
- Integration: Register in `DataProvider.kt`
- Tests: `bidon/src/test/java/org/bidon/sdk/databinders/custom/`

**New Use Case:**
- Interface: `bidon/src/main/java/org/bidon/sdk/{layer}/usecases/{Name}UseCase.kt`
- Implementation: `usecases/impl/{Name}UseCaseImpl.kt`
- Models: Place input/output models in `usecases/models/` subdirectory
- Tests: `bidon/src/test/java/org/bidon/sdk/{layer}/usecases/impl/{Name}UseCaseImplTest.kt`

**Utilities/Helpers:**
- Shared helpers: `bidon/src/main/java/org/bidon/sdk/utils/` subdirectories
- Adapter-specific: `adapter/{name}/src/main/java/org/bidon/{name}/ext/` for extensions

**Extension Functions:**
- General extensions: `bidon/src/main/java/org/bidon/sdk/utils/ext/{Name}Ext.kt`
- Format-specific: `bidon/src/main/java/org/bidon/sdk/ads/{format}/ext/{Name}Ext.kt`
- Adapter-specific: `adapter/{name}/src/main/java/org/bidon/{name}/ext/{Name}Ext.kt`

## Special Directories

**bidon/src/main/res/values/ - Resources:**
- Purpose: String resources and configuration values
- Generated: No (manually maintained)
- Committed: Yes

**bidon/src/production/ and bidon/src/serverless/ - Build Variants:**
- Purpose: Variant-specific implementations for DI, networking
- Generated: No (source-level variants)
- Committed: Yes
- Usage: Selected via Gradle build variant

**build/ - Build Artifacts:**
- Purpose: Compiled classes, resources, generated files
- Generated: Yes (by Gradle)
- Committed: No (.gitignore)

**.gradle/ - Gradle Cache:**
- Purpose: Downloaded Gradle wrapper, cached artifacts
- Generated: Yes
- Committed: No (.gitignore)

**.idea/ - IDE Configuration:**
- Purpose: IntelliJ/Android Studio project settings
- Generated: Yes (by IDE)
- Committed: Partially (some shared configs)

---

*Structure analysis: 2026-02-05*
