package org.bidon.gma.ext

import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision

internal typealias GmaAdValue = com.google.android.libraries.ads.mobile.sdk.common.AdValue

/**
 * Maps GMA Next-Gen [GmaAdValue] to Bidon [AdValue].
 *
 * The Next-Gen SDK may not expose the same precisionType enum as the legacy SDK.
 * Default to [Precision.Estimated] and use the SDK's currency code when available.
 */
internal fun GmaAdValue.asBidonAdValue(): AdValue {
    return AdValue(
        adRevenue = this.valueMicros / 1_000_000.0,
        precision = Precision.Estimated,
        currency = this.currencyCode?.takeIf { it.isNotBlank() } ?: AdValue.USD,
    )
}
