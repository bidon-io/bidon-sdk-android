package org.bidon.gma.ext

import com.google.android.libraries.ads.mobile.sdk.common.AdValue as GmaAdValue
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision

internal fun GmaAdValue.asBidonAdValue(): AdValue {
    return AdValue(
        adRevenue = this.valueMicros / 1_000_000.0,
        precision = when (this.precisionType) {
            0 -> Precision.Estimated // UNKNOWN
            1 -> Precision.Precise   // PRECISE
            2 -> Precision.Estimated // ESTIMATED
            3 -> Precision.Precise   // PUBLISHER_PROVIDED
            else -> Precision.Estimated
        },
        currency = AdValue.USD,
    )
}
