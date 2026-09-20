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

// The renderer's frame-rate bench (#19), held to the numbers in docs/research/canvas-frame-rate.md.
// Its own JavaExec and not the Compose `run` task, because that task sets `args` and `jvmArgs`
// itself and wipes whatever the build script put there — which is how the spike lost a run.
// Test runtime classpath: the bench ships nowhere. It is not wired into `check`; it opens a window.
//   ./gradlew :app:benchFarm -Pbench.label=gpu -Pbench.out=C:/tmp
//   ./gradlew :app:benchFarm -Pbench.label=software -Pbench.renderapi=SOFTWARE -Pbench.out=C:/tmp
// Both resolved against the project, not the task: inside a task's configuration block `extensions`
// is the task's own, which holds nothing but extra properties.
val composeVersion = libs.versions.compose.asProvider().get()
val benchClasspath =
    extensions.getByType<JavaPluginExtension>().sourceSets.getByName("test").runtimeClasspath

tasks.register<JavaExec>("benchFarm") {
    description = "Measures the farm renderer's frame rate at 200 entities."
    group = "verification"
    classpath = benchClasspath
    mainClass.set("dev.peashoot.app.render.FarmBenchKt")
    systemProperty("bench.compose.version", composeVersion)
    systemProperty(
        "bench.out",
        providers.gradleProperty("bench.out").getOrElse(layout.buildDirectory.get().asFile.path),
    )
    providers.gradleProperty("bench.label").orNull?.let { systemProperty("bench.label", it) }
    // Skiko reads this at class-init time, so it has to be on the JVM from the start.
    providers.gradleProperty("bench.renderapi").orNull?.let {
        systemProperty("skiko.renderApi", it)
    }
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
