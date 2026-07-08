import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("adapter")
}

val adapterSdkVersion = "4.10.0"
val adapterMinor = 1
val adapterSemantic = Versions.semanticVersion

val adapterMainVersion = "$adapterSdkVersion.$adapterMinor$adapterSemantic"

publishAdapter {
    artifactId = "moloco-adapter"
    versionName = adapterMainVersion
}

android {
    namespace = "org.bidon.moloco"

    defaultConfig {
        ADAPTER_VERSION = adapterMainVersion
    }
}

dependencies {
    implementation("com.moloco.sdk:moloco-sdk:$adapterSdkVersion")
}
