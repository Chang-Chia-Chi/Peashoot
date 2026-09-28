package dev.peashoot.app.farm

import dev.peashoot.core.Mode
import dev.peashoot.core.text
import kotlinx.serialization.json.JsonObject

/** What one turn that really ended earns. */
internal const val COINS_A_TURN = 1

/** What a crop earns the first time it ripens: a harvest is worth more than a turn. */
internal const val COINS_A_HARVEST = 3

/** The lifetime coins between level 1 and level 2; each gap after it is this much wider. */
private const val LEVEL_STEP = 10

/**
 * What the farm has earned from the agents' work, and what of it is still to spend. [earned] only
 * ever grows and is what the level is read from, so buying something never takes a level away.
 */
data class Purse(val coins: Int = 0, val earned: Int = 0) {
    /** Level 1 from the start, level 2 at 10 lifetime coins, 3 at 30, 4 at 60, 5 at 100. */
    val level: Int
        get() = generateSequence(1) { it + 1 }.first { floorOf(it + 1) > earned }

    fun earning(amount: Int): Purse = copy(coins = coins + amount, earned = earned + amount)
}

/** The lifetime coins a farm needs to reach [level]. */
fun floorOf(level: Int): Int = LEVEL_STEP * level * (level - 1) / 2

/**
 * What the line that took the farm from [before] to [after] earned: a coin for each turn the bin
 * shipped and more for each crop that ripened. A replayed line earns nothing, because serving a
 * cassette is the proxy remembering work rather than an agent doing it.
 *
 * ponytail: "the first time it ripens" is the first time this window saw it ripen. A crop is a path
 * in [FarmState.fields], which starts empty with every window, so a file ripened again after a
 * restart pays again; it takes four edits to ripen, so that is work too. Upgrade: keep ripened
 * paths in the save, if paying twice for one file ever matters.
 */
internal fun earned(before: FarmState, after: FarmState, event: JsonObject): Int {
    if (event["mode"].text()?.let(Mode::of) == Mode.REPLAY) return 0
    val turns = after.bin.produce - before.bin.produce
    val harvests = after.ripe() - before.ripe()
    return turns * COINS_A_TURN + harvests * COINS_A_HARVEST
}

private fun FarmState.ripe(): Int =
    fields.values.sumOf { field -> field.crops.values.count { it.growth == Growth.RIPE } }
