import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("common")
    id("publish-adapter")
}

publishAdapter {
    artifactId = "chartboost-adapter"
    versionName = Versions.Adapters.Chartboost
}

android {
    namespace = "org.bidon.chartboost"

    defaultConfig {
        ADAPTER_VERSION = Versions.Adapters.Chartboost
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation("com.chartboost:chartboost-sdk:9.8.3")
}
