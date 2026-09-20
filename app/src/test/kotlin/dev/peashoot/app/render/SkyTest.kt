package dev.peashoot.app.render

import dev.peashoot.app.farm.Season
import dev.peashoot.app.farm.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which state of `weather.jsonl` is which sky; see the fixture README for the line-by-line. */
private const val RAINY = 1
private const val CLEARED = 3
private const val STRUCK = 5
private const val STORMY = 8

/** Which state of `bin.jsonl` is under a replayed route, and which is back on a live one. */
private const val REPLAYING = 6
private const val RECORDING = 8

/**
 * What the sky is, from the farm alone: issue #21's first and fourth criteria at the renderer's own
 * seam. The reducer's half — that a 529 is a storm and a `mode` of replay is night — is
 * [dev.peashoot.app.farm.SignalsTest]'s; this is the other half, that the farm's weather really
 * reaches the draw phase as something to draw.
 */
class SkyTest {
    @Test
    fun `a turn that read nothing from the cache leaves a clear sky`() {
        val sky = skyOf(replay("weather.jsonl")[CLEARED], flash = null)
        assertEquals(Sky(shade = 0f, rain = 0f, flash = 0f), sky)
    }

    @Test
    fun `a cache read is rain, and only rain`() {
        val sky = skyOf(replay("weather.jsonl")[RAINY], flash = null)
        assertTrue(sky.rain > 0f, "a cache read left the sky dry")
        assertEquals(0f, sky.shade, "rain is not a darker farm, only a wetter one")
    }

    @Test
    fun `a 529 is a storm, which is heavier rain and a darker farm`() {
        val storm = skyOf(replay("weather.jsonl")[STORMY], flash = null)
        val rain = skyOf(replay("weather.jsonl")[RAINY], flash = null)
        assertTrue(storm.rain > rain.rain, "a storm is no wetter than rain")
        assertTrue(storm.shade > 0f, "a storm did not darken the farm")
    }

    @Test
    fun `a dropped stream keeps the storm's sky, and a live flash is in it`() {
        val struck = replay("weather.jsonl")[STRUCK]
        assertTrue(skyOf(struck, flash = null).shade > 0f, "lightning left the farm bright")
        assertEquals(0f, skyOf(struck, flash = null).flash, "a flash nothing raised is not live")
        assertEquals(1f, skyOf(struck, Effect(EffectKind.FLASH)).flash)
    }

    @Test
    fun `a flash fades over an effect's life rather than switching off`() {
        val struck = replay("weather.jsonl")[STRUCK]
        val half = skyOf(struck, Effect(EffectKind.FLASH, age = EFFECT_SECONDS / 2)).flash
        assertEquals(0.5f, half, "a flash that does not fade reads as a broken screen")
    }

    @Test
    fun `a route switched to replay turns the farm to night, and back when it is live again`() {
        val states = replay("bin.jsonl")
        assertTrue(states[REPLAYING].night, "the fixture no longer replays at that line")
        assertTrue(skyOf(states[REPLAYING], flash = null).shade > 0f, "replay is not night")
        assertEquals(
            0f,
            skyOf(states[RECORDING], flash = null).shade,
            "a route back on a live upstream is daylight again",
        )
    }

    @Test
    fun `a storm at night is darker than either on its own`() {
        val storm = replay("weather.jsonl")[STORMY]
        val shade = skyOf(storm, flash = null).shade
        val atNight = skyOf(storm.copy(night = true), flash = null).shade
        assertTrue(atNight > shade, "a storm at night is no darker than one at noon")
        assertTrue(atNight < 1f, "a farm nobody can see through is not a farm")
    }

    @Test
    fun `spring is the palette the farm was drawn in, and the other three tint it`() {
        assertNull(seasonTint(Season.SPRING), "spring must not tint, or every still changes")
        val tinted = listOf(Season.SUMMER, Season.AUTUMN, Season.WINTER).map(::seasonTint)
        tinted.forEach { assertNotNull(it) }
        assertEquals(tinted.size, tinted.distinct().size, "two seasons share one palette")
    }
}
