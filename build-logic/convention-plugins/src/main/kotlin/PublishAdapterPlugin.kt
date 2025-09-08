import com.android.build.gradle.LibraryExtension
import ext.Versions.PublishedAdapters.SupportedCoreRange
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension
import java.util.Properties

class PublishAdapterPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val publishAdapterExtension =
            project.extensions.create("publishAdapter", PublishAdapterExtension::class.java)

        plugins.apply("maven-publish")
        plugins.apply("signing")

        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("productionRelease") {
                    withSourcesJar()
                    withJavadocJar()
                }
            }
        }

        project.afterEvaluate {
            extensions.configure(PublishingExtension::class.java) {
                val getGroupId = publishAdapterExtension.groupId.getOrElse(defaultGroupId)
                val getArtifactId = publishAdapterExtension.artifactId.orNull
                val getVersionName = publishAdapterExtension.versionName.orNull
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/bidon-io/bidon-sdk-android")
                        credentials {
                            val githubProperties = Properties().apply {
                                val githubCredentialFile = rootProject.file("github.properties")
                                if (githubCredentialFile.exists()) {
                                    load(githubCredentialFile.inputStream())
                                }
                            }
                            username =
                                githubProperties["gpr.usr"] as? String ?: System.getenv("GPR_USER")
                            password =
                                githubProperties["gpr.key"] as? String ?: System.getenv("GPR_TOKEN")
                        }
                    }

                    maven {
                        name = "Bidon"
                        project.findProperty("repo")?.let { repo ->
                            url = uri("https://artifactory.bidon.org/artifactory/$repo")
                            println("Artifactory repo: $url. $getArtifactId, $getVersionName")
                            credentials {
                                project.findProperty("uname")?.let { uname ->
                                    username = uname as String
                                    println("Artifactory username: $uname")
                                }
                                project.findProperty("upassword")?.let { upassword ->
                                    password = upassword as String
                                    println("Artifactory password: $upassword")
                                }
                            }
                        }
                    }

                    maven {
                        name = "MavenCentral"
                        url =
                            uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                        credentials {
                            username = project.findProperty("mavenUser") as? String
                            password = project.findProperty("mavenPassword") as? String
                        }
                    }
                }

                publications {
                    create("gpr", MavenPublication::class.java) {
                        from(components.getByName("productionRelease"))
                        pom {
                            groupId = getGroupId
                            artifactId = getArtifactId
                            version = getVersionName

                            name.set(project.name)
                            description.set(project.description)
                            url.set("https://bidon.org/")
                            scm {
                                url.set("https://github.com/bidon-io/bidon_sdk_android.git")
                                connection.set("scm:git:github.com/bidon-io/bidon_sdk_android.git")
                                developerConnection.set("scm:git:ssh://github.com/bidon-io/bidon_sdk_android.git")
                            }
                            licenses {
                                license {
                                    name.set("The Apache License, Version 2.0")
                                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                                }
                            }
                            organization {
                                name.set("Bidon")
                                url.set("https://bidon.org/")
                            }
                            developers {
                                developer {
                                    id.set("bidon")
                                    name.set("Bidon Dev Team")
                                    email.set("dev@bidon.org")
                                    url.set("https://bidon.org/")
                                }
                            }

                            // Add dependency management to enforce version constraints
                            withXml {
                                val root = asNode()
                                // Find existing dependencies node or create new one
                                val dependenciesNodeList = root.get("dependencies") as groovy.util.NodeList
                                val dependenciesNode = if (dependenciesNodeList.isNotEmpty()) {
                                    dependenciesNodeList[0] as groovy.util.Node
                                } else {
                                    root.appendNode("dependencies")
                                }

                                // Add bidon-sdk dependency with version constraint
                                val dependencyNode = dependenciesNode.appendNode("dependency")
                                dependencyNode.appendNode("groupId", "org.bidon")
                                dependencyNode.appendNode("artifactId", "bidon-sdk")
                                dependencyNode.appendNode("version", "[${SupportedCoreRange.Min},${SupportedCoreRange.Max})")
                                dependencyNode.appendNode("scope", "compile")
                            }
                        }
                    }
                }
            }

            extensions.configure(SigningExtension::class.java) {
                isRequired =
                    gradle.taskGraph.hasTask("publishGprPublicationToMavenCentralRepository")
                val keyId: String? = project.findProperty("signing.keyId") as? String
                val key: String? = project.findProperty("signing.key") as? String
                val password: String? = project.findProperty("signing.password") as? String
                useInMemoryPgpKeys(keyId, key, password)
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }
}

abstract class PublishAdapterExtension(project: Project) {
    val groupId: Property<String?> = project.objects.property(String::class.java)
    val artifactId: Property<String?> = project.objects.property(String::class.java)
    val versionName: Property<String?> = project.objects.property(String::class.java)
}

private const val defaultGroupId = "org.bidon"