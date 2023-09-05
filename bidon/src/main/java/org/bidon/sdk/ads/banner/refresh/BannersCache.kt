package org.bidon.sdk.ads.banner.refresh

import android.app.Activity
import org.bidon.sdk.ads.Ad
import org.bidon.sdk.ads.banner.BannerFormat
import org.bidon.sdk.ads.banner.BannerListener
import org.bidon.sdk.ads.banner.BannerView
import org.bidon.sdk.config.BidonError
import org.bidon.sdk.logs.logging.impl.logInfo
import org.bidon.sdk.utils.ext.TAG

/**
 * Created by Aleksei Cherniaev on 05/09/2023.
 */
internal interface BannersCache {
    fun load(
        activity: Activity,
        pricefloor: Double
    )

    fun load(
        activity: Activity,
        pricefloor: Double,
        onLoaded: (BannerView) -> Unit,
        onFailed: (BidonError) -> Unit,
    )
}

internal class BannersCacheImpl(
    private val format: BannerFormat,
) : BannersCache {
    private val cache = mutableListOf<BannerView>()

    override fun load(
        activity: Activity,
        pricefloor: Double
    ) {
        val banner = BannerView(activity)
        banner.setBannerListener(object : BannerListener {
            override fun onAdLoaded(ad: Ad) {
                logInfo(TAG, "Banner loaded: $ad")
                cache.add(banner)
            }

            override fun onAdLoadFailed(cause: BidonError) {
                logInfo(TAG, "Banner load failed: $cause")
            }

            override fun onAdShown(ad: Ad) {}

            override fun onAdExpired(ad: Ad) {
                if (cache.contains(banner)) {
                    logInfo(TAG, "Banner expired and will be removed from cache: $ad")
                    cache.remove(banner)
                }
            }
        })
        banner.loadAd(activity, pricefloor)
    }

    override fun load(
        activity: Activity,
        pricefloor: Double,
        onLoaded: (BannerView) -> Unit,
        onFailed: (BidonError) -> Unit
    ) {
        if (cache.isNotEmpty()) {
            onLoaded(cache.removeFirst())
            return
        }
        val banner = BannerView(activity)
        banner.setBannerListener(object : BannerListener {
            override fun onAdLoaded(ad: Ad) {
                logInfo(TAG, "Banner loaded: $ad")
                if (cache.contains(banner)) {
                    onLoaded(cache.removeFirst())
                } else {
                    cache.add(banner)
                }
            }

            override fun onAdLoadFailed(cause: BidonError) {
                logInfo(TAG, "Banner load failed: $cause")
                onFailed(cause)
            }

            override fun onAdShown(ad: Ad) {}

            override fun onAdExpired(ad: Ad) {
                if (cache.contains(banner)) {
                    logInfo(TAG, "Banner expired and will be removed from cache: $ad")
                    cache.remove(banner)
                }
            }
        })
        banner.loadAd(activity, pricefloor)
    }
}