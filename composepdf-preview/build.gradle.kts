import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
