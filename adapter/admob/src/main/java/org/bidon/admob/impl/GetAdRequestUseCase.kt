package org.bidon.admob.impl

import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import org.bidon.admob.AdmobBannerAuctionParams
import org.bidon.admob.AdmobFullscreenAdAuctionParams
import org.bidon.admob.REQUEST_AGENT
import org.bidon.admob.ext.asBundle
import org.bidon.sdk.BidonSdk

/**
 * Created by Aleksei Cherniaev on 18/08/2023.
 */
internal class GetAdRequestUseCase {
    operator fun invoke(adParams: AdmobFullscreenAdAuctionParams): AdRequest {
        return when (adParams) {
            is AdmobFullscreenAdAuctionParams.Bidding -> {
                AdRequest.Builder()
                    .bindFillParams(adParams.payload)
                    .build()
            }

            is AdmobFullscreenAdAuctionParams.Network -> {
                AdRequest.Builder()
                    .addNetworkExtrasBundle(AdMobAdapter::class.java, BidonSdk.regulation.asBundle())
                    .build()
            }
        }
    }

    operator fun invoke(adParams: AdmobBannerAuctionParams): AdRequest {
        return when (adParams) {
            is AdmobBannerAuctionParams.Bidding -> {
                AdRequest.Builder()
                    .bindFillParams(adParams.payload)
                    .build()
            }

            is AdmobBannerAuctionParams.Network -> {
                AdRequest.Builder()
                    .addNetworkExtrasBundle(AdMobAdapter::class.java, BidonSdk.regulation.asBundle())
                    .build()
            }
        }
    }

    private fun AdRequest.Builder.bindFillParams(payload: String): AdRequest.Builder = this.apply {
        val networkExtras = BidonSdk.regulation.asBundle()
        setAdString(payload)
        setRequestAgent(REQUEST_AGENT)
        addNetworkExtrasBundle(AdMobAdapter::class.java, networkExtras)
    }
}