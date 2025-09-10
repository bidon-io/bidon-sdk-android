import com.android.build.gradle.LibraryExtension
import ext.Versions.PublishedAdapters.SupportedCoreRange
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.dependencies
import java.io.File

class AdapterGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        pluginManager.apply("common")

        // Add API dependency on core SDK with strictly version constraints,
        // it will applied for adapters during publishing
        dependencies {
            add("api", "org.bidon:bidon-sdk") {
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
        registerDeprecatedCodeCheckTask()
    }

    private fun Project.registerDeprecatedCodeCheckTask() {
        tasks.register("checkDeprecatedCode") {
            group = "verification"
            description = "Checks for deprecated code in adapter source files"

            doLast {
                var deprecatedFound = false
                val deprecatedFiles = mutableListOf<String>()

                val android = extensions.findByType(LibraryExtension::class.java)
                val sourceDirs = android?.sourceSets?.getByName("main")?.java?.srcDirs
                    ?: setOf(
                        file("src/main/java"),
                        file("src/main/kotlin")
                    )

                sourceDirs.forEach { srcDir ->
                    if (srcDir.exists()) {
                        srcDir.walkTopDown()
                            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                            .forEach { file ->
                                if (containsDeprecatedAnnotation(file)) {
                                    deprecatedFound = true
                                    deprecatedFiles.add(file.relativeTo(projectDir).path)
                                }
                            }
                    }
                }

                if (deprecatedFound) {
                    val errorMessage = buildString {
                        appendLine("❌ Deprecated code found in adapter '${project.name}':")
                        deprecatedFiles.forEach { file ->
                            appendLine("  - $file")
                        }
                        appendLine()
                        appendLine("Adapters must not contain deprecated code.")
                        appendLine("Please remove all @Deprecated annotations and deprecated code before proceeding.")
                    }
                    throw GradleException(errorMessage)
                } else {
                    logger.lifecycle("✅ No deprecated code found in adapter '${project.name}'")
                }
            }
        }

        tasks.named("check") {
            dependsOn("checkDeprecatedCode")
        }
    }

    private fun containsDeprecatedAnnotation(file: File): Boolean {
        return try {
            file.readText().contains("@Deprecated")
        } catch (exception: Exception) {
            false
        }
    }
}