import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.dependencies

class AdapterGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("common")

        fun addCoreConstraint(configurationName: String) {
            dependencies {
                add(configurationName, "org.bidon:bidon-sdk") {
                    version {
                        require("[0.11.0,1.0.0)")
                    }
                }
            }
        }

        pluginManager.withPlugin("com.android.library") {
            addCoreConstraint("api")
        }

        pluginManager.withPlugin("java-library") {
            addCoreConstraint("api")
        }

        configurations.matching { it.name == "api" }.all {
            addCoreConstraint("api")
        }
    }
}