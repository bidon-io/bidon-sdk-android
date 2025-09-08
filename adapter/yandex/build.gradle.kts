import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "yandex-adapter"
    versionName = Versions.PublishedAdapters.Yandex
}

android {
    namespace = "org.bidon.yandex"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Yandex
    }
}

dependencies {
    implementation(Dependencies.Adapter.Yandex)
}
