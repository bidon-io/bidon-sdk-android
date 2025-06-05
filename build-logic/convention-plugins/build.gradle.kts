import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "org.bidon.convention-plugins"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        register("commonGradle") {
            id = "common"
            implementationClass = "CommonGradlePlugin"
        }
        register("publishAdapterGradle") {
            id = "publish-adapter"
            implementationClass = "PublishAdapterPlugin"
        }
        register("sampleAppConfig") {
            id = "sample-app-config"
            implementationClass = "SampleAppConfigPlugin"
        }
        register("signatureConfig") {
            id = "signature"
            implementationClass = "SignaturePlugin"
        }
        register("composeConfig") {
            id = "compose"
            implementationClass = "ComposePlugin"
        }
        register("kotlin20") {
            id = "kotlin-2-0"
            implementationClass = "Kotlin20Plugin"
        }
    }
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

dependencies {
    implementation(kotlin("gradle-plugin"))
    compileOnly("com.android.tools.build:gradle:8.7.3")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    compileOnly("com.android.tools:common:31.9.0")
    compileOnly("org.jlleitschuh.gradle:ktlint-gradle:12.1.0")
}