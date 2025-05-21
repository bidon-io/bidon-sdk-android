import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("publish-adapter")
    id("kotlin-2-0")
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

project.extra.apply {
    this.set("AdapterArtifactId", "gam-adapter")
    this.set("AdapterVersionName", Versions.Adapters.Gam)
}

android {
    namespace = "org.bidon.gam"
    defaultConfig {
        ADAPTER_VERSION = Versions.Adapters.Gam
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Google.PlayServicesAds)
}