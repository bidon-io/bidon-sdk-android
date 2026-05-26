package org.bidon.gma.impl

import com.google.android.gms.ads.AdRequest

internal class GetAdRequestUseCase {
    operator fun invoke() = AdRequest.Builder().build()
}
