# Changelog

All notable changes to the MobileFuse adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://docs.mobilefuse.com/changelog)

## [Unreleased]

## [1.10.0.0] - 2026-01-21
### Changed
- Updated MobileFuse SDK dependency from 1.9.3 to 1.10.0
- Fixed compilation error in `loadAdFromBiddingToken()` method calls - SDK now requires non-null String parameter
- Updated `MobileFuseBannerImpl`, `MobileFuseInterstitialImpl`, and `MobileFuseRewardedAdImpl` to use Elvis operator for null safety

## [1.9.3.0]
- Updated MobileFuse SDK to 1.9.3