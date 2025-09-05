import ext.ADAPTER_VERSION
import ext.Dependencies
import ext.Versions

plugins {
    id("common")
}

publishAdapter {
    artifactId = "meta-adapter"
    versionName = Versions.PublishedAdapters.Meta
}

android {
    namespace = "org.bidon.meta"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Meta
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Meta) {
        exclude(group = "com.google.android.gms", module = "play-services-basement")
    }
    implementation(Dependencies.Google.PlayServicesAdsIdentifier)
}
