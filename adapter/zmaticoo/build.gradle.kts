import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("adapter")
}

val adapterSdkVersion = "2.0.5.1"
val adapterMinor = 0
val adapterSemantic = Versions.semanticVersion

val adapterMainVersion = "$adapterSdkVersion.$adapterMinor$adapterSemantic"

publishAdapter {
    artifactId = "zmaticoo-adapter"
    versionName = adapterMainVersion
}

android {
    namespace = "org.bidon.zmaticoo"
    defaultConfig {
        ADAPTER_VERSION = adapterMainVersion
    }
}

dependencies {
    implementation("io.github.maticooads:maticoo-android-sdk:$adapterSdkVersion")
}
