package dev.peashoot.app.farm

import dev.peashoot.core.EDIT_TOOLS
import dev.peashoot.core.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The names a villager can have, short enough to sit under a sprite. Which one a villager gets is
 * its id's hash, so one session is one villager in every run and in every replay of a feed.
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

/** The field a file with no directory in front of it grows in: every crop belongs to one. */
private const val ROOT_FIELD = "."

/** The one tool that only looks, which is an inspection rather than growth. */
private const val READ = "Read"

/**
 * What a villager is doing. [IDLE] is only where one starts: its first line sets it walking or
 * coming home, and nothing in the feed says a walk home has ended, which takes a clock (#18).
 */
enum class Activity {
    IDLE,
    WALKING_TO_WELL,
    RETURNING,
}

/** How far a crop has come. It stops at [RIPE]; nothing takes a crop backwards. */
enum class Growth {
    SEED,
    SPROUT,
    GROWING,
    RIPE,
}

/**
 * One villager: a session, or a sub-agent helping one. [inFlight] is what keeps a villager at the
 * well while several of its exchanges are out at once, which Claude Code does routinely: one of
 * them completing is not the villager's turn ending.
 */
data class Villager(
    val id: String,
    val name: String,
    /** The session it belongs to, which for a main-thread villager is its own id. */
    val session: String,
    /**
     * The villager this one helps, by the id its lines named; null for a main-thread session. An id
     * and not a promise, since a helper can be heard before its parent: [FarmState.parentOf] is who
     * it stands beside meanwhile.
     */
    val parent: String?,
    val activity: Activity = Activity.IDLE,
    /** Output tokens carried home, added up over this villager's completed exchanges. */
    val water: Int = 0,
    /** The exchanges of this villager that have started and not completed. */
    val inFlight: Set<String> = emptySet(),
)

/**
 * One file the agents have touched. [label] is its path, kept in the state because the farm draws
 * it when [FarmState.labelsHidden] is off, and it is the crop's key as well.
 *
 * ponytail: keyed by the normalised path string where the design says "path hash". A hash would buy
 * nothing a string key lacks and would need this field anyway to draw the label from. Upgrade: hash
 * it if a crop ever has to be named where its path is not in memory.
 */
data class Crop(
    val label: String,
    val growth: Growth,
    /** Reads of this file: an inspection, which advances nothing. */
    val inspections: Int,
    /**
     * The `ts` of the event that last touched it, as the event line spells it; null only for a crop
     * planted by a line that had none. #22's touch history wants more than the last.
     */
    val lastTouched: String?,
)

/** A directory: the field the files under it grow in, labelled with the directory itself. */
data class Field(val label: String, val crops: Map<String, Crop>)

/**
 * The farm as the events heard so far have left it. Everything a renderer needs, and nothing else.
 */
data class FarmState(
    val villagers: Map<String, Villager> = emptyMap(),
    val fields: Map<String, Field> = emptyMap(),
    /** Who is at the well, in the order they arrived. */
    val wellQueue: List<String> = emptyList(),
    /**
     * Whether [Crop.label] and [Field.label] are drawn. Hidden until something turns them on, so a
     * screenshot leaks no path by accident; one flag for the whole farm, because the badged toggle
     * it will answer to (#20) is one switch, not one per crop.
     */
    val labelsHidden: Boolean = true,
)

/**
 * Who a helper stands beside: the villager its lines named once the farm has heard of that one, and
 * the session's own villager until then. Worked out on reading and not once at the helper's first
 * line, because a window that connects in the middle of a run hears a nested helper before its
 * parent, and a link settled then would flatten the fan-out for good.
 */
fun FarmState.parentOf(villager: Villager): Villager? =
    villager.parent?.let { villagers[it] ?: villagers[villager.session] }

/**
 * The farm after one event line. Pure: no clock, no randomness, nothing but the line's own fields,
 * so the same feed always replays to the same farm. A line this cannot use — a field missing, a
 * field of a shape it did not expect, an event name a later proxy invented — gives back the state
 * it was handed, because the window must not lose the farm over one strange line.
 * `exchange.client_gone` is one of those for now; it becomes lightning with #18.
 */
fun reduce(state: FarmState, event: JsonObject): FarmState =
    when (event["event"].text()) {
        "exchange.started" -> started(state, event)
        "exchange.completed" -> completed(state, event)
        else -> state
    }

/**
 * A request in flight: the villager walks to the well, and waits there until its turns are done.
 */
private fun started(state: FarmState, event: JsonObject): FarmState {
    val session = event["session"].text()
    val exchange = event["exchangeId"].text()
    if (session == null || exchange == null) return state
    val villager = villagerOf(state, session, event)
    // ponytail: the feed says nothing between started and completed, so the wait at the well is the
    // tail of the walk; the renderer shows waiting once the sprite arrives (#20). Upgrade: a
    // WAITING_AT_WELL activity, if a tick ever reaches the reducer (#18's clock).
    val walking =
        villager.copy(activity = Activity.WALKING_TO_WELL, inFlight = villager.inFlight + exchange)
    val queue =
        if (villager.id in state.wellQueue) state.wellQueue else state.wellQueue + villager.id
    return state.copy(villagers = state.villagers + (villager.id to walking), wellQueue = queue)
}

