import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
}

publishAdapter {
    artifactId = "applovin-adapter"
    versionName = Versions.PublishedAdapters.Applovin
}

android {
    namespace = "org.bidon.applovin"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Applovin
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Applovin)
}
