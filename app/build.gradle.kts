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
    "testRuntimeOnly"(libs.slf4j.simple)
}

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
