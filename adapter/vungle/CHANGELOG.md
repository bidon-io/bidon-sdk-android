# Changelog

All notable changes to the Vungle adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://github.com/Vungle/VungleAds-SDK/blob/master/CHANGELOG-Android.md) | [Download](https://support.vungle.com/hc/en-us/articles/15722228922395-Download-Vungle-SDK-for-Android-Amazon)

## [Unreleased]

## [7.7.8.0] - 2026-09-02
### Changed
- Updated SDK dependency from 7.7.7 to 7.7.8

## [7.7.7.0] - 2026-07-27
### Changed
- Updated SDK dependency from 7.7.6 to 7.7.7

## [7.7.6.0] - 2026-07-15
### Changed
- Updated SDK dependency from 7.7.5 to 7.7.6

## [7.7.5.0] - 2026-07-08
### Changed
- Updated SDK dependency from 7.7.4 to 7.7.5

## [7.7.4.0] - 2026-05-13
### Changed
- Updated SDK dependency from 7.7.3 to 7.7.4

## [7.7.3.0] - 2026-04-29
### Changed
- Updated SDK dependency from 7.7.2 to 7.7.3

## [7.7.2.0] - 2026-04-01
### Changed
- Updated SDK dependency from 7.7.1 to 7.7.2

## [7.7.1.0] - 2026-03-04
### Changed
- Updated SDK dependency from 7.7.0 to 7.7.1

## [7.7.0.0] - 2026-01-29
### Changed
- Updated SDK dependency from 7.6.3 to 7.7.0

## [7.6.3.0] - 2026-01-28
### Changed
- Updated SDK dependency from 7.6.2 to 7.6.3

## [7.6.2.0] - 2026-01-07
### Changed
- Updated SDK dependency from 7.6.1 to 7.6.2
- Migrated from deprecated `BannerAd` to `VungleBannerView` API
- Replaced deprecated `BaseAdListener` with `BannerAdListener` for banner ads
- Implemented manual load state tracking using `isAdLoaded` flag to replace deprecated `canPlayAd()` method
- Simplified `getAdView()` implementation as `VungleBannerView` itself is the view (removed `getBannerView()` call)

## [7.6.1.0]
- Updated Vungle SDK to 7.6.1