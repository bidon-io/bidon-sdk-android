import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("adapter")
}

val adapterSdkVersion = "9.4.4"
val adapterMinor = 0
val adapterSemantic = Versions.semanticVersion

val adapterMainVersion = "$adapterSdkVersion.$adapterMinor$adapterSemantic"

publishAdapter {
    artifactId = "ironsource-adapter"
    versionName = adapterMainVersion
}

android {
    namespace = "org.bidon.ironsource"

    defaultConfig {
        ADAPTER_VERSION = adapterMainVersion
    }
}

dependencies {
    implementation("com.unity3d.ads-mediation:mediation-sdk:$adapterSdkVersion")
}
