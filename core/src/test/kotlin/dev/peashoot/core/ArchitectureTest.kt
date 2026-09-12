package dev.peashoot.core

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * `core` is shared by the proxy and the app: no server, no UI. Gradle declares neither dependency;
 * this fails the build if someone adds one.
 */
@AnalyzeClasses(
    packages = ["dev.peashoot.core"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
object ArchitectureTest {
    @ArchTest
    @JvmField
    val coreHasNoServerOrUi: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.peashoot.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.ktor.server..", "androidx.compose..", "org.jetbrains.compose..")
            .allowEmptyShould(true) // core has no production classes yet
}
