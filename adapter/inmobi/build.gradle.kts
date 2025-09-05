import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
}

publishAdapter {
    artifactId = "inmobi-adapter"
    versionName = Versions.PublishedAdapters.Inmobi
}

android {
    namespace = "org.bidon.inmobi"

    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.Inmobi
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Inmobi)
}
