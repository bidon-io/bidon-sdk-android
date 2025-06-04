import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("common")
    id("publish-adapter")
}

publishAdapter {
    artifactId = "applovin-adapter"
    versionName = Versions.Adapters.Applovin
}

android {
    namespace = "org.bidon.applovin"
    defaultConfig {
        ADAPTER_VERSION = Versions.Adapters.Applovin
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation("com.applovin:applovin-sdk:13.2.0")
}
