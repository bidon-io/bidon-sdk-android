import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "mintegral-adapter"
    versionName = Versions.PublishedAdapters.Mintegral
}

android {
    namespace = "org.bidon.mintegral"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Mintegral
    }
}

dependencies {
    implementation(Dependencies.Adapter.Mintegral)
}
