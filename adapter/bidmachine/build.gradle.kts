import ext.ADAPTER_VERSION
import ext.Versions
import ext.Dependencies

plugins {
    id("adapter")
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
    implementation(Dependencies.Adapter.Bidmachine)
}
