package org.bidon.demoapp.ui.ext

import android.os.Build
import org.bidon.demoapp.ui.model.ImpExt
import org.bidon.demoapp.ui.model.ImpressionInfo
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.stats.models.BidType
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Created by Aleksei Cherniaev on 13/07/2023.
 */
internal val LocalDateTimeNow get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
} else {
    System.currentTimeMillis()
}

internal fun Ad.toImpressionData() =
    ImpressionInfo(
        adUnitName = adUnit.label,
        networkName = networkName,
        placementId = null,
        placementName = null,
        revenue = ecpm,
        currency = currencyCode,
        precision = if (adUnit.bidType == BidType.RTB) "exact" else "estimated",
        demandSource = dsp,
        ext = ImpExt(
            networkName = networkName,
            dspName = dsp,
            adUnitId = adUnit.uid,
            credentials = adUnit.extra.toString()
        )
    )