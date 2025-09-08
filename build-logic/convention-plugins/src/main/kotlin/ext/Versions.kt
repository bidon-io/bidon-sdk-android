package ext

import ext.Versions.AdapterSdk.AdmobSdk
import ext.Versions.AdapterSdk.AmazonSdk
import ext.Versions.AdapterSdk.ApplovinSdk
import ext.Versions.AdapterSdk.BidmachineSdk
import ext.Versions.AdapterSdk.BigoAdsSdk
import ext.Versions.AdapterSdk.ChartboostSdk
import ext.Versions.AdapterSdk.DtexchangeSdk
import ext.Versions.AdapterSdk.InmobiSdk
import ext.Versions.AdapterSdk.IronsourceSdk
import ext.Versions.AdapterSdk.MetaSdk
import ext.Versions.AdapterSdk.MintegralSdk
import ext.Versions.AdapterSdk.MobilefuseSdk
import ext.Versions.AdapterSdk.MolocoSdk
import ext.Versions.AdapterSdk.UnityAdsSdk
import ext.Versions.AdapterSdk.VkAdsSdk
import ext.Versions.AdapterSdk.VungleSdk
import ext.Versions.AdapterSdk.YandexSdk

object Versions {
    private val major = 0
    private val minor = 11
    private val patch = 0
    private val semantic: String = ""

    val BidonVersionName = mainVersion + semanticVersion

    object AdapterRange {
        const val Min = "0.11.0"
        const val Max = "1.0.0"
    }

    object AdapterSdk {
        const val AdmobSdk = "24.5.0"
        const val AmazonSdk = "11.0.1"
        const val ApplovinSdk = "13.3.1"
        const val BidmachineSdk = "3.4.0"
        const val BigoAdsSdk = "5.4.0"
        const val ChartboostSdk = "9.9.1"
        const val DtexchangeSdk = "8.3.8"
        const val InmobiSdk = "10.8.7"
        const val IronsourceSdk = "8.10.0"
        const val MetaSdk = "6.20.0"
        const val MintegralSdk = "16.9.91"
        const val MobilefuseSdk = "1.9.2"
        const val MolocoSdk = "3.12.0"
        const val UnityAdsSdk = "4.16.0"
        const val VkAdsSdk = "5.27.2"
        const val VungleSdk = "7.5.0"
        const val YandexSdk = "7.15.0"
    }

    object PublishedAdapters {
        private fun adapterVersion(sdk: String, minor: Int) = "$sdk.$minor$semanticVersion"

        val Admob = adapterVersion(sdk = AdmobSdk, minor = 0)
        val Amazon = adapterVersion(sdk = AmazonSdk, minor = 0)
        val Applovin = adapterVersion(sdk = ApplovinSdk, minor = 0)
        val BidMachine = adapterVersion(sdk = BidmachineSdk, minor = 0)
        val BigoAds = adapterVersion(sdk = BigoAdsSdk, minor = 0)
        val Chartboost = adapterVersion(sdk = ChartboostSdk, minor = 0)
        val DTExchange = adapterVersion(sdk = DtexchangeSdk, minor = 0)
        val Gam = adapterVersion(sdk = AdmobSdk, minor = 0)
        val Inmobi = adapterVersion(sdk = InmobiSdk, minor = 0)
        val IronSource = adapterVersion(sdk = IronsourceSdk, minor = 0)
        val Meta = adapterVersion(sdk = MetaSdk, minor = 0)
        val Mintegral = adapterVersion(sdk = MintegralSdk, minor = 0)
        val MobileFuse = adapterVersion(sdk = MobilefuseSdk, minor = 0)
        val Moloco = adapterVersion(sdk = MolocoSdk, minor = 0)
        val UnityAds = adapterVersion(sdk = UnityAdsSdk, minor = 0)
        val VkAds = adapterVersion(sdk = VkAdsSdk, minor = 0)
        val Vungle = adapterVersion(sdk = VungleSdk, minor = 0)
        val Yandex = adapterVersion(sdk = YandexSdk, minor = 0)

        val Appsflyer = "$mainVersion.0"
        val Fyber = "$mainVersion.0"
    }

    object ThirdPartyMediationAdapters {
        val ApplovinMax = "$mainVersion.0" + semanticVersion
        val LevelPlay = "$mainVersion.0" + semanticVersion
    }

    private val mainVersion get() = "$major.$minor.$patch"
    private val semanticVersion get() = semantic.takeIf { it.isNotBlank() }.orEmpty()
}
