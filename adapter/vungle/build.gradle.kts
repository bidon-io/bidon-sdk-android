import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

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
