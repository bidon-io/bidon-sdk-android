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
                "auctionId": "$auctionId",
                "auctionConfigurationId": ${auctionConfigurationId},
                "auctionConfigurationUid": ${auctionConfigurationUid?.let { "\"$it\"" }},
                "auctionPricefloor": $auctionPricefloor,
                "noBids": ${
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
                "demandId": "$demandId",
                "label": ${label?.let { "\"$it\"" }},
                "price": ${price},
                "uid": ${uid?.let { "\"$it\"" }},
                "bidType": ${bidType?.let { "\"$it\"" }},
                "fillStartTs": ${fillStartTs},
                "fillFinishTs": ${fillFinishTs},
                "status": ${status?.let { "\"$it\"" }},
                "errorMessage": ${errorMessage?.let { "\"$it\"" }},
                "ext": ${ext?.let { "\"$it\"" }}
            }
        """.trimIndent()
}