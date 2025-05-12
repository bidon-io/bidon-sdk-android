import ext.ADAPTER_VERSION

plugins {
    id("common")
}

project.extra.apply {
    this.set("AdapterArtifactId", "bidon-adapter")
    this.set("AdapterVersionName", Versions.BidonVersionName)
}

android {
    namespace = "com.applovin.mediation.adapters"
    defaultConfig {
        ADAPTER_VERSION = Versions.BidonVersionName
    }
}

dependencies {
    implementation(projects.bidon)
    testImplementation(projects.bidon)

    compileOnly("com.applovin:applovin-sdk:13.1.0")
}
