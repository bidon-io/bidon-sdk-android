import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "unityads-adapter"
    versionName = Versions.PublishedAdapters.UnityAds
}

android {
    namespace = "org.bidon.unityads"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.UnityAds
    }
}

dependencies {
    implementation(Dependencies.Adapter.UnityAds)
}
