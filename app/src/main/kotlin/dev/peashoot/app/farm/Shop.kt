package dev.peashoot.app.farm

/** How many animals the coop, and the barn, hold before either is ever upgraded. */
private const val FIRST_ROOM = 6

/** How much room each upgrade adds. */
private const val ROOM_AN_UPGRADE = 4

/** What the first coop upgrade costs; each after it costs this much more. */
private const val COOP_PRICE = 30

/** What the first barn upgrade costs; each after it costs this much more. */
private const val BARN_PRICE = 50

private const val HEN_PRICE = 10
private const val COW_PRICE = 40
private const val SHEEP_PRICE = 30
private const val PIG_PRICE = 30
private const val HEN_LEVEL = 2
private const val COW_LEVEL = 3
private const val SHEEP_LEVEL = 4
private const val PIG_LEVEL = 5

/** What the farm's shop sells: [price] coins each, from [level] on. Hens live in the coop. */
enum class Animal(val price: Int, val level: Int, val inCoop: Boolean) {
    HEN(HEN_PRICE, HEN_LEVEL, true),
    COW(COW_PRICE, COW_LEVEL, false),
    SHEEP(SHEEP_PRICE, SHEEP_LEVEL, false),
    PIG(PIG_PRICE, PIG_LEVEL, false);

    /** Its name on the click line and in the snapshot. */
    val key: String = name.lowercase()
}

/**
 * The animals the farm owns, the starting three cows and three hens among them, and how many times
 * the coop and the barn have been upgraded. Saved with the purse, like [FarmState.land].
 */
data class Herd(
    val hens: Int = 3,
    val cows: Int = 3,
    val sheep: Int = 0,
    val pigs: Int = 0,
    val coop: Int = 0,
    val barn: Int = 0,
) {
    /** How many hens the coop holds. */
    val coopRoom: Int
        get() = FIRST_ROOM + ROOM_AN_UPGRADE * coop

    /** How many cows, sheep and pigs, together, the barn holds. */
    val barnRoom: Int
        get() = FIRST_ROOM + ROOM_AN_UPGRADE * barn

    fun count(animal: Animal): Int =
        when (animal) {
            Animal.HEN -> hens
            Animal.COW -> cows
            Animal.SHEEP -> sheep
            Animal.PIG -> pigs
        }

    fun hasRoomFor(animal: Animal): Boolean =
        if (animal.inCoop) hens < coopRoom else cows + sheep + pigs < barnRoom

    internal fun with(animal: Animal): Herd =
        when (animal) {
            Animal.HEN -> copy(hens = hens + 1)
            Animal.COW -> copy(cows = cows + 1)
            Animal.SHEEP -> copy(sheep = sheep + 1)
            Animal.PIG -> copy(pigs = pigs + 1)
        }
}

/**
 * Why the shop will not sell something yet: the level is too low, there is no room, or no coins.
 */
enum class Refusal {
    LEVEL,
    FULL,
    COINS,
}

/**
 * One line of the shop's board: [item] for [price] coins, from [level] on, and why it cannot be
 * bought now, or null when it can.
 */
data class Offer(val item: String, val price: Int, val level: Int, val refusal: Refusal?)

/** Everything on the shop's board, the animals first and then the two upgrades. */
fun FarmState.offers(): List<Offer> =
    Animal.entries.map { animal ->
        offer(animal.key, animal.price, animal.level, herd.hasRoomFor(animal))
    } +
        offer("coop", COOP_PRICE * (herd.coop + 1), 1, true) +
        offer("barn", BARN_PRICE * (herd.barn + 1), 1, true)

private fun FarmState.offer(item: String, price: Int, level: Int, room: Boolean): Offer {
    val refusal =
        when {
            purse.level < level -> Refusal.LEVEL
            !room -> Refusal.FULL
            purse.coins < price -> Refusal.COINS
            else -> null
        }
    return Offer(item, price, level, refusal)
}

/**
 * The farm with [item] bought — a plot, an animal, or a coop or barn upgrade — or the farm as it
 * was when the shop will not sell it, or when there is no such thing. Like [boughtPlot], only a
 * person's click leads here, and the farm decides.
 */
fun FarmState.bought(item: String): FarmState {
    if (item == "plot") return boughtPlot()
    val offer = offers().firstOrNull { it.item == item && it.refusal == null }
    return if (offer == null) this else sold(offer)
}

private fun FarmState.sold(offer: Offer): FarmState {
    val herd =
        when (offer.item) {
            "coop" -> herd.copy(coop = herd.coop + 1)
            "barn" -> herd.copy(barn = herd.barn + 1)
            else -> herd.with(Animal.entries.first { it.key == offer.item })
        }
    return copy(purse = purse.copy(coins = purse.coins - offer.price), herd = herd)
}
