# Changelog

All notable changes to the Unity Ads adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://docs.unity.com/en-us/grow/ads/changelog) | [GitHub](https://github.com/Unity-Technologies/unity-ads-android/releases)

## [Unreleased]

## [4.19.0.0] - 2026-07-01
### Changed
- Updated SDK dependency from 4.18.1 to 4.19.0
- Migrated from deprecated `IUnityAdsInitializationListener` to `InitializationListener`
- Replaced deprecated `UnityAds.initialize()` with `InitializationConfiguration.Builder` API
- Migrated from deprecated `MetaData` class to new privacy properties (`UnityAds.userConsent`, `userOptOut`, `nonBehavioral`)
- Replaced deprecated `BannerView` constructor with `BannerAd.load()` API
- Migrated from deprecated `BannerView.IListener` to `BannerShowListener` with updated callback methods
- Replaced deprecated `UnityAds.load()` with `InterstitialAd.load()` and `RewardedAd.load()` APIs
- Migrated from deprecated `IUnityAdsLoadListener` to lambda-based load callbacks
- Replaced deprecated `UnityAds.show()` with `InterstitialAd.show()` and `RewardedAd.show()` methods
- Migrated from deprecated `IUnityAdsShowListener` to `InterstitialShowListener` and `RewardedShowListener`
- Updated error handling from deprecated error enums to `UnityAdsError` with numeric error codes

## [4.18.1.0] - 2026-06-04
### Changed
- Updated SDK dependency from 4.18.0 to 4.18.1

## [4.18.0.0] - 2026-05-13
### Changed
- Updated SDK dependency from 4.17.0 to 4.18.0

## [4.17.0.0] - 2026-03-11
### Changed
- Updated SDK dependency from 4.16.6 to 4.17.0

## [4.16.6.0] - 2026-01-28
### Changed
- Updated SDK dependency from 4.16.5 to 4.16.6

## [4.16.5.0] - 2025-12-10
### Changed
- Updated SDK dependency from 4.16.4 to 4.16.5


## [4.16.4.1]
- BDN-1101 Added revenue paid event for Unity Ads banner ad type
## [4.16.4.0]
- Updated Unity Ads SDK to 4.16.4
