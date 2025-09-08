import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

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
