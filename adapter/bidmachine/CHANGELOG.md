# Changelog

All notable changes to the BidMachine adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://developers.bidmachine.io/sdk/general/android/android-changelog)

## [Unreleased]

## [3.7.0.0] - 2026-05-13
### Changed
- Updated SDK dependency from 3.6.1 to 3.7.0
- Migrated from deprecated `BidMachine.setUSPrivacyString()` to `BidMachine.setNonPersonalized()` with CCPA privacy string parsing (format: 1YNN, position 2 indicates opt-out status)
- Replaced deprecated `BidMachine.setConsentConfig(hasConsent, consentString)` with `BidMachine.setConsentStatus(hasConsent)`

## [3.6.1.0] - 2026-03-25
### Changed
- Updated SDK dependency from 3.6.0 to 3.6.1

## [3.6.0.0] - 2026-03-17
### Changed
- Updated SDK dependency from 3.5.1 to 3.6.0

## [3.5.1.0] - 2025-12-15
### Changed
- Updated SDK dependency from 3.5.0 to 3.5.1


## [3.5.0.0]
- Updated BidMachine SDK to 3.5.0
- BDN-1076 Added support for IAB restriction parameters (bcat, badv, bapps) for BidMachine CPM ad units
