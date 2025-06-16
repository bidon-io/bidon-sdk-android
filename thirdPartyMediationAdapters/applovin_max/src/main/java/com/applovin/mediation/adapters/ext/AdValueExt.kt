package com.applovin.mediation.adapters.ext

import android.os.Bundle
import org.bidon.sdk.ads.Ad

fun Ad.toAdValueBundle(maxPlacement: String, maxEcpm: Double): Bundle =
    Bundle(6).apply {
        putString(KEY_MAX_PLACEMENT, maxPlacement)
        putDouble(KEY_MAX_ECPM, maxEcpm)
        putString(KEY_AD_UNIT_UID, adUnit.uid)
        putString(KEY_DEMAND_ID, networkName)
        putDouble(KEY_PRICE, price)
        putString(KEY_BID_TYPE, bidType.code)
    }

private const val KEY_MAX_PLACEMENT = "max_placement"
private const val KEY_MAX_ECPM = "max_ecpm"
private const val KEY_AD_UNIT_UID = "ad_unit_uid"
private const val KEY_DEMAND_ID = "demand_id"
private const val KEY_PRICE = "price"
private const val KEY_BID_TYPE = "bid_type"