# Changelog

All notable changes to the GMA adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [1.0.0.0] - Initial Release
### Added
- Initial implementation of the GMA (Google Mobile Ads Next-Gen) adapter.
- Supports Interstitial, Rewarded, and Banner ad formats in Network (No Bidding) mode.
- Integrates with `com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.0.0`.
- Requires `app_id` in the Bidon server demand source configuration for initialization.
- Requires `ad_unit_id` in the auction ad unit extra fields.

### Notes
- **GMA Next-Gen vs Legacy AdMob coexistence:** The GMA Next-Gen SDK (`com.google.android.libraries.ads.mobile.sdk`) and the legacy AdMob SDK (`com.google.android.gms:play-services-ads`) may conflict if both are included in the same application. The GMA Next-Gen example explicitly excludes `com.google.android.gms:play-services-ads`. This exclusion is an **app-level concern** and is not enforced by this adapter module. If you include both `gma-adapter` and `admob-adapter` in your app, you may need to add the following to your app's `build.gradle.kts`:
  ```kotlin
  configurations.all {
      exclude(group = "com.google.android.gms", module = "play-services-ads")
  }
  ```
- **Consent handling:** The GMA Next-Gen SDK reads IAB TCF v2 consent strings directly from `SharedPreferences` (set by the UMP SDK or any CMP). No explicit regulation bundle is required in the adapter. Bidon SDK persists IAB consent keys to SharedPreferences via `IabConsent`, which the GMA SDK will pick up automatically.
- **SDK version:** The GMA Next-Gen SDK does not expose a programmatic version API. The adapter hardcodes `sdkVersion = "1.0.0"` in `ext/Ext.kt`. Update this value when upgrading the SDK dependency.
- 📋 [View GMA Next-Gen Release Notes](https://developers.google.com/admob/android/rel-notes)
