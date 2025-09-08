import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "bigoads-adapter"
    versionName = Versions.PublishedAdapters.BigoAds
}

android {
    namespace = "org.bidon.bigoads"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.BigoAds
    }
}

dependencies {
    implementation(Dependencies.Adapter.BigoAds)
}
