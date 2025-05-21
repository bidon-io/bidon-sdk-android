import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    id("com.android.lint") version "8.7.3" apply false
}

group = "org.bidon.convention-plugins"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        register("androidApplicationCompose") {
            id = "application-compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("commonGradle") {
            id = "common"
            implementationClass = "CommonGradlePlugin"
        }
        register("publishAdapterGradle") {
            id = "publish-adapter"
            implementationClass = "PublishAdapterPlugin"
        }
        register("kotlin20") {
            id = "kotlin-2-0"
            implementationClass = "Kotlin20Plugin"
        }
    }
}

//subprojects {
//    apply(plugin = "org.jlleitschuh.gradle.ktlint")
//    apply(plugin = "org.jetbrains.dokka")

//    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
//        debug.set(true)
//        additionalEditorconfigFile.set(file(".editorconfig"))
//        disabledRules.set(setOf("final-newline", "no-wildcard-imports", "max-line-length"))
//    }
//}

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
    implementation(kotlin("gradle-plugin"))
    compileOnly("com.android.tools.build:gradle:8.7.3")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    compileOnly("com.android.tools:common:31.9.0")
    compileOnly("org.jetbrains.kotlin:compose-compiler-gradle-plugin:1.9.10")
}