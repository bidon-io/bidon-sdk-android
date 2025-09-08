import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "applovin-adapter"
    versionName = Versions.PublishedAdapters.Applovin
}

android {
    namespace = "org.bidon.applovin"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Applovin
    }
}

dependencies {
    implementation(Dependencies.Adapter.Applovin)
}
