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
// Memory stress harness — opt-in only.
//
// MemoryStressTest builds ~50/100/200/400 MB photo-report PDFs: minutes of runtime and hundreds of
// MB of temporary disk. It lives in the jvmTest source set (same fonts, same helpers) but is excluded
// from the `jvmTest` task, so `check` and CI never touch it. Run it explicitly:
//
//     ./gradlew :composepdf:jvmStressTest                    # default 460m heap
//     ./gradlew :composepdf:jvmStressTest -PstressHeap=640m  # other heap ceilings
//
// The default heap is deliberately between 2x and 3x the largest target file: streaming the
// caller's in-memory photos (P + F) fits, the ByteArray path (P + 2F) does not — which is the point
// of the eager (a_/b_) runs.
//
// The lazy-source ladder (c_) has no P term at all — photos live in files and are pulled one at a
// time — so it runs in a heap smaller than a single rung of the eager ladder. Select it alone
// (the eager runs cannot survive that heap) with `-PstressTests`:
//
//     ./gradlew :composepdf:jvmStressTest -PstressHeap=128m -PstressTests="*MemoryStressTest.c_*"
// ----------------------------------------------------------------------------------------------

val stressTestClass = "io.github.rikoappdev.composepdf.MemoryStressTest"

tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(stressTestClass) }
}

val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<Test>("jvmStressTest") {
    group = "verification"
    description = "Runs the photo-report memory stress harness in a heap-constrained forked JVM (opt-in; not part of check)."
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = jvmTestCompilation.output.allOutputs + jvmTestCompilation.runtimeDependencyFiles
    useJUnit()
    // -PstressTests narrows to individual runs (e.g. only the lazy ladder, which needs a heap the
    // eager runs could never finish in).
    filter { includeTestsMatching(providers.gradleProperty("stressTests").getOrElse(stressTestClass)) }
    maxHeapSize = providers.gradleProperty("stressHeap").getOrElse("460m")
    providers.gradleProperty("stressTargetsMb").orNull?.let { systemProperty("stress.targetsMb", it) }
    providers.gradleProperty("stressLazyTargetsMb").orNull?.let { systemProperty("stress.lazyTargetsMb", it) }
    providers.gradleProperty("stressIdentityMb").orNull?.let { systemProperty("stress.identityMb", it) }
    providers.gradleProperty("stressCeiling").orNull?.let { systemProperty("stress.ceiling", it) }
    // Measurements, not caching: always re-run and always show the harness's stdout table.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
