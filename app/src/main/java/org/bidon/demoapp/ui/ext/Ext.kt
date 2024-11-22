package org.bidon.demoapp.ui.ext

import android.os.Build
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.AuctionInfo
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Created by Bidon Team on 13/07/2023.
 */
internal val LocalDateTimeNow
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    } else {
        System.currentTimeMillis()
    }

internal fun Ad.demo(): String {
    return "${demandAd.adType} $networkName/$bidType $ecpm $currencyCode"
}

internal fun AuctionInfo.demo(): String {
    return "id='$auctionId', timeout=$auctionTimeout, pricefloor=$auctionPricefloor, noBidsSize=${noBids?.size}, adUnitsSize=${adUnits?.size})"
}