import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "vkads-adapter"
    versionName = Versions.PublishedAdapters.VkAds
}

android {
    namespace = "org.bidon.vkads"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.VkAds
    }
}

dependencies {
    implementation(Dependencies.Adapter.VkAds)
}
