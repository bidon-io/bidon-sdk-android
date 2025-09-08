import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "mobilefuse-adapter"
    versionName = Versions.PublishedAdapters.MobileFuse
}

android {
    namespace = "org.bidon.mobilefuse"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.MobileFuse
    }
}

dependencies {
    implementation(Dependencies.Adapter.Mobilefuse)
}
