import ext.Versions.PublishedAdapters.SupportedCoreRange
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.dependencies

class AdapterGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("common")

        // Add implementation dependency on core SDK with strictly version constraints,
        // it will applied for adapters during publishing
        dependencies {
            add("implementation", "org.bidon:bidon-sdk") {
                version {
                    strictly("[${SupportedCoreRange.Min},${SupportedCoreRange.Max})")
                }
                because("${project.name} adapter is only compatible with bidon-sdk versions ${SupportedCoreRange.Min} to ${SupportedCoreRange.Max}. Please use a compatible version of bidon-sdk.")
            }
        }

        // Using local project instead of maven dependency for local development and testing
        configurations.all {
            resolutionStrategy.dependencySubstitution {
                substitute(module("org.bidon:bidon-sdk"))
                    .using(project(":bidon"))
            }
        }
    }
}