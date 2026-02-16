package org.bidon.demoapp.ui.domain

import androidx.lifecycle.ViewModel
import org.bidon.sdk.ads.banner.BannerManager

/**
 * Created by Bidon Team on 08/09/2023.
 */
class BannerManagerViewModel : ViewModel() {
    val bannerManager by lazy { BannerManager() }
}