import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "admob-adapter"
    versionName = Versions.PublishedAdapters.Admob
}

android {
    namespace = "org.bidon.admob"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Admob
    }
}

dependencies {
    implementation(Dependencies.Adapter.Admob)
}
