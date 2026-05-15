# Changelog

All notable changes to the BidMachine adapter will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

📋 [Release Notes](https://developers.bidmachine.io/sdk/general/android/android-changelog)

## [Unreleased]

## [3.6.1.1] - 2026-05-15
### Added
- APDM-2419: Forward host-supplied mediator identity to BidMachine. The adapter now reads the `"mediator"` key from `BidonSdk.getExtras()` and sends it as `mediation_mode` on every ad request. Falls back to `"bidon"` when the key is unset.

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
