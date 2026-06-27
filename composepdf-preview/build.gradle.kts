import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    `maven-publish`
    signing
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    android {
        namespace = "io.github.rikoappdev.composepdf.preview"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    // Android + JVM only: these are the platforms with a design-time @Preview runtime (Android Studio
    // and Compose Desktop). The core `composepdf` engine remains Android + iOS + JVM; iOS simply has
    // no IDE preview pane, so the preview tooling doesn't target it.

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":composepdf"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
            }
        }
        // @Preview example functions live here: Android Studio renders androidMain @Preview natively,
        // and the preview tooling artifact has no iOS variant so it must stay out of commonMain.
        val androidMain by getting {
            dependencies {
                implementation(compose.components.uiToolingPreview)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------
// Publishing — mirrors :composepdf (plain maven-publish + signing). The preview artifact ships as
// `compose-pdf-preview` (+ per-target variants). Signed artifacts stage into the SAME local Maven
// layout as :composepdf (`composepdf/build/staging-deploy`), so the existing release workflow zips
// and uploads both modules' artifacts in one Central Portal bundle with no workflow change.
// ----------------------------------------------------------------------------------------------

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        artifactId = if (name == "kotlinMultiplatform") "compose-pdf-preview" else "compose-pdf-preview-$name"
        pom {
            name.set("compose-pdf-preview")
            description.set("Design-time @Preview bridge for compose-pdf: render a pdfDocument spec live on a Compose Canvas.")
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
        maven {
            name = "MavenCentral"
            url = rootProject.layout.projectDirectory.dir("composepdf/build/staging-deploy").asFile.toURI()
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
