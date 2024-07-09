import ext.ADAPTER_VERSION

plugins {
    id("common")
    id("publish-adapter")
}

project.extra.apply {
    this.set("AdapterArtifactId", "mytarget-adapter")
    this.set("AdapterVersionName", Versions.Adapters.MyTarget)
}

android {
    namespace = "org.bidon.mytarget"

    defaultConfig {
        ADAPTER_VERSION = Versions.Adapters.MyTarget
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)

    implementation("com.my.target:mytarget-sdk:5.21.0")
}
