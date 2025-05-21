buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
//    dependencies {
//        classpath(Dependencies.Android.gradlePlugin)
//        classpath(Dependencies.Kotlin.gradlePlugin)
//        classpath(Dependencies.Google.Services)
//    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
//    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.android.test") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}
