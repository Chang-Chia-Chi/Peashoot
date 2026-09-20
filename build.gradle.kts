import com.ncorti.ktfmt.gradle.KtfmtExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kover)
}

// Catalog accessors resolve against the root project, so capture them before entering subprojects.
val kotlinTest = libs.kotlin.test

// What a build calls itself: the tag without its `v`, passed by .github/workflows/release.yml as
// `-Ppeashoot.version=1.2.3`. Untagged builds keep 1.0.0, because jpackage refuses a 0 major on
// Windows and an installer that cannot be built is worse than one that overstates its age.
val peashootVersion = providers.gradleProperty("peashoot.version").getOrElse("1.0.0")

// One coverage report for the whole build: `./gradlew koverHtmlReport` -> build/kover/html.
dependencies {
    kover(project(":core"))
    kover(project(":proxy"))
    kover(project(":app"))
}

// One convention for every module: the compiler is the linter, ktfmt is the only style, detekt
// runs its default rules with no baseline, tests run on JUnit 5 under `check`.
// See docs/research/code-quality-environment.md, Tiers 1 and 2.
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.ncorti.ktfmt.gradle")
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    group = "dev.peashoot"
    version = peashootVersion

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
    // Still detekt's defaults, still no baseline: the file names one option on one rule, and
    // `buildUponDefaultConfig` keeps every other default in force. See config/detekt/detekt.yml.
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    dependencies { "testImplementation"(kotlinTest) }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // CI keeps only the console, so a failing assertion must say what it saw there.
        testLogging { exceptionFormat = TestExceptionFormat.FULL }
    }
    tasks.named("check") { dependsOn("ktfmtCheck") }
}
