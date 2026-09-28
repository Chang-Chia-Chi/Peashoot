package dev.peashoot.app.farm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A crop's kind comes from its file type, and the level unlocks the rarer kinds. */
class KindTest {
    private fun kinds(level: Int, vararg paths: String) = paths.map { kindOf(it, level) }

    @Test
    fun `code, tests, docs and build files each grow their own crop from the start`() {
        assertEquals(
            listOf("carrot", "tomato", "tomato", "tomato", "turnip", "pumpkin", "pumpkin"),
            kinds(
                1,
                "app/src/main/kotlin/Farm.kt",
                "app/src/test/kotlin/FarmTest.kt",
                "web/farm.spec.ts",
                "test_farm.py",
                "docs/farm-growth.md",
                "gradle/libs.versions.toml",
                "app/build.gradle.kts",
            ),
        )
    }

    @Test
    fun `a name that only ends in the letters of test is no test`() {
        assertEquals("carrot", kindOf("src/latest.kt", 1))
    }

    @Test
    fun `scripts, styles and the rest grow as carrots until their level`() {
        val paths = arrayOf("farm/export.sh", "site/farm.css", "farm/art/farmer.png")
        assertEquals(listOf("carrot", "carrot", "carrot"), kinds(2, *paths))
        assertEquals(listOf("corn", "carrot", "carrot"), kinds(3, *paths))
        assertEquals(listOf("corn", "strawberry", "carrot"), kinds(5, *paths))
        assertEquals(listOf("corn", "strawberry", "sunflower"), kinds(7, *paths))
    }

    @Test
    fun `the snapshot carries each crop's kind at the farm's level`() {
        val field = Field("farm", mapOf("farm/export.sh" to Crop("farm/export.sh", Growth.SEED, 0)))
        fun kind(earned: Int) =
            Json.parseToJsonElement(
                    snapshot(FarmState(fields = mapOf("farm" to field), purse = Purse(0, earned)))
                )
                .jsonObject
                .getValue("fields")
                .jsonArray[0]
                .jsonObject
                .getValue("crops")
                .jsonArray[0]
                .jsonObject
                .getValue("kind")
                .jsonPrimitive
                .content
        assertEquals(listOf("carrot", "corn"), listOf(kind(earned = 0), kind(earned = 30)))
    }
}
