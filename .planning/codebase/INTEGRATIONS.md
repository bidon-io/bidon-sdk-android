# External Integrations

**Analysis Date:** 2026-02-05

## APIs & External Services

**Bidon Backend:**
- Service: Bidon Auction Server
- What it's used for: Ad configuration, auction requests, statistics reporting
- Base URL: `https://api.bidon.org` (configurable via `BidonInitializer.setBaseUrl()`)
- API Version: v2
- Endpoints:
  - `/v2/config` - Fetch SDK configuration with ad units and price floors
  - `/v2/auction` - Execute ad auctions and request ad units
  - `/v2/stats` - Send auction statistics and performance metrics
- Auth: Basic authentication via header (optional, configured in `NetworkSettings.basicAuthHeader`)
- Environment: Configured via `NetworkSettings` in `bidon/src/main/java/org/bidon/sdk/utils/networking/NetworkSettings.kt`

**Ad Network APIs (via Adapters):**
Bidon connects to multiple ad networks through modular adapters. Each adapter wraps the native SDK:

- **Google AdMob** - `adapter/admob/`
  - SDK: Google Play Services Ads 24.9.0
  - Network calls: Handled by Google Play Services

- **Meta (Facebook) Audience Network** - `adapter/meta/`
  - SDK: Audience Network SDK 6.21.0
  - Network calls: Handled by Meta SDK

- **AppLovin MAX** - `adapter/applovin/`
  - SDK: AppLovin SDK 13.5.1
  - Network calls: Handled by AppLovin SDK

- **Unity Ads** - `adapter/unityads/`
  - SDK: Unity Ads 4.16.6
  - Network calls: Handled by Unity SDK

- **IronSource (by Unity)** - `adapter/ironsource/`
  - SDK: Mediation SDK 9.3.0
  - Network calls: Handled by IronSource SDK

- **Yandex Mobile Ads** - `adapter/yandex/`
  - SDK: Yandex Mobile Ads 7.18.1
  - Network calls: Handled by Yandex SDK

- **Vungle** - `adapter/vungle/`
  - SDK: Vungle Ads 7.6.3
  - Network calls: Handled by Vungle SDK

- **Additional Networks:** Amazon, BidMachine, BigOAds, Chartboost, DTExchange, Google Ad Manager (GAM), InMobi, Mintegral, MobileFuse, Moloco, TaurusX, StartIO, VkAds, Fyber, AppsFlyer
  - Each with their own SDK wrapper in `adapter/*/` directories

## Data Storage

**Databases:**
- None - No Room, SQLite, or embedded database
- Uses SharedPreferences for lightweight persistence

**SharedPreferences:**
- `bidon_preferences` - SDK internal preferences for caching and state
  - Location: `bidon/src/main/java/org/bidon/sdk/utils/keyvaluestorage/KeyValueStorageImpl.kt`
  - Accessed via `KeyValueStorage` interface
- `app_test` - Demo app preferences for settings persistence

**File Storage:**
- Local filesystem only - No remote file storage integration
- APK size tracking via CI/CD (no runtime integration)

**Caching:**
- Ad cache system implemented in core SDK
  - Architecture: Factory pattern for versioned ad cache implementations
  - Location: `bidon/src/main/java/org/bidon/sdk/ads/` (ad format specific)
- Network response caching at HTTP level (minimal)

## Authentication & Identity

**Auth Provider:**
- Custom/Self-managed
- Implementation: Basic HTTP Authentication
  - Optional header: `Authorization: Basic {base64(username:password)}`
  - Configured via `NetworkSettings.basicAuthHeader`
  - Set programmatically in app during initialization

**Device Identifiers:**
- Advertising ID (AAID)
  - SDK: `play-services-ads-identifier:18.0.1`
  - Permission: `com.google.android.gms.permission.AD_MANAGER_ACCOUNT`

- App Set ID
  - SDK: `play-services-appset:16.0.0`
  - Used for cross-app user tracking without device ID

**No OAuth/SSO** - Direct app-to-backend communication only

## Consent & Regulation

**Privacy/Consent Management:**
- IAB TCF v2.0 Decoder
  - Library: `com.iabtcf:iabtcf-decoder:2.0.10`
  - Location: `bidon/src/main/java/org/bidon/sdk/regulation/`
  - Parses GDPR consent strings

- SharedPreferences for consent storage
  - File: `PreferenceManager.getDefaultSharedPreferences(context)`
  - Used in `bidon/src/main/java/org/bidon/sdk/regulation/impl/IabConsentImpl.kt`

**User Segmentation:**
- SDK supports custom segmentation and user data
- Location: `bidon/src/main/java/org/bidon/sdk/segment/`
- Sent in request bodies to Bidon backend

## Monitoring & Observability

**Error Tracking:**
- None - No Sentry, Firebase Crashlytics, or external error tracking
- Errors logged via internal logging system

