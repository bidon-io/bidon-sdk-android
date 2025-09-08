import ext.Versions.PublishedAdapters.SupportedCoreRange
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.dependencies

class AdapterGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("common")

        // Add API dependency on core SDK with version constraints
        dependencies {
            add("api", "org.bidon:bidon-sdk") {
                version {
                    strictly("[${SupportedCoreRange.Min},${SupportedCoreRange.Min})")
                }
            }
        }
    }
}