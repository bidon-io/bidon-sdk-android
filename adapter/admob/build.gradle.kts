import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("common")
}

publishAdapter {
    artifactId = "admob-adapter"
    versionName = Versions.PublishedAdapters.Admob
}

android {
    namespace = "org.bidon.admob"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Admob
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Admob)
}
