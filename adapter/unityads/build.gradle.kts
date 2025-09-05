import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
}

publishAdapter {
    artifactId = "unityads-adapter"
    versionName = Versions.PublishedAdapters.UnityAds
}

android {
    namespace = "org.bidon.unityads"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.UnityAds
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.UnityAds)
}
