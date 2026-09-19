import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Compose for Desktop app: control client, farm reducer, renderer. It speaks the control API over
// HTTP and nothing else; the store is the proxy's, and the app never opens the database file.
plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    "implementation"(project(":core"))
    "implementation"(compose.desktop.currentOs)
    "implementation"(libs.ktor.client.core)
    "implementation"(libs.ktor.client.cio)
    // A real proxy on a real socket is the only honest test of a feed that must survive one
    // stopping and starting again. Tests only: nothing in `main` knows the proxy is Kotlin.
    "testImplementation"(project(":proxy"))
    // The reducer's package lives beside the window, so only a rule can keep Compose out of it.
    "testImplementation"(libs.archunit.junit5)
    "testRuntimeOnly"(libs.slf4j.simple)
    // The Compose rule set, here alone because this is the only module with a composable in it.
    "detektPlugins"(libs.compose.rules.detekt)
}

// Added to the root config rather than replacing it: the other modules have no Compose rules on
// their detekt classpath, and a `Compose:` block they cannot resolve would fail their own run.
extensions.configure<DetektExtension> { config.from(rootProject.file("config/detekt/compose.yml")) }

compose.desktop {
    application {
        mainClass = "dev.peashoot.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Peashoot"
            // jpackage refuses a 0 major on Windows, and the installers are not released yet.
            packageVersion = "1.0.0"
            description = "Record, replay, and watch LLM agent traffic"
            vendor = "Peashoot"
        }
    }
}
