package org.bidon.gma.ext

import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision

internal typealias GmaAdValue = com.google.android.libraries.ads.mobile.sdk.common.AdValue

internal fun GmaAdValue.asBidonAdValue(): AdValue {
    return AdValue(
        adRevenue = this.valueMicros / 1_000_000.0,
        precision = when (this.precisionType) {
            0 -> Precision.Estimated // "UNKNOWN"
            1 -> Precision.Precise // "PRECISE"
            2 -> Precision.Estimated // "ESTIMATED"
            3 -> Precision.Precise // "PUBLISHER_PROVIDED"
            else -> Precision.Estimated // "unknown type ${precisionType}"
        },
        currency = AdValue.USD,
    )
}
