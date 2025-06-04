import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import ext.Dependencies.Kotlin.kotlinCompilerExtensionVersion as kotlinCompose

class ComposePlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        extensions.configure<ApplicationExtension> {
            buildFeatures {
                compose = true
            }
            composeOptions {
                kotlinCompilerExtensionVersion = kotlinCompose
            }
        }
    }
}