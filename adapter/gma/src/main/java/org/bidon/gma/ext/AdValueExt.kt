package org.bidon.gma.ext

import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision

internal typealias GoogleAdValue = com.google.android.gms.ads.AdValue

internal fun GoogleAdValue.asBidonAdValue(): AdValue {
    return AdValue(
        adRevenue = this.valueMicros / 1_000_000.0,
        precision = when (this.precisionType) {
            0 -> Precision.Estimated
            1 -> Precision.Precise
            2 -> Precision.Estimated
            3 -> Precision.Precise
            else -> Precision.Estimated
        },
        currency = AdValue.USD,
    )
}
