import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    android {
        namespace = "io.github.rikoappdev.composepdf"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    // Declared so the common-metadata compile enforces that commonMain uses only common
    // (non-JVM) APIs — the guarantee that the engine is identical on iOS. Native binaries
    // for these targets can only be produced on macOS/CI.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.pdfbox)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Publishing — plain maven-publish + signing: GitHub Packages + Maven Central (OSSRH staging API),
// in-memory PGP signing from env, signing required only for Maven Central tasks so local
// builds/tests never need keys.
// ----------------------------------------------------------------------------------------------

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        artifactId = if (name == "kotlinMultiplatform") "compose-pdf" else "compose-pdf-$name"
        pom {
            name.set(providers.gradleProperty("POM_NAME"))
            description.set(providers.gradleProperty("POM_DESCRIPTION"))
            url.set(providers.gradleProperty("POM_URL"))
            licenses {
                license {
                    name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                    url.set(providers.gradleProperty("POM_LICENSE_URL"))
                }
            }
            developers {
                developer {
                    id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                    name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                    url.set(providers.gradleProperty("POM_DEVELOPER_URL"))
                }
            }
            scm {
                url.set(providers.gradleProperty("POM_SCM_URL"))
                connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
            }
        }
    }
    repositories {
        // Stage all signed artifacts into a local Maven layout; the CI workflow zips this and uploads
        // it to the Central Portal Publisher API (the native path — the legacy OSSRH staging API +
        // finalize step published to the Portal but did not reliably sync to the Maven Central CDN).
        maven {
            name = "MavenCentral"
            url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
        }
    }
}

signing {
    val signingKeyId = findProperty("signingKeyId") as String? ?: System.getenv("SIGNING_KEY_ID")
    val signingKey = findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD")
    if (signingKeyId != null && signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications)
    }
}

// Only require signing for Maven Central publication tasks (keeps local builds key-free).
gradle.taskGraph.whenReady {
    signing.isRequired = allTasks.any { it.name.contains("MavenCentral") }
}
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
