import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
}

publishAdapter {
    artifactId = "chartboost-adapter"
    versionName = Versions.PublishedAdapters.Chartboost
}

android {
    namespace = "org.bidon.chartboost"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Chartboost
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Chartboost)
}
