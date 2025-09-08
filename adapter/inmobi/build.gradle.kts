import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "inmobi-adapter"
    versionName = Versions.PublishedAdapters.Inmobi
}

android {
    namespace = "org.bidon.inmobi"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Inmobi
    }
}

dependencies {
    implementation(Dependencies.Adapter.Inmobi)
}
