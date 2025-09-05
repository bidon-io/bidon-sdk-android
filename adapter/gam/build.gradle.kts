import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("common")
}

publishAdapter {
    artifactId = "gam-adapter"
    versionName = Versions.PublishedAdapters.Gam
}

android {
    namespace = "org.bidon.gam"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Gam
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Admob)
}