/**
 * The turn ended: its tools plant and grow, and its output tokens are water. The villager only
 * leaves the well once nothing of its own is still out there. A completed line for a villager never
 * seen still makes one, because the app can connect in the middle of a turn.
 */
private fun completed(state: FarmState, event: JsonObject): FarmState {
    val session = event["session"].text() ?: return state
    val villager = villagerOf(state, session, event)
    // ponytail: the same completed line heard twice would carry its water and grow its crops
    // twice. Telling that from a late join, which is also a completion nothing started, takes every
    // exchange id ever heard; what keeps a line from arriving twice is the feed's cursor
    // (`ControlClient.events`). Upgrade: remember completed ids, if a feed ever redelivers.
    val remaining = event["exchangeId"].text()?.let { villager.inFlight - it } ?: villager.inFlight
    val home = remaining.isEmpty()
    val back =
        villager.copy(
            activity = if (home) Activity.RETURNING else villager.activity,
            water = villager.water + outputTokens(event),
            inFlight = remaining,
        )
    return state.copy(
        villagers = state.villagers + (villager.id to back),
        fields = touched(state.fields, event),
        wellQueue = if (home) state.wellQueue - villager.id else state.wellQueue,
    )
}

/**
 * The villager an event belongs to, as the state has it or as it will be. A sub-agent is a villager
 * of its own, keyed by session and agent, whose parent is the helper `parentAgent` names, or the
 * session's villager when it names none. Whether that parent has been heard yet is not asked here:
 * [parentOf] asks it each time the farm is read.
 */
private fun villagerOf(state: FarmState, session: String, event: JsonObject): Villager {
    val agent = event["agent"].text()
    val id = if (agent == null) session else "$session/$agent"
    val parent = agent?.let {
        event["parentAgent"].text()?.let { named -> "$session/$named" } ?: session
    }
    return state.villagers[id] ?: Villager(id, nameFor(id), session, parent)
}

/** `String.hashCode` is specified, so the same id picks the same name on every JVM. */
private fun nameFor(id: String): String = VILLAGER_NAMES[id.hashCode().mod(VILLAGER_NAMES.size)]

/** Water is what the turn reported as output; a turn that reported no usage carried none. */
private fun outputTokens(event: JsonObject): Int =
    ((event["usage"] as? JsonObject)?.get("output") as? JsonPrimitive)?.intOrNull ?: 0

/** Every path the turn's tools named, in the order the turn named them. */
private fun touched(fields: Map<String, Field>, event: JsonObject): Map<String, Field> {
    val ts = event["ts"].text()
    val tools = (event["tools"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    return tools.fold(fields) { grown, tool -> grown.touch(tool, ts) }
}

/**
 * One tool call against the fields: the directory is the field, the file is the crop. Separators
 * are normalised first, so a file written on Windows and read on a POSIX box is one crop and not
 * two. A Read plants nothing — a file only looked at is no crop of ours — but an edit to a path
 * never planted does, and that edit is the planting rather than a stage on top of it, because the
 * file existed before the app was watching. A tool that named no path, or named one but only
 * searched it, changes nothing.
 */
private fun Map<String, Field>.touch(tool: JsonObject, ts: String?): Map<String, Field> {
    val path = tool["path"].text()?.replace('\\', '/')
    val name = tool["name"].text()
    val touches = name == READ || name in EDIT_TOOLS
    if (path == null || !touches) return this
    val directory = path.substringBeforeLast('/', ROOT_FIELD)
    val field = this[directory] ?: Field(label = directory, crops = emptyMap())
    val crop = field.crops[path]
    val next =
        when {
            crop == null && name == READ -> null
            crop == null ->
                Crop(label = path, growth = Growth.SEED, inspections = 0, lastTouched = ts)
            // A line whose `ts` is missing or is not a string keeps the crop's last good one: a
            // strange line must leave the farm no worse, and no timestamp is worse than a stale
            // one.
            name == READ ->
                crop.copy(inspections = crop.inspections + 1, lastTouched = ts ?: crop.lastTouched)
            else -> crop.copy(growth = crop.growth.next(), lastTouched = ts ?: crop.lastTouched)
        }
    return if (next == null) this
    else plus(directory to field.copy(crops = field.crops + (path to next)))
}

/** Growth stops at the last stage: a ripe crop stays ripe rather than starting over. */
private fun Growth.next(): Growth =
    Growth.entries[(ordinal + 1).coerceAtMost(Growth.entries.lastIndex)]
