import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "dtexchange-adapter"
    versionName = Versions.PublishedAdapters.DTExchange
}

android {
    namespace = "org.bidon.dtexchange"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.DTExchange
    }
}

dependencies {
    implementation(Dependencies.Adapter.Dtexchange)
    implementation(Dependencies.Google.PlayServicesAdsIdentifier)
}
