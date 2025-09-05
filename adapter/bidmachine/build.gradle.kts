import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("common")
}

publishAdapter {
    artifactId = "bidmachine-adapter"
    versionName = Versions.PublishedAdapters.BidMachine
}

android {
    namespace = "org.bidon.bidmachine"
    defaultConfig {
        ADAPTER_VERSION = Versions.PublishedAdapters.BidMachine
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation(Dependencies.Adapter.Bidmachine)
}
