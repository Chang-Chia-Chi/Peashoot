package dev.peashoot.app

import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * What the Compose compiler plugin writes on every public class of this module, the reducer's
 * included, whether or not the class has anything to do with Compose. It is not in the source and
 * nothing loads it, so it is the one Compose name the rule below lets through.
 */
private const val STABILITY_MARKER = "androidx.compose.runtime.internal.StabilityInferred"

/**
 * The farm reducer shares a module with the window, so Gradle cannot keep Compose out of it the way
 * it keeps it out of `core`: only this can. The reducer is tested without a display and replayed
 * from recorded feeds, and one `mutableStateOf` inside it would end both.
 */
@AnalyzeClasses(
    packages = ["dev.peashoot.app.farm"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
object ArchitectureTest {
    @ArchTest
    @JvmField
    val farmHasNoCompose: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("dev.peashoot.app.farm..")
            .should()
            .dependOnClassesThat(
                resideInAnyPackage("androidx.compose..", "org.jetbrains.compose..")
                    .and(not(name(STABILITY_MARKER)))
            )
}
