package dev.peashoot.app.farm

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The farm as the Godot window is told it: one JSON object per farm, on one line. Kotlin says what
// each thing *is* and which slot it has; the Godot scene says where each slot stands
// (`docs/adr/0003-godot-farm.md`). A slot is an order of first hearing, which is why nothing
// already placed ever moves: a later villager or field only ever takes the next slot.

/** How many looks a villager can have: the two farmers in `farm/art`. */
private const val LOOKS = 2

/**
 * One farm as one line of JSON, with no newline in it: the pipe to the Godot window is read a line
 * at a time, so a line is a whole farm and the latest line is the only one that matters.
 *
 * Villagers and fields are in the order the farm first heard them, and so are a field's crops.
 * `home` is a villager's slot among those with nobody to stand beside, and null for a helper, which
 * stands beside `parent` instead. `queue` is its place in the line at the well, or null. `tended`
 * is the crops, by path, its latest turn planted or grew, which it visits on the way home, and
 * `turns` how many turns it has completed, which tells a new turn's `tended` from the last one's.
 * `purse` is the coins to spend, the lifetime coins, the level they reach, and the lifetime coins
 * this level began at and the next one begins at, which is the whole of a progress bar. `land` is
 * the plots bought beyond the starting island and what the next one costs.
 */
fun snapshot(state: FarmState): String {
    val homes =
        state.villagers.values
            .filter { state.parentOf(it) == null }
            .withIndex()
            .associate { (slot, villager) -> villager.id to slot }
    val queue = state.wellQueue.withIndex().associate { (place, id) -> id to place }
    return buildJsonObject {
        put(
            "villagers",
            buildJsonArray {
                for (villager in state.villagers.values) {
                    add(villagerOf(state, villager, homes[villager.id], queue[villager.id]))
                }
            },
        )
        put(
            "fields",
            buildJsonArray {
                for (field in state.fields.values) {
                    add(
                        buildJsonObject {
                            put("label", field.label)
                            put(
                                "crops",
                                buildJsonArray {
                                    for (crop in field.crops.values) {
                                        add(
                                            buildJsonObject {
                                                put("path", crop.label)
                                                put("growth", crop.growth.ordinal)
                                            }
                                        )
                                    }
                                },
                            )
                        }
                    )
                }
            },
        )
        put("weather", state.weather.name)
        put("night", state.night)
        put("season", state.season.name)
        put("labelsHidden", state.labelsHidden)
        put("bin", binOf(state.bin))
        put("purse", purseOf(state.purse))
        put("land", landOf(state.land))
    }
        .toString()
}

/**
 * A helper whose lines named itself as its own parent has nobody to stand beside, so it is given no
 * parent here rather than a villager chasing its own tail.
 */
private fun villagerOf(state: FarmState, villager: Villager, home: Int?, queue: Int?): JsonObject =
    buildJsonObject {
        put("id", villager.id)
        put("name", villager.name)
        put("look", villager.id.hashCode().mod(LOOKS))
        put("activity", villager.activity.name)
        put("home", home?.let(::JsonPrimitive) ?: JsonNull)
        val parent = state.parentOf(villager)?.id?.takeIf { it != villager.id }
        put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("queue", queue?.let(::JsonPrimitive) ?: JsonNull)
        put("spills", villager.spills)
        put("tended", buildJsonArray { villager.tended.forEach { add(it) } })
        put("turns", villager.turns)
    }

/** What the turns have shipped, and what they are known to have cost. */
private fun binOf(bin: ShippingBin): JsonObject = buildJsonObject {
    put("produce", bin.produce)
    put("ledger", bin.ledger)
    put("unpriced", bin.unpriced)
}

/** The plots bought, and what the next one costs. */
private fun landOf(plots: Int): JsonObject = buildJsonObject {
    put("plots", plots)
    put("price", plotPrice(plots))
}

/** The purse, with the lifetime coins its level began at and the next level begins at. */
private fun purseOf(purse: Purse): JsonObject = buildJsonObject {
    put("coins", purse.coins)
    put("earned", purse.earned)
    put("level", purse.level)
    put("floor", floorOf(purse.level))
    put("next", floorOf(purse.level + 1))
}
