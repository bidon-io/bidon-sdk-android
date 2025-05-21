import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("publish-adapter")
    id("kotlin-2-0")
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

project.extra.apply {
    this.set("AdapterArtifactId", "admob-adapter")
    this.set("AdapterVersionName", Versions.Adapters.Admob)
}

android {
    namespace = "org.bidon.admob"
    defaultConfig {
        ADAPTER_VERSION = Versions.Adapters.Admob
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Google.PlayServicesAds)
}
