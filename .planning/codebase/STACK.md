# Technology Stack

**Analysis Date:** 2026-02-05

## Languages

**Primary:**
- Kotlin 2.1.0 - Core SDK and all adapters
- Java 11 - Target compilation level

**Secondary:**
- Groovy (Gradle build files)

## Runtime

**Environment:**
- Android SDK 23+ (minSdk in `build-logic/convention-plugins/src/main/kotlin/ext/Dependencies.kt`)
- Android API 35 (targetSdk and compileSdk)

**Package Manager:**
- Gradle 8.7.3 (wrapper-based)
- Lockfile: gradle/wrapper/gradle-wrapper.properties

## Frameworks

**Core:**
- Android Framework (API 23+) - Ad mediation and lifecycle
- AndroidX - Modern Android support libraries
  - androidx.core:core-ktx:1.6.0
  - androidx.annotation:annotation:1.6.0
  - androidx.appcompat:appcompat:1.6.1
  - androidx.constraintlayout:constraintlayout:2.1.4
  - androidx.fragment:fragment-ktx:1.6.1
  - androidx.activity:activity-ktx:1.7.2
  - androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1
  - androidx.multidex:multidex:2.0.1

**UI/Compose (Demo App):**
- Jetpack Compose 1.9.3 - UI framework for demo app (`app/build.gradle.kts`)
- androidx.compose.material3:material3:1.4.0
- androidx.navigation:navigation-compose:2.6.0
- androidx.compose.material:material-icons-extended:1.7.8

**Async/Concurrency:**
- Kotlin Coroutines 1.6.0
  - kotlinx-coroutines-core
  - kotlinx-coroutines-android

**Testing:**
- JUnit 4.13.2 - Unit testing framework
- Kotlin Test (2.1.0) - Kotlin testing library
- MockK 1.13.5 - Mocking framework
- Google Truth 1.1.4 - Assertion library
- kotlinx-coroutines-test - Coroutine testing utilities

**Build/Dev:**
- Android Gradle Plugin 8.7.3 - Build system
- Kotlin Gradle Plugin 2.1.0 - Kotlin compilation
- KtLint 0.48.2 - Kotlin linting and formatting
- Google Play Services Gradle Plugin 4.3.14 - For Firebase integration in demo
- Gradle Configuration Cache - Performance optimization

## Key Dependencies

**Critical:**
- Google Play Services
  - play-services-ads:22.5.0 - Google AdMob SDK integration
  - play-services-ads-identifier:18.0.1 - Advertising ID access
  - play-services-appset:16.0.0 - App set identifier

**Ad Network SDKs (Adapters):**
- com.google.android.gms:play-services-ads:24.9.0 (AdMob)
- com.facebook.android:audience-network-sdk:6.21.0 (Meta)
- com.applovin:applovin-sdk:13.5.1 (AppLovin)
- com.unity3d.ads:unity-ads:4.16.6 (Unity Ads)
- com.unity3d.ads-mediation:mediation-sdk:9.3.0 (IronSource)
- com.yandex.android:mobileads:7.18.1 (Yandex)
- com.vungle:vungle-ads:7.6.3 (Vungle)
- Additional adapters: Amazon, BidMachine, BigOAds, Chartboost, DTExchange, GAM, InMobi, Mintegral, MobileFuse, Moloco, TaurusX, StartIO, VkAds, Fyber, AppsFly

**Serialization:**
- org.json:json:20210307 - JSON parsing (standard library, no external JSON parsers like GSON or Jackson)

**Consent/Privacy:**
- com.iabtcf:iabtcf-decoder:2.0.10 - IAB TCF v2.0 consent decoding

**Development/Testing:**
- LeakCanary 2.12 (debug only) - Memory leak detection in demo app

## Configuration

**Environment:**
- gradle.properties - JVM arguments, AndroidX settings, Kotlin code style
- settings.gradle.kts - Repository configuration (Google, Maven Central, Bidon private/public repos)
- build.gradle.kts - Plugin definitions and ktlint configuration

**Build:**
- build-logic/convention-plugins/ - Custom Gradle convention plugins
  - `common` - Shared Android library configuration
  - `core` - Core SDK-specific settings
  - `adapter` - Adapter-specific configuration and versioning
  - `compose` - Compose UI support
  - `sample-app-config` - Demo app configuration
  - `signature` - APK signing configuration
  - `publish-adapter` - Maven publication setup

**Build Features:**
- BuildConfig generation enabled
- Jetifier disabled (using AndroidX directly)
- R class namespacing enabled (nonTransitiveRClass)
- Configuration cache enabled for faster builds
- Parallel builds enabled

## Platform Requirements

**Development:**
- Android Studio/IntelliJ IDEA
- Java 11 JDK
- Android SDK API 35
- Minimum Android API 23 (SDK support)

**Production:**
- Target Android API 35
- Compile SDK 35
- Min SDK 23 (Android 6.0 Marshmallow)
- Google Play Services installed on device

## Code Style & Quality

**Formatting:**
- KtLint 0.48.2 - Kotlin linter/formatter
- Code style: "official" (Kotlin convention)

**Explicit API Mode:**
- Enabled in all modules - all public declarations must have explicit visibility

**Target Settings:**
- JVM target: Java 11
- Kotlin language: 2.1
- Opt-ins: RequiresOptIn, ExperimentalCoroutinesApi, InternalCoroutinesApi, FlowPreview

---

*Stack analysis: 2026-02-05*
