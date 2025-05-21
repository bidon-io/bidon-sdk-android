import ext.ADAPTER_VERSION
import ext.Versions

plugins {
    id("common")
    id("publish-adapter")
}

project.extra.apply {
    this.set("AdapterArtifactId", "yandex-adapter")
    this.set("AdapterVersionName", Versions.Adapters.Yandex)
}

android {
    namespace = "org.bidon.yandex"

    defaultConfig {
        ADAPTER_VERSION = Versions.Adapters.Yandex
    }
}

dependencies {
    compileOnly(projects.bidon)
    testImplementation(projects.bidon)
    implementation("androidx.appcompat:appcompat-resources:1.5.1")
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.yandex.android:mobileads:7.12.3") {
        exclude(group = "androidx.appcompat", module = "appcompat")
        exclude(group = "androidx.appcompat", module = "appcompat-resources")
        exclude(group = "androidx.viewpager2", module = "viewpager2")
    }
}
