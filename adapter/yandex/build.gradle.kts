import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
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
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Yandex)
}
