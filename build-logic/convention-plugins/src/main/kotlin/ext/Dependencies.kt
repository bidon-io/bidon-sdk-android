package ext

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

object Dependencies {
    object Kotlin {
        const val kotlinVersion = "1.9.10"

        /**
         * [Compatibility](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
         */
        const val kotlinCompilerExtensionVersion = "1.5.3"
        const val bom = "org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"
        const val reflect = "org.jetbrains.kotlin:kotlin-reflect"

        object Coroutines {
            const val bom = "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.0"
            const val KotlinxCoroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core"
            const val KotlinxCoroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android"
        }

    }

    object Android {
        const val compileSdkVersion = 34
        const val targetSdkVersion = 33
        const val minSdkVersion = 23

        const val CoreKtx = "androidx.core:core-ktx:1.6.0"
        const val Annotation = "androidx.annotation:annotation:1.1.0"
    }

    object Java {
        const val javaVersion = 17
        val kotlinCompile = JvmTarget.JVM_17
    }

    object Google {
        const val PlayServicesAds = "com.google.android.gms:play-services-ads:24.3.0"
        const val PlayServicesAdsIdentifier = "com.google.android.gms:play-services-ads-identifier:18.0.1"
    }
}