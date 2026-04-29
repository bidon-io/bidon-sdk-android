# Changelog

All notable changes to the Yandex adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://raw.githubusercontent.com/yandexmobile/yandex-ads-sdk-android/master/changelogs/mobileads/CHANGELOG.md) | [Changelog](https://ads.yandex.com/helpcenter/en/dev/android/changelog-android)

## [Unreleased]

## [8.0.0.0] - 2026-04-29
### Changed
- Updated SDK dependency from 7.18.5 to 8.0.0
- Migrated from `MobileAds` to `YandexAds` API
- Replaced `AdRequestConfiguration` with `AdRequest` (adUnitId now passed to constructor)
- Migrated `BidderTokenRequestConfiguration.Builder` to factory methods: `BidderTokenRequest.banner()`, `BidderTokenRequest.interstitial()`, `BidderTokenRequest.rewarded()`
- Migrated `BidderTokenLoader.loadBidderToken()` from static to instance method
- Replaced `BannerAdSize.fixedSize()` with `BannerAdSize.inline()`
- Removed `BannerAdView.setAdUnitId()` (adUnitId now passed via `AdRequest.Builder`)
- Replaced `setAgeRestrictedUser()` with `setAgeRestricted()`
- Removed deprecated banner event listeners: `onLeftApplication()` and `onReturnedToApplication()`
- Updated ad loaders to pass listeners directly to `loadAd()` instead of `setAdLoadListener()`

## [7.18.5.0] - 2026-04-01
### Changed
- Updated SDK dependency from 7.18.4 to 7.18.5

## [7.18.4.0] - 2026-03-25
### Changed
- Updated SDK dependency from 7.18.3 to 7.18.4

## [7.18.3.0] - 2026-03-18
### Changed
- Updated SDK dependency from 7.18.2 to 7.18.3

## [7.18.2.0] - 2026-01-29
### Changed
- Updated SDK dependency from 7.18.1 to 7.18.2
- Suppressed deprecation warning for `BidderTokenRequestConfiguration.Builder(AdType)` constructor (factory methods not yet available in Android SDK)

## [7.18.1.0] - 2026-01-07
### Changed
- Updated SDK dependency from 7.18.0 to 7.18.1

## [7.18.0.0] - 2025-12-10
### Changed
- Updated SDK dependency from 7.17.0 to 7.18.0


## [7.17.0.0]
- Updated Yandex SDK to 7.17.0
- BDN-1071 Implemented RTB mode for Yandex adapter