**Logging:**
- Internal logging framework
  - Location: `bidon/src/main/java/org/bidon/sdk/logs/`
  - Exposes `Logger` interface for custom implementations
  - Default: Android Log

**Network Logging:**
- Requests/responses logged with truncated bodies
- Headers logged separately
- Location: `bidon/src/main/java/org/bidon/sdk/utils/networking/impl/HttpClientImpl.kt`

**Statistics:**
- Auction results tracked and sent to Bidon backend
  - Location: `bidon/src/main/java/org/bidon/sdk/stats/`
  - Sent to `/v2/stats` endpoint

**Demo App Monitoring:**
- Firebase analytics (demo only, not in SDK core)
  - Google Play Services Ads integration for testing

## CI/CD & Deployment

**Hosting:**
- Maven repositories:
  - Public: `https://artifactory.bidon.org/bidon` (snapshots and releases)
  - Private: `https://artifactory.bidon.org/artifactory/bidon-private/` (with credentials)
  - GitHub Packages: `https://maven.pkg.github.com/bidon-io/bidon-sdk-android` (with GitHub credentials)

**CI Pipeline:**
- GitHub Actions workflows in `.github/workflows/`
  - `ci-pull-request.yml` - KtLint, CHANGELOG, tests on PR
  - `ci-adapter-quality.yml` - Adapter builds and quality checks
  - `sdk-size-check.yml` - APK size regression detection
  - `automation-publish-adapters.yml` - Auto-publish on merge
  - `release-adapter.yml` - Manual adapter release
  - `release-sdk.yml` - Manual SDK release
  - `claude-fix-deprecated.yml` - Auto-fix deprecated code via Claude AI

**Build & Publish:**
- Gradle publication to Maven repositories
- Version management via `build-logic/convention-plugins/src/main/kotlin/ext/Versions.kt`
- Signing configuration via keystore properties

## Network Communication

**HTTP Implementation:**
- Custom HTTP client built on `java.net.HttpURLConnection`
  - Location: `bidon/src/main/java/org/bidon/sdk/utils/networking/impl/RawRequestClient.kt`
  - No OkHttp, Retrofit, or external HTTP client
  - Supports request body encoding/decoding via pluggable encoders

**Request/Response Handling:**
- Custom request models in `bidon/src/main/java/org/bidon/sdk/utils/networking/`
- Generic `RawRequest` and `RawResponse` wrappers
- Support for custom encoders (e.g., GZIP)

**Timeouts:**
- Configurable connect/read timeouts (default: `DefaultConnectTimeoutMs`)
- Per ad unit timeout in auction pipeline

**Retry Logic:**
- HTTP Retry-After header support
- Automatic retry on 429/503 with delay
- Multiple endpoint failover support via `BidonEndpoints`

**Encoding/Compression:**
- Pluggable encoder/decoder system
- GZIP support via `GZIPRequestDataEncoder`
- Location: `bidon/src/main/java/org/bidon/sdk/utils/networking/encoders/`

## Serialization

**JSON Handling:**
- Built on `org.json:json:20210307` (standard library)
- Custom parsers and serializers
  - Location: `bidon/src/main/java/org/bidon/sdk/utils/json/`
  - Interfaces: `JsonParser<T>`, `JsonSerializer<T>`
  - No GSON, Jackson, or kotlinx.serialization

**Data Models:**
- Location: `bidon/src/main/java/org/bidon/sdk/auction/models/` and `bidon/src/main/java/org/bidon/sdk/config/models/`
- Manual serialization to/from JSONObject

## Environment Configuration

**Required env vars for CI/CD:**
- `BDN_USERNAME` - Bidon Artifactory username
- `BDN_USERPASSWORD` - Bidon Artifactory password
- `GPR_USER` - GitHub Package Registry username
- `GPR_TOKEN` - GitHub Package Registry token
- `ANTHROPIC_API_KEY` - For Claude AI auto-fix workflow (optional)

**Runtime Configuration:**
- `BidonSdk.initialize(context, appKey)` - SDK setup
- `BidonInitializer.setBaseUrl(host)` - Custom backend URL
- `NetworkSettings.basicAuthHeader` - Optional basic auth
- Demo app config loaded from `keystore.properties` (not committed)

**Build Configuration Files:**
- `gradle.properties` - Gradle settings
- `settings.gradle.kts` - Repository and project includes
- `build.gradle.kts` - Plugin and ktlint configuration
- `keystore.properties` (local) - Signing and API keys

## Webhooks & Callbacks

**Incoming:**
- No webhook receivers - SDK is client-only

**Outgoing:**
- SDK sends data to Bidon backend endpoints
  - `/v2/config` - Configuration fetch
  - `/v2/auction` - Auction request
  - `/v2/stats` - Performance statistics

---

*Integration audit: 2026-02-05*
