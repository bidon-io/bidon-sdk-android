package org.bidon.demoapp.ui.ext

import android.os.Build
import org.bidon.sdk.ads.AdUnitInfo
import org.bidon.sdk.ads.AuctionInfo
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Created by Aleksei Cherniaev on 13/07/2023.
 */
internal val LocalDateTimeNow
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    } else {
        System.currentTimeMillis()
    }

fun AuctionInfo.toJson(): String {
    return """
            {
                "auction_id": "$auctionId",
                "auction_configuration_id": ${auctionConfigurationId},
                "auction_configuration_uid": ${auctionConfigurationUid?.let { "\"$it\"" }},
                "auction_pricefloor": $auctionPricefloor,
                "no_bids": ${
        noBids?.let {
            it.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { adUnit -> adUnit.toJson() }
        }
    },
                "adUnits": ${
        adUnits?.let {
            it.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { adUnit -> adUnit.toJson() }
        }
    }
            }
        """.trimIndent()
}

fun AdUnitInfo.toJson(): String {
    return """
            {
                "demand_id": "$demandId",
                "label": ${label?.let { "\"$it\"" }},
                "price": ${price},
                "uid": ${uid?.let { "\"$it\"" }},
                "bid_type": ${bidType?.let { "\"$it\"" }},
                "fill_start_ts": ${fillStartTs},
                "fill_finish_ts": ${fillFinishTs},
                "status": ${status?.let { "\"$it\"" }},
                "error_message": ${errorMessage?.let { "\"$it\"" }},
                "ext": ${ext?.let { "\"$it\"" }}
            }
        """.trimIndent()
}