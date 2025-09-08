import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "chartboost-adapter"
    versionName = Versions.PublishedAdapters.Chartboost
}

android {
    namespace = "org.bidon.chartboost"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Chartboost
    }
}

dependencies {
    implementation(Dependencies.Adapter.Chartboost)
}
