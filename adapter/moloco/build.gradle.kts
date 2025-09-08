import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "moloco-adapter"
    versionName = Versions.PublishedAdapters.Moloco
}

android {
    namespace = "org.bidon.moloco"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Moloco
    }
}

dependencies {
    implementation(Dependencies.Adapter.Moloco)
}
