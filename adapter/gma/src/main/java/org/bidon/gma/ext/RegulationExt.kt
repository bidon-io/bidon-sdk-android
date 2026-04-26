package org.bidon.gma.ext

import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.regulation.Regulation

// TODO: GMA Next-Gen SDK relies on UMP SDK for consent, not per-request extras.
//       Regulation is expected to be handled by the app via UMP SDK before ad requests.
//       The SDK reads TCF strings from SharedPreferences (set by UMP SDK).
//       No per-request consent bundle is needed.
//       See: https://developers.google.com/interactive-media-ads/ump/android

/**
 * No-op regulation handler for GMA Next-Gen adapter.
 *
 * GMA Next-Gen SDK uses the UMP (User Messaging Platform) SDK for consent handling.
 * Apps integrating this adapter should separately integrate the UMP SDK to handle
 * GDPR, CCPA, and other consent requirements. The GMA SDK will automatically read
 * IAB TCF strings from SharedPreferences set by the UMP SDK.
 */
@Suppress("unused")
internal fun Regulation.applyToGma() {
    logInfo("GmaAdapter", "Regulation: GMA Next-Gen relies on UMP SDK for consent handling. " +
            "No per-request extras bundle applied.")
    // No-op: The app is expected to integrate UMP SDK separately.
}
