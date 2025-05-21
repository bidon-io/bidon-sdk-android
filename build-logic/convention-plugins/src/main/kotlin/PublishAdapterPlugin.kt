import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import java.util.Properties

class PublishAdapterPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        plugins.apply("maven-publish")
        plugins.apply("signing")

        val githubProperties = Properties().apply {
            val githubCredentialFile = rootProject.file("github.properties")
            if (githubCredentialFile.exists()) {
                load(githubCredentialFile.inputStream())
            }
        }

        afterEvaluate {
            val dokkaJar = tasks.register("dokkaJar", Jar::class.java) {
                group = "documentation"
                dependsOn("dokkaJavadoc")
                archiveClassifier.set("javadoc")
                include(javadoc.ClassesList.javaDocsAllowList)
                from("$buildDir/dokka/javadoc")
            }

            val sourcesJar = tasks.register("sourcesJar", Jar::class.java) {
                group = "documentation"
                archiveClassifier.set("sources")
                include(javadoc.ClassesList.javaDocsAllowList)
//                from(android.sourceSets.getByName("main").java.srcDirs)
            }

//            publishing {
//                val artifactId = project.getArtifactId()
//                val versionName = project.getVersionName()
//
//                repositories {
//                    maven {
//                        name = "GitHubPackages"
//                        url = uri("https://maven.pkg.github.com/bidon-io/bidon-sdk-android")
//                        credentials {
//                            username = githubProperties["gpr.usr"] as? String ?: System.getenv("GPR_USER")
//                            password = githubProperties["gpr.key"] as? String ?: System.getenv("GPR_TOKEN")
//                        }
//                    }
//
//                    project.findProperty("repo")?.let { repo ->
//                        maven {
//                            name = "Bidon"
//                            url = uri("https://artifactory.bidon.org/artifactory/$repo")
//                            credentials {
//                                username = project.findProperty("uname") as? String
//                                password = project.findProperty("upassword") as? String
//                            }
//                        }
//                    }
//
//                    maven {
//                        name = "MavenCentral"
//                        url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
//                        credentials {
//                            username = project.findProperty("mavenUser") as? String
//                            password = project.findProperty("mavenPassword") as? String
//                        }
//                    }
//                }
//
//                publications {
//                    register("gpr", MavenPublication::class.java) {
//                        afterEvaluate {
//                            from(components.findByName("productionRelease"))
//                        }
//                        artifact(dokkaJar.get())
//                        if (!plugins.hasPlugin("com.android.library")) {
//                            artifact(sourcesJar.get())
//                        }
//
//                        pom {
//                            groupId = "org.bidon"
//                            this.artifactId = artifactId
//                            version = versionName
//                            name.set(project.name)
//                            description.set(project.description)
//                            url.set("https://bidon.org/")
//                            scm {
//                                url.set("https://github.com/bidon-io/bidon_sdk_android.git")
//                                connection.set("scm:git:github.com/bidon-io/bidon_sdk_android.git")
//                                developerConnection.set("scm:git:ssh://github.com/bidon-io/bidon_sdk_android.git")
//                            }
//                            licenses {
//                                license {
//                                    name.set("The Apache License, Version 2.0")
//                                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
//                                }
//                            }
//                            organization {
//                                name.set("Bidon")
//                                url.set("https://bidon.org/")
//                            }
//                            developers {
//                                developer {
//                                    id.set("bidon")
//                                    name.set("Bidon Dev Team")
//                                    email.set("dev@bidon.org")
//                                    url.set("https://bidon.org/")
//                                }
//                            }
//                        }
//                    }
//                }
//
//                signing {
//                    isRequired = gradle.taskGraph.hasTask("publishGprPublicationToMavenCentralRepository")
//                    val keyId: String? by project
//                    val key: String? by project
//                    val password: String? by project
//                    useInMemoryPgpKeys(keyId, key, password)
//                    sign(publishing.publications)
//                }
//            }
        }
    }
}