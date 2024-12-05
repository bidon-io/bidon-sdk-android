package org.bidon.dtexchange.ext

import com.fyber.inneractive.sdk.external.ImpressionData
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision

/**
 * Created by Bidon Team on 09/05/2023.
 */
internal fun ImpressionData.asAdValue() = AdValue(
    adRevenue = this.pricing?.value ?: 0.0,
    precision = Precision.Precise,
    currency = this.pricing?.currency ?: AdValue.USD
)