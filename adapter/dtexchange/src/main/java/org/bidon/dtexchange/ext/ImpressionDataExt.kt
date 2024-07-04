package org.bidon.dtexchange.ext

import com.fyber.inneractive.sdk.external.ImpressionData
import org.bidon.sdk.logs.analytic.AdValue
import org.bidon.sdk.logs.analytic.Precision

/**
 * Created by Aleksei Cherniaev on 09/05/2023.
 */
internal fun ImpressionData.asAdValue(precision: Precision) = AdValue(
    adRevenue = this.pricing?.value ?: 0.0,
    precision = precision,
    currency = this.pricing?.currency ?: AdValue.USD
)