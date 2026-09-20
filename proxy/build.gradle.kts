// Ktor server, interceptors, SQLite store, control API. Produces peashoot.jar.
plugins { application }

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json) // the store's JSON columns, tree API only
    implementation(libs.hikaricp)
    implementation(libs.jdbi3.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.tomlj)
    runtimeOnly(libs.slf4j.simple)
}

// The fake upstream replays the same captured provider responses the core parser tests use.
sourceSets.test { resources.srcDir(rootProject.file("core/src/test/resources")) }

application { mainClass.set("dev.peashoot.proxy.MainKt") }

/**
 * The one file a release ships and a packaged app starts: `proxy/build/libs/peashoot.jar`. Part of
 * `assemble`, so a plain `./gradlew build` leaves one exactly where `OwnedProxy` looks for it.
 *
 * ponytail: a plain `Jar`, not the Shadow plugin. Nothing needs merging here — no two jars on this
 * runtime classpath ship the same `META-INF/services` entry, and none is signed — and the
 * `filesMatching` below turns the day that changes into a build failure rather than a service file
 * silently dropped. Upgrade: `com.gradleup.shadow`, which merges them, on that day.
 */
val fatJar =
    tasks.register<Jar>("fatJar") {
        description = "Packs the proxy and its dependencies into one runnable peashoot.jar."
        group = "build"
        archiveFileName.set("peashoot.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest {
            attributes(
                "Main-Class" to application.mainClass.get(),
                // What `GET /_peashoot/v1/health` answers with; without it the answer is "dev".
                "Implementation-Title" to "Peashoot",
                "Implementation-Version" to version,
            )
        }
        from(sourceSets.main.get().output)
        from(configurations.runtimeClasspath.map { classpath -> classpath.map(::zipTree) }) {
            // A dependency's signature cannot match the jar its classes are copied into, and its
            // `module-info` describes a module this jar is not.
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
            filesMatching("META-INF/services/*") { duplicatesStrategy = DuplicatesStrategy.FAIL }
        }
    }

tasks.named("assemble") { dependsOn(fatJar) }

// Tests read the real log output back from this file to prove what is, and is not, logged.
tasks.test {
    val appLog = layout.buildDirectory.file("test-app.log").get().asFile
    systemProperty("org.slf4j.simpleLogger.logFile", appLog.path)
    systemProperty("peashoot.test.appLog", appLog.path)
    doFirst { appLog.delete() }
}
