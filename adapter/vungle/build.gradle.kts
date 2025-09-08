import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "vungle-adapter"
    versionName = Versions.PublishedAdapters.Vungle
}

android {
    namespace = "org.bidon.vungle"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Vungle
    }
}

dependencies {
    implementation(Dependencies.Adapter.Vungle)
}
