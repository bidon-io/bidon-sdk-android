# Changelog

All notable changes to the Zmaticoo adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://doc.zmaticoo.com/#/help/en/1801/)

## [Unreleased]

## [2.0.7.0.0] - 2026-08-26
### Changed
- Updated SDK dependency from 2.0.6.0 to 2.0.7.0
- Note: SDK 2.0.7.0 deprecates `InterstitialAd.isReady(String)`, `InterstitialAd.showAd(String)`, `RewardedVideoAd.isReady(String)`, and `RewardedVideoAd.showAd(String)` static methods, but does not provide working instance method alternatives; deprecated methods remain in use

## [2.0.6.0.0] - 2026-06-04
### Changed
- Updated SDK dependency from 2.0.5.1 to 2.0.6.0
- Fixed null-safety handling for `isReady()` API calls (SDK now requires non-null String parameters)
- Updated `setAdListener()` calls in `destroy()` method to use empty listener object instead of null

## [2.0.5.1.0] - 2026-04-15
### Changed
- Updated SDK dependency from 2.0.5.0 to 2.0.5.1
- Migrated deprecated `setDoNotTrackStatus(Context, Int)` to `setDoNotSell(Boolean)`

## [2.0.5.0.0] - 2026-03-25
### Changed
- Updated SDK dependency from 2.0.4.5 to 2.0.5.0

## [2.0.4.5.0]
- BDN-1103 Integrated Zmaticoo SDK 2.0.4.5 as bidding adapter
