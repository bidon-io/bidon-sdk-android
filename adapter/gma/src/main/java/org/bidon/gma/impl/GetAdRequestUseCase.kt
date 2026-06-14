package org.bidon.gma.impl

import com.google.android.libraries.ads.mobile.sdk.common.AdRequest

internal class GetAdRequestUseCase {
    operator fun invoke(adUnitId: String): AdRequest =
        AdRequest.Builder(adUnitId).build()
}
