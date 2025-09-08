import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

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
