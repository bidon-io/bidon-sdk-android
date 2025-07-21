import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "org.bidon.convention-plugins"

// Configure the build-logic plugins to target JDK 17
// This matches the JDK used to build the project, and is not related to what is running on device.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

dependencies {
    compileOnly(kotlin("gradle-plugin"))
    compileOnly("com.android.tools.build:gradle:8.7.3")
}

gradlePlugin {
    plugins {
        register("coreGradle") {
            id = "core"
            implementationClass = "CoreGradlePlugin"
        }
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
    }
}
