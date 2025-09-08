import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

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
