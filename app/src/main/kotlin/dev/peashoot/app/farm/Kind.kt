package dev.peashoot.app.farm

/** The levels corn, strawberries and sunflowers unlock at. */
private const val CORN_LEVEL = 3

private const val STRAWBERRY_LEVEL = 5

private const val SUNFLOWER_LEVEL = 7

/**
 * What sort of file a crop is, read from its path alone. Each grows as [kind] from [level] on and
 * as [base], the crop of the category it belongs with, until then (`docs/farm-growth.md`).
 */
private enum class Category(val kind: String, val base: String, val level: Int) {
    CODE("carrot", "carrot", 1),
    TESTS("tomato", "tomato", 1),
    DOCS("turnip", "turnip", 1),
    BUILD("pumpkin", "pumpkin", 1),
    SCRIPTS("corn", "carrot", CORN_LEVEL),
    STYLES("strawberry", "carrot", STRAWBERRY_LEVEL),
    OTHER("sunflower", "carrot", SUNFLOWER_LEVEL),
}

/** Directories whose every file is a test, whatever its name. */
private val TEST_DIRECTORIES = setOf("test", "tests", "__tests__", "spec", "androidtest")

/**
 * A test by its own name: `FooTest.kt`, `foo_test.go`, `test_foo.py`, `foo.spec.ts`. Case counts,
 * so `latest.kt` is not one.
 */
private val TEST_NAME = Regex("""^test_.+|.+(Test|Tests|_test|\.spec|\.test)\.[^.]+$""")

/** Files known by their whole name rather than their extension. */
private val BY_NAME =
    mapOf(
        "readme" to Category.DOCS,
        "license" to Category.DOCS,
        "changelog" to Category.DOCS,
        "dockerfile" to Category.BUILD,
        "makefile" to Category.BUILD,
        "gradlew" to Category.SCRIPTS,
    )

private val BY_EXTENSION =
    listOf(
            Category.DOCS to "md markdown rst txt adoc",
            Category.BUILD to
                "json yaml yml toml xml properties ini cfg conf lock gradle env " +
                    "gitignore gitattributes editorconfig",
            Category.SCRIPTS to "sh bash zsh fish ps1 bat cmd",
            Category.STYLES to "css scss sass less html htm xhtml svg",
            Category.CODE to
                "kt kts java py js mjs cjs ts tsx jsx go rs c h cc cpp hpp cs rb swift " +
                    "scala php gd lua dart m sql vue svelte",
        )
        .flatMap { (category, extensions) -> extensions.split(' ').map { it to category } }
        .toMap()

/**
 * The crop [path] grows as at [level]: carrot for code, tomato for tests, turnip for docs, pumpkin
 * for config and build files; corn for scripts, strawberry for styles and markup, and sunflower for
 * everything else once the level unlocks them, and carrot before. Worked out afresh for every
 * snapshot, so a whole field of scripts turns to corn the moment the level reaches 3.
 */
fun kindOf(path: String, level: Int): String {
    val category = categoryOf(path)
    return if (level >= category.level) category.kind else category.base
}

private fun categoryOf(path: String): Category {
    val name = path.substringAfterLast('/')
    val lower = name.lowercase()
    val directories = path.lowercase().split('/').dropLast(1)
    return when {
        directories.any { it in TEST_DIRECTORIES } || TEST_NAME.matches(name) -> Category.TESTS
        lower.endsWith(".gradle.kts") -> Category.BUILD
        else ->
            BY_NAME[lower.substringBefore('.')]
                ?: BY_EXTENSION[lower.substringAfterLast('.', "")]
                ?: Category.OTHER
    }
}
