# Changelog

All notable changes to the VK Ads adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://ads.vk.com/help/partner/partner_integration/partner_android/partner_android_history)

## [Unreleased]

## [5.47.1.0] - 2026-04-29
### Changed
- Updated SDK dependency from 5.45.3 to 5.47.1

## [5.45.3.0] - 2026-03-09
### Changed
- Updated SDK dependency from 5.27.4 to 5.45.3
- Removed `const` modifier from `sdkVersion` property as `MyTargetVersion.VERSION` is no longer a compile-time constant
- Migrated from deprecated `InterstitialAdListener` to `InterstitialAdListener2` using `setListener2()`
- Migrated click events to `InterstitialAdBannerListener` using `setBannerListener()`
- Migrated video events to `InterstitialVideoListener` using `setVideoListener()`
- Renamed `onDismiss()` callback to `onClose()` in interstitial listener

## [5.27.4.0]
- Updated VK Ads SDK to 5.27.4