pluginManagement {
    includeBuild("../../build-logic")
    plugins {
        id("org.jetbrains.kotlin.android") version "2.1.0"
    }
    repositories {
        google()
        gradlePluginPortal()
    }
}
rootProject.name = "admob"

include(":bidon")