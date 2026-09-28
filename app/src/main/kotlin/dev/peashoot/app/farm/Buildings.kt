package dev.peashoot.app.farm

import kotlinx.serialization.json.JsonObject

/** How many extensions the farmhouse takes, each a wing with a yard of its own. */
private const val MOST_EXTENSIONS = 2

/** What the first farmhouse extension costs; the second costs twice as much. */
private const val HOUSE_PRICE = 40
private const val HOUSE_LEVEL = 2
private const val GREENHOUSE_PRICE = 100
private const val GREENHOUSE_LEVEL = 4

/**
 * The beds the greenhouse stands over: the back row of the starting island, which holds the first
 * four directories the farm heard of.
 */
private const val BEDS_UNDER_GLASS = 4

/** The levels the dog and the cat come to the farm at. They are free: nobody buys a pet. */
private const val DOG_LEVEL = 2
private const val CAT_LEVEL = 4

/**
 * What has been built beyond the starting farm: [house] farmhouse extensions, each more homes for
 * the farmers, and whether the greenhouse stands, which keeps its beds growing through winter.
 * Saved with the purse, like [FarmState.herd].
 */
data class Buildings(val house: Int = 0, val greenhouse: Boolean = false)

/** The shop's lines for the farmhouse extension and the greenhouse. */
internal fun FarmState.buildingOffers(): List<Offer> =
    listOf(
        offer(
            "house",
            HOUSE_PRICE * (buildings.house + 1),
            HOUSE_LEVEL,
            buildings.house < MOST_EXTENSIONS,
        ),
        offer("greenhouse", GREENHOUSE_PRICE, GREENHOUSE_LEVEL, !buildings.greenhouse),
    )

/** [buildings] with [item] built, or null when [item] is not a building. */
internal fun Buildings.with(item: String): Buildings? =
    when (item) {
        "house" -> copy(house = house + 1)
        "greenhouse" -> copy(greenhouse = true)
        else -> null
    }

/**
 * Whether the crops of [directory] grow: all year under the greenhouse's glass, and outside it
 * every season but winter, when an edit leaves an open bed's crop where it was. A directory the
 * farm has not heard of yet would take the next bed, so it is asked about as that bed.
 */
fun FarmState.grows(directory: String): Boolean {
    val slot = fields.keys.indexOf(directory).takeIf { it >= 0 } ?: fields.size
    return season != Season.WINTER || (buildings.greenhouse && slot < BEDS_UNDER_GLASS)
}

/** The pets the level has brought: the dog from level 2, and the cat from level 4. */
fun FarmState.pets(): List<String> =
    listOfNotNull(
        "dog".takeIf { purse.level >= DOG_LEVEL },
        "cat".takeIf { purse.level >= CAT_LEVEL },
    )

/**
 * Whether the line's turn failed, which is what the dog barks at: the provider answered with an
 * error. A 429 is not a failure but a rest, which [Activity.RESTING] says, and the dog barks at
 * that too.
 */
internal fun failed(event: JsonObject): Boolean =
    status(event)?.let { it !in ANSWERED && it != TOO_MANY_REQUESTS } == true
