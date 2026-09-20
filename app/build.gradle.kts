import dev.detekt.gradle.extensions.DetektExtension
import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask

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

/**
 * The proxy a packaged app starts, staged where jpackage will carry it. Everything under `common/`
 * is copied in beside the app, and the app reads that directory back through the
 * `compose.application.resources.dir` system property — the first place `OwnedProxy` looks.
 *
 * The proxy's jar is named by path and not by task: subprojects are configured in alphabetical
 * order, so `:proxy`'s tasks do not exist yet while this one is being declared.
 */
val bundleProxy =
    tasks.register<Sync>("bundleProxy") {
        description = "Stages peashoot.jar as an app resource, so an installed app carries one."
        dependsOn(":proxy:fatJar")
        from(project(":proxy").layout.buildDirectory.file("libs/peashoot.jar"))
        into(layout.buildDirectory.dir("appResources/common/proxy"))
    }

compose.desktop {
    application {
        mainClass = "dev.peashoot.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Peashoot"
            // The same version the jar carries, with any suffix taken off: jpackage accepts a
            // number with a non-zero major and nothing else, so the untagged `1.0.0-dev` installs
            // as `1.0.0` while the jar inside it still says which build it came from. A tag build
            // passes `-Ppeashoot.version=<tag without v>` and both say the tag.
            packageVersion = version.toString().substringBefore('-')
            description = "Record, replay, and watch LLM agent traffic"
            vendor = "Peashoot"
            copyright = "Copyright 2026 The Peashoot authors"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            // The runtime image the app ships is also the JVM it starts the proxy on, so it has
            // to hold what the proxy needs as well as what the window does. All but the last are
            //   jdeps --multi-release 21 --ignore-missing-deps --print-module-deps peashoot.jar
            // (java.sql for SQLite, jdk.unsupported for Netty's Unsafe, java.naming for HikariCP's
            // JNDI lookup and Netty's `LdapName` in certificate hostnames), together with
            // `./gradlew :app:suggestRuntimeModules` for Compose and Skiko, which asks for
            // java.instrument, java.management and jdk.unsupported. java.base is in every image,
            // and jlink adds what these require: java.logging and java.xml come with java.sql.
            //
            // jdk.crypto.ec is the one jdeps cannot see, because SunEC is a service and not an
            // import — and it is the whole TLS handshake to the providers. It is an empty
            // placeholder on the JDK this was built with (22: `sun.security.ec` is in java.base
            // now), but CI packages on 21, where it may still carry SunEC, and a missing one
            // fails nowhere but on a user's machine.
            //
            // ponytail: a hand-kept list, so re-run both commands when a dependency is added, and
            // suspect this first when an installed app cannot reach a provider. Upgrade:
            // `includeAllModules = true`, which cannot be wrong, for something like 40 MB.
            modules(
                "java.desktop",
                "java.instrument",
                "java.management",
                "java.naming",
                "java.sql",
                "jdk.jfr",
                "jdk.unsupported",
                "jdk.crypto.ec",
            )
        }
    }
}

// Compose stages the resources for every packaging task through this one, so the jar is in place
// whether the build is making an image, an installer, `runDistributable`, or the minified
// variants of any of them. Inside `afterEvaluate`, and named rather than matched: the plugin
// registers this task from its own `afterEvaluate`, so it does not exist while this script runs,
// and a pattern that matched nothing would ship installers with an empty `resources/proxy/` and
// say nothing. Named, a rename breaks the build instead of the installer.
afterEvaluate { tasks.named("prepareAppResources") { dependsOn(bundleProxy) } }

/**
 * The `java` an installed app starts its proxy with. jlink strips a runtime image's own launchers,
 * and Compose keeps that flag to itself — `AbstractJLinkTask.stripNativeCommands`, under a "todo:
 * public DSL" — so the image jpackage ships holds `jvm.dll` and nothing to start it with. An
 * installed app has no JDK to fall back on, so without this it can package a proxy it cannot run.
 * The launcher comes from the JDK jlink itself was run from, which is the runtime it produced.
 *
 * ponytail: one file copied into another task's output, and only `java`. Upgrade: whatever the
 * plugin offers when that DSL stops being a to-do.
 */
tasks.withType<AbstractJLinkTask>().configureEach {
    val name = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    val launcher = javaHome.map { File(it, "bin").resolve(name) }
    val image = destinationDir
    doLast {
        val target = image.get().asFile.resolve("bin").resolve(name)
        launcher.get().copyTo(target, overwrite = true)
        // Executable by everyone, not only its owner: `copyTo` writes 0644 and the one-argument
        // `setExecutable` means owner-only, which a .deb installs root-owned — and then the user
        // who runs the app cannot start the runtime it was given.
        target.setExecutable(true, false)
    }
}
