package ext

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

object Dependencies {

    object Adapter {
        const val Admob = "com.google.android.gms:play-services-ads:${Versions.AdapterSdk.AdmobSdk}"
        const val Amazon = "com.amazon.android:aps-sdk:${Versions.AdapterSdk.AmazonSdk}"
        const val Applovin = "com.applovin:applovin-sdk:${Versions.AdapterSdk.ApplovinSdk}"
        const val Bidmachine = "io.bidmachine:ads:${Versions.AdapterSdk.BidmachineSdk}"
        const val BigoAds = "com.bigossp:bigo-ads:${Versions.AdapterSdk.BigoAdsSdk}"
        const val Chartboost = "com.chartboost:chartboost-sdk:${Versions.AdapterSdk.ChartboostSdk}"
        const val Dtexchange = "com.fyber:marketplace-sdk:${Versions.AdapterSdk.DtexchangeSdk}"
        const val Inmobi = "com.inmobi.monetization:inmobi-ads-kotlin:${Versions.AdapterSdk.InmobiSdk}"
        const val Ironsource = "com.unity3d.ads-mediation:mediation-sdk:${Versions.AdapterSdk.IronsourceSdk}"
        const val Meta = "com.facebook.android:audience-network-sdk:${Versions.AdapterSdk.MetaSdk}"
        const val Mintegral = "com.mbridge.msdk.oversea:mbridge_android_sdk:${Versions.AdapterSdk.MintegralSdk}"
        const val Mobilefuse = "com.mobilefuse.sdk:mobilefuse-sdk-core:${Versions.AdapterSdk.MobilefuseSdk}"
        const val Moloco = "com.moloco.sdk:moloco-sdk:${Versions.AdapterSdk.MolocoSdk}"
        const val UnityAds = "com.unity3d.ads:unity-ads:${Versions.AdapterSdk.UnityAdsSdk}"
        const val VkAds = "com.my.target:mytarget-sdk:${Versions.AdapterSdk.VkAdsSdk}"
        const val Vungle = "com.vungle:vungle-ads:${Versions.AdapterSdk.VungleSdk}"
        const val Yandex = "com.yandex.android:mobileads:${Versions.AdapterSdk.YandexSdk}"
    }

    object Kotlin {
        const val kotlinVersion = "2.1.0"
        val kotlinTarget = KotlinVersion.KOTLIN_2_1

        /**
         * [Compatibility](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
         */
        const val bom = "org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"
        const val reflect = "org.jetbrains.kotlin:kotlin-reflect"

        object Coroutines {
            const val bom = "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.0"
            const val KotlinxCoroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core"
            const val KotlinxCoroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android"
        }

    }

    object Android {
        const val compileSdkVersion = 35
        const val targetSdkVersion = 35
        const val minSdkVersion = 23

        const val CoreKtx = "androidx.core:core-ktx:1.6.0"
        const val Annotation = "androidx.annotation:annotation:1.6.0"
    }

    object Java {
        const val javaVersion = 11
        val javaCompile = JvmTarget.JVM_11
    }

    object Google {
        const val AppSet = "com.google.android.gms:play-services-appset:16.0.0"
        const val PlayServicesAdsIdentifier =
            "com.google.android.gms:play-services-ads-identifier:18.0.1"
    }

    object Others {
        const val IabTcfDecoder = "com.iabtcf:iabtcf-decoder:2.0.10"
    }
}