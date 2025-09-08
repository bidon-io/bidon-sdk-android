import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("adapter")
}

publishAdapter {
    artifactId = "amazon-adapter"
    versionName = Versions.PublishedAdapters.Amazon
}

android {
    namespace = "org.bidon.amazon"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Amazon
    }
}

dependencies {
    implementation(Dependencies.Adapter.Amazon)
    implementation(Dependencies.Others.IabTcfDecoder)
}
