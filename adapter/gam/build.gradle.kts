import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "gam-adapter"
    versionName = Versions.PublishedAdapters.Gam
}

android {
    namespace = "org.bidon.gam"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Gam
    }
}

dependencies {
    implementation(Dependencies.Adapter.Admob)
}
