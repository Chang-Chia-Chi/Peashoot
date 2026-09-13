// Shared models, rules, fingerprint, event line, surface grammars. No server dependency.
dependencies {
    "api"(libs.kotlinx.coroutines.core) // Flow<Frame> is part of the interceptor contract
    "testImplementation"(libs.archunit.junit5)
}
