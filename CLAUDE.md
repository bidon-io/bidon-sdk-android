# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bidon SDK is an Android ad mediation SDK that provides transparent and fair advertising auctions. It connects multiple ad network adapters (AdMob, AppLovin, Unity Ads, Meta, etc.) through a unified interface, giving publishers control over their advertising stack.

## Build Commands

```bash
# Build everything
./gradlew build

# Build core SDK only
./gradlew :bidon:assembleRelease

# Build specific adapter
./gradlew :adapter:admob:assembleRelease
./gradlew :adapter:yandex:assembleRelease

# Run all tests
./gradlew test

# Run core SDK tests
./gradlew :bidon:testReleaseUnitTest

# Run specific adapter tests
./gradlew :adapter:admob:testReleaseUnitTest

# Lint check (ktlint)
./gradlew ktlintCheck

# Format code
./gradlew ktlintFormat

# Publish to private repository
./gradlew publishAllPublicationsToBidonRepository -Prepo='bidon-private' -Puname='USER' -Pupassword='PASSWORD'

# Publish to public repository
./gradlew publishAllPublicationsToBidonRepository -Prepo='bidon' -Puname='USER' -Pupassword='PASSWORD'
```

## Architecture

### Module Structure

- **bidon/** - Core SDK module (`org.bidon:bidon-sdk`)
- **adapter/** - Ad network adapters (admob, applovin, meta, unityads, yandex, etc.)
- **thirdPartyMediationAdapters/** - Adapters for third-party mediation (applovin_max, level_play)
- **app/** - Demo application with `production` and `serverless` build variants
- **build-logic/** - Gradle convention plugins

### Core SDK Components

The SDK entry point is `BidonSdk` object which delegates to `Bidon` class implementing:
- `BidonInitializer` - SDK initialization
- `Logger` - Logging
- `Extras` - Custom key-value data
- `Segmentation` - User segmentation
- `Consent` - Privacy/consent management

Key packages in `bidon/src/main/java/org/bidon/sdk/`:
- `adapter/` - Adapter interfaces (`Adapter`, `AdSource`, `AdProvider`)
- `auction/` - Auction logic and models
- `config/` - SDK configuration and initialization
- `ads/` - Ad format implementations (banner, interstitial, rewarded)
- `regulation/` - GDPR/consent handling

### Adapter Architecture

Each adapter implements:
1. **Adapter** interface - Either `Adapter.Network` or `Adapter.Bidding`
2. **Initializable<T>** - Initialization with parsed config params
3. **AdProvider** interfaces - `Banner`, `Interstitial`, `Rewarded` as needed

Adapter structure (example: admob):
```
adapter/admob/
├── build.gradle.kts          # Defines adapter version and SDK dependency
└── src/main/java/org/bidon/admob/
    ├── AdmobAdapter.kt       # Main adapter class
    ├── AdmobInitParameters.kt
    └── impl/
        ├── AdmobBannerImpl.kt
        ├── AdmobInterstitialImpl.kt
        └── AdmobRewardedImpl.kt
```

### Versioning

Versions are defined in `build-logic/convention-plugins/src/main/kotlin/ext/Versions.kt`:
- Core SDK: `{major}.{minor}.{patch}{semantic}`
- Adapters: `{adNetworkSdkVersion}.{adapterMinor}{semantic}`

Adapter compatibility range: bidon-sdk `0.10.0` to `1.0.0`

### Build Plugins

Convention plugins in `build-logic/convention-plugins/`:
- `adapter` - Applies to all adapters, adds core SDK dependency with version constraints
- `core` - Applies to core SDK module
- `common` - Shared Android configuration
- `publishAdapter` - Publication configuration

## CI/CD

- **ci-pull-request.yml** - KtLint, CHANGELOG verification, core tests
- **ci-adapter-quality.yml** - Builds and tests changed adapters on PR, uploads deprecated warnings
- **sdk-size-check.yml** - Compares APK size on adapter updates, comments on PR
- **claude-fix-deprecated.yml** - Auto-fixes deprecated code using Claude AI (requires `ANTHROPIC_API_KEY`)
- **automation-publish-adapters.yml** - Auto-publishes adapters after merge to develop
- **release-adapter.yml** - Manual adapter publication
- **release-sdk.yml** - Manual SDK publication (public repo requires `release/*` branch)

## Git Workflow

- Target `develop` for feature PRs
- Target `main` for releases
- Dependabot monitors adapter dependencies weekly
- CHANGELOG.md updates required for adapter changes

## Adapter CHANGELOG Format

Each adapter has `CHANGELOG.md` with Release Notes URL used by Claude for deprecated fixes:
```markdown
📋 [Release Notes](https://example.com/changelog)
```

## Key Conventions

- Build variant for CI: `productionDebug` (faster) or `productionRelease`
- Deprecated code is blocked in adapters (CI fails on deprecated warnings)
- ktlintFormat runs automatically via Claude Code hooks on Kotlin file changes