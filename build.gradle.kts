import com.ncorti.ktfmt.gradle.KtfmtExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktfmt) apply false
}

// Catalog accessors resolve against the root project, so capture them before entering subprojects.
val kotlinTest = libs.kotlin.test

// One convention for every module: the compiler is the linter, ktfmt is the only style,
// tests run on JUnit 5 under `check`. See docs/research/code-quality-environment.md, Tier 1.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.ncorti.ktfmt.gradle")

    group = "dev.peashoot"

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
            extraWarnings.set(true)
        }
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    extensions.configure<KtfmtExtension> { kotlinLangStyle() }

    dependencies { "testImplementation"(kotlinTest) }

    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.named("check") { dependsOn("ktfmtCheck") }
}
