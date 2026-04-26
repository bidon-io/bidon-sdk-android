import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("adapter")
}

val adapterSdkVersion = "1.0.0"
val adapterMinor = 0
val adapterSemantic = Versions.semanticVersion

val adapterMainVersion = "$adapterSdkVersion.$adapterMinor$adapterSemantic"

publishAdapter {
    artifactId = "gma-adapter"
    versionName = adapterMainVersion
}

android {
    namespace = "org.bidon.gma"
    defaultConfig {
        ADAPTER_VERSION = adapterMainVersion
        minSdk = 24 // Override common minSdk (23) — GMA Next-Gen SDK requires API 24
    }
}

dependencies {
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:$adapterSdkVersion")
}

// Exclude legacy ads SDK transitively to prevent symbol conflicts
configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}
