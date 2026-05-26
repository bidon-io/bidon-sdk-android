import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("adapter")
}

val adapterSdkVersion = "25.2.0"
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
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-ads:$adapterSdkVersion")
}
