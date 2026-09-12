// Ktor server, interceptors, SQLite store, control API. Produces peashoot.jar.
plugins { application }

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    runtimeOnly(libs.slf4j.simple)
}

application { mainClass.set("dev.peashoot.proxy.MainKt") }
