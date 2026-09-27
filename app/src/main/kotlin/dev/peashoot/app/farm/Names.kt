package dev.peashoot.app.farm

/**
 * The names a villager can have, short enough to sit under a sprite. Which one a villager gets is
 * its id's hash, so one session is one villager in every run and in every replay of a feed — unless
 * a villager already on the farm has that name, when it takes the next free one: see [uniqueName].
 */
internal val VILLAGER_NAMES =
    listOf(
        "Ada",
        "Bede",
        "Cass",
        "Dot",
        "Elm",
        "Fern",
        "Gil",
        "Hazel",
        "Ida",
        "Jory",
        "Kit",
        "Lark",
        "Mira",
        "Nell",
        "Otto",
        "Pim",
        "Quill",
        "Rosa",
        "Sage",
        "Tam",
        "Una",
        "Vee",
        "Wren",
        "Xan",
        "Yara",
        "Zeb",
        "Bram",
        "Clover",
        "Dill",
        "Ember",
    )

/** `String.hashCode` is specified, so the same id picks the same name on every JVM. */
internal fun nameFor(id: String): String = VILLAGER_NAMES[id.hashCode().mod(VILLAGER_NAMES.size)]

/**
 * [nameFor], unless a villager on the farm already answers to it: two farmers with one name is a
 * farm that cannot be read, and four sessions share a name about one farm in four. The next name
 * along the list that nobody has is taken instead, so the same feed still names everyone the same
 * way in every run and replay; only the order villagers arrive in can move a name, and only off a
 * name someone else was holding. A farm with more villagers than names shares again from the top.
 */
internal fun uniqueName(state: FarmState, id: String): String {
    val taken = state.villagers.values.mapTo(HashSet()) { it.name }
    val first = id.hashCode().mod(VILLAGER_NAMES.size)
    return VILLAGER_NAMES.indices
        .map { VILLAGER_NAMES[(first + it) % VILLAGER_NAMES.size] }
        .firstOrNull { it !in taken } ?: nameFor(id)
}
