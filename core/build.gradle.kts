// Shared models, rules, fingerprint, event line, surface grammars. No server dependency.
dependencies {
    // Flow<Frame> and Headers are part of the interceptor contract. ktor-http is not the server.
    "api"(libs.kotlinx.coroutines.core)
    "api"(libs.ktor.http)
    "testImplementation"(libs.archunit.junit5)
}
