import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "ironsource-adapter"
    versionName = Versions.PublishedAdapters.IronSource
}

android {
    namespace = "org.bidon.ironsource"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.IronSource
    }
}

dependencies {
    implementation(Dependencies.Adapter.Ironsource)
}
