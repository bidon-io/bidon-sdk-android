package org.bidon.gma.impl

import com.google.android.libraries.ads.mobile.sdk.common.AdRequest

internal class GetAdRequestUseCase {
    /**
     * GMA Next-Gen SDK reads IAB consent strings directly from SharedPreferences automatically.
     * No explicit regulation bundle is needed.
     */
    operator fun invoke(adUnitId: String): AdRequest {
        return AdRequest.Builder(adUnitId).build()
    }
}
