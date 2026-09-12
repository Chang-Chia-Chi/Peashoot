// Shared models, rules, fingerprint, event line, surface grammars. No server dependency.
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Nothing is public by accident: proxy and app both consume this module.
configure<KotlinJvmProjectExtension> { explicitApi() }

dependencies { "testImplementation"(libs.archunit.junit5) }
