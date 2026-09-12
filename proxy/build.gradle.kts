// Ktor server, interceptors, SQLite store, control API. Produces peashoot.jar.
plugins { application }

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(project(":core"))
}

// The fake upstream replays the same captured provider responses the core parser tests use.
sourceSets.test { resources.srcDir(rootProject.file("core/src/test/resources")) }

application { mainClass.set("dev.peashoot.proxy.MainKt") }

// Tests read the real log output back from this file to prove what is, and is not, logged.
tasks.test {
    val appLog = layout.buildDirectory.file("test-app.log").get().asFile
    systemProperty("org.slf4j.simpleLogger.logFile", appLog.path)
    systemProperty("peashoot.test.appLog", appLog.path)
    doFirst { appLog.delete() }
}
