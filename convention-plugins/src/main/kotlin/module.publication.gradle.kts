import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.`maven-publish`

plugins {
    `maven-publish`
    signing
}

publishing {
    // Configure all publications
    publications.withType<MavenPublication> {
        // Stub javadoc.jar artifact
        artifact(tasks.register("${name}JavadocJar", Jar::class) {
            archiveClassifier.set("javadoc")
            archiveAppendix.set(this@withType.name)
        })

        // Provide artifacts information required by Maven Central
        pom {
            name.set("KonTinuity")
            description.set("Provides fully-fledged multishot delimitied continuations in Kotlin with Coroutines")
            url.set("https://github.com/kyay10/kontinuity")

            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://opensource.org/license/apache-2-0")
                }
            }
            developers {
                developer {
                    id.set("kyay10")
                    name.set("Youssef Shoaib")
                }
            }
            scm {
                url.set("https://github.com/kyay10/kontinuity")
            }
        }
    }
}

signing {
    if (project.hasProperty("signing.gnupg.keyName")) {
        useGpgCmd()
        sign(publishing.publications)
    }
}
