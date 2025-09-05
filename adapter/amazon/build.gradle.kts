import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
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
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Amazon)
    implementation(Dependencies.Others.IabTcfDecoder)
}
