package dev.peashoot.app.farm

import dev.peashoot.core.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull

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
internal const val READ = "Read"

/**
 * What a villager is doing. [IDLE] is only where one starts: its first line sets it walking or
 * coming home, and nothing in the feed ends a walk home. [tick] retires a villager whose session
 * has gone quiet rather than idling it, so nothing the window draws ever sees an idle one — the
 * renderer's own `Pose.STANDING` is what a villager that has arrived and has nothing out looks
 * like, worked out from having stopped rather than from this.
 */
enum class Activity {
    IDLE,
    WALKING_TO_WELL,
    /** Rate-limited: resting at the well rather than walking home, until the retry comes. */
    RESTING,
    RETURNING,
}

/** The sky over the whole farm: the latest line that said anything about it wins. */
enum class Weather {
    CLEAR,
    RAIN,
    STORM,
    LIGHTNING,
}

/** The palette's season, which only a clock can know: see [tick]. */
enum class Season {
    WINTER,
    SPRING,
    SUMMER,
    AUTUMN,
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
    val stamina: Stamina = Stamina(),
    /** Buckets spilled: turns whose client left before the answer arrived. */
    val spills: Int = 0,
)

/**
 * A villager's stamina, which is the provider's rate-limit remaining. Raw, as the headers said it:
 * the line carries no limit, so [peakTokens] is the most this villager has been told it had, which
 * is the only whole a bar can be a fraction of.
 */
data class Stamina(
    val remainingTokens: Long? = null,
    val remainingRequests: Long? = null,
    val peakTokens: Long? = null,
)

/** What the turns have dropped off: one produce per turn, and what the turns are known to cost. */
data class ShippingBin(
    val produce: Int = 0,
    val ledger: Double = 0.0,
    /**
     * Completions that reported usage and no cost, so the ledger can say why it is quieter than the
     * day. A refusal or an overload reported neither, and is not one of these.
     */
    val unpriced: Int = 0,
)

/** What one turn did to one crop, which is the three things a tool call can do to a file. */
enum class TouchKind {
    PLANTED,
    GROWN,
    INSPECTED,
}

/**
 * One turn against one file: which villager's turn it was, the `ts` of the line as it spelled it,
 * and what the turn did. The villager and not the session, because a helper touching its parent's
 * file is the thing the pane exists to show.
 */
data class Touch(val villager: String, val ts: String?, val kind: TouchKind)

/**
 * How many touches a crop remembers.
 *
 * This is the whole of the history the window has, and not because the proxy lacks it: every touch
 * is in a `tools` array on a stored `exchange.completed` line, and `GET /events?since=0` serves the
 * whole event table. The window asks for no backfill on its first connection, so it only ever hears
 * what happened after it opened, and these are what that leaves.
 *
 * ponytail: the newest [TOUCH_HISTORY], oldest dropped, so a file a run edits a thousand times
 * costs a bounded amount of memory. `Detail.touchNote` is what says so in the pane, rather than
 * letting the list simply end. Upgrade: a backfilled feed would give the pane the rest — though
 * that is a decision beyond this pane, since folding a day-old feed would end every finished
 * session's day at the first tick and put a card up for each.
 */
internal const val TOUCH_HISTORY = 50

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
     * Every turn that touched it, oldest first, up to [TOUCH_HISTORY]: #22's pane reads this. A
     * touch whose line carried no `ts` is kept with none rather than dropped or stamped with a
     * neighbour's time — the turn happened, and a made-up time is worse than an admitted gap.
     */
    val touches: List<Touch> = emptyList(),
) {
    /** One more turn on this crop's history, the oldest dropped once it is [TOUCH_HISTORY] long. */
    internal fun touched(villager: String, ts: String?, kind: TouchKind): Crop =
        copy(touches = (touches + Touch(villager, ts, kind)).takeLast(TOUCH_HISTORY))
}

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
    val weather: Weather = Weather.CLEAR,
    /** Spring until the first [tick]: an event line cannot know the month. */
    val season: Season = Season.SPRING,
    /** Replay is night. */
    val night: Boolean = false,
    val bin: ShippingBin = ShippingBin(),
    /** What each session has run up today, keyed by session; [tick] ends the quiet ones. */
    val days: Map<String, Day> = emptyMap(),
    /**
     * The cards ended days have left, oldest first: the window shows the front one and [dismissed]
     * takes it away.
     *
     * ponytail: no cap, because a card is only made when a day ends and only a person makes them go
     * away. A window left open over a weekend with nobody at it holds one small card per session
     * per day. Upgrade: drop the oldest past some number, if that is ever a real amount of memory.
     */
    val pendingCards: List<EndOfDayCard> = emptyList(),
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
 * so the same feed always replays to the same farm. Everything time can say is [tick]'s. A line
 * this cannot use — a field missing, a field of a shape it did not expect, an event name a later
 * proxy invented — gives back the state it was handed, because the window must not lose the farm
 * over one strange line.
 */
fun reduce(state: FarmState, event: JsonObject): FarmState =
    when (event["event"].text()) {
        "exchange.started" -> started(state, event)
        "exchange.completed" -> nonGenerating(state, event) ?: completed(state, event)
        "exchange.client_gone" -> clientGone(state, event)
        else -> state
    }

/**
 * The farm after a completed line that generated nothing, or null when the line is a turn like any
 * other and [completed] should read it as one.
 *
 * A Responses get-by-id and a cancel are exchanges of their own (#81): each has its own started and
 * completed line, and neither generated anything, because the response they name was generated by
 * the exchange that created it. Counted as turns they drew a second turn beside a cancel and one
 * walk to the well per poll of a background response. So such a line does the bookkeeping its
 * exchange needs — out of [Villager.inFlight], out of the well queue, home once nothing else is out
 * — and nothing else: no water, no crops, no weather, no bin, no day. A villager whose session this
 * window never heard is not made by a line that is not a turn.
 *
 * Reporting no usage was the whole of the test (#81). #81's own wording was "no usage and no
 * model", and the model half does not hold against the data: a cancel's body carries a `model` and
 * the reader reads it, so the event line for one names a model like a turn's does. What guards the
 * usage instead are the three ways a turn can report none and still be a turn, each of them a
 * fixture: a status outside [ANSWERED] failed rather than generated nothing (`rate-limit.jsonl`'s
 * 429, `weather.jsonl`'s 529), a stop reason that says it [ENDED] ended whatever it reported
 * (`odd-lines.jsonl`), and a stream whose client left was being answered (`stream-cut.jsonl`). A
 * replay hit and a resumed line (#26) both report usage and a zero cost, so both stay turns, which
 * is what they are.
 *
 * That fence reads [ENDED] and not `end_turn` alone (#93), the same set the bin ships on, because
 * it asks one question — does this line say the turn ended? — and the answer is spelled `completed`
 * on the Responses surface. Widening it costs #81 nothing: none of the exchanges it removed says
 * `completed`, since a poll of a running response says `in_progress` and a cancel says `cancelled`.
 * What it buys is a Responses create whose usage never parsed, which says `completed` and is a turn
 * for exactly the reason an `end_turn` that reported nothing is one.
 *
 * What leads is no longer the usage, though. #94: a `GET /v1/responses/{id}` of a response that has
 * already *finished* answers with the whole object, usage and all, so every reading of the line's
 * own numbers calls it a turn — a second produce, a second cost and the create's tokens a second
 * time on the day's card, once per poll. The Deriver knows what the reducer cannot, the request's
 * method and path, and now says it on the line: `generatedNothing`, set from the same rule #27's
 * `createsResponse()` already draws. So the flag leads where a line carries one and the usage is
 * the fallback for every line recorded before it, which is every cassette taken so far. The guards
 * belong to the fallback and not to the flag: they exist to keep the heuristic from calling a turn
 * a poll, and a line that states the fact outright needs no guarding — a finished-response poll
 * says `completed` and carries usage, so guarding the flag would be refusing to believe it.
 *
 * A status outside [ANSWERED] is the one thing still read ahead of the flag. A 429 or a 529 on a
 * poll is the key's news and not the poll's: the sky and the well want it, and reading it first
 * leaves every line recorded before #94 reduced exactly as it was.
 *
 * The walk is undone and not prevented. A `started` cannot know: the flag is on the completed line
 * only, for the reason the cost is. So a poll still costs its villager the trip out and back, and
 * this is what brings it home rather than leaving it at a well it has no reason to be at.
 */
private fun nonGenerating(state: FarmState, event: JsonObject): FarmState? {
    val generated =
        status(event)?.let { it in ANSWERED } != true ||
            (event.scalar("generatedNothing") { booleanOrNull }?.not()
                ?: (usage(event) != null ||
                    event["stopReason"].text() in ENDED ||
                    event.scalar("clientDisconnected") { booleanOrNull } == true))
    if (generated) return null
    val session = event["session"].text()
    val villager = session?.let { state.villagers[villagerOf(state, it, event).id] }
    val remaining = villager?.inFlight.orEmpty() - event["exchangeId"].text().orEmpty()
    val home = remaining.isEmpty()
    return if (villager == null) state
    else
        state.copy(
            villagers =
                state.villagers +
                    (villager.id to
                        villager.copy(
                            activity = if (home) Activity.RETURNING else villager.activity,
                            inFlight = remaining,
                        )),
            wellQueue = if (home) state.wellQueue - villager.id else state.wellQueue,
        )
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
    // WAITING_AT_WELL activity, which `tick` could set from how long ago the walk began.
    val walking =
        villager.copy(activity = Activity.WALKING_TO_WELL, inFlight = villager.inFlight + exchange)
    val queue =
        if (villager.id in state.wellQueue) state.wellQueue else state.wellQueue + villager.id
    return state.copy(
        villagers = state.villagers + (villager.id to walking),
        wellQueue = queue,
        night = nightAfter(state.night, event),
        days = state.days.heard(villager.session, event),
    )
}

/**
 * The turn ended: its tools plant and grow, its output tokens are water, and what the line says
 * about the sky, the bin and this key's rate limit is the farm's. The villager only leaves the well
 * once nothing of its own is still out there — and a 429 leaves it there resting, since the turn is
 * not done but nothing of it can go on either. A completed line for a villager never seen still
 * makes one, because the app can connect in the middle of a turn.
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
    val resting = status(event) == TOO_MANY_REQUESTS
    val weather = weatherOf(event)
    // A rest carries nothing, and neither does a turn whose client left: that bucket is spilled,
    // which is the lightning the sky is showing. The provider billed those tokens all the same, so
    // the day's card still counts them.
    val spilled = resting || weather == Weather.LIGHTNING
    val back =
        villager.copy(
            activity =
                when {
                    !home -> villager.activity
                    resting -> Activity.RESTING
                    else -> Activity.RETURNING
                },
            water = villager.water + if (spilled) 0 else outputTokens(event),
            inFlight = remaining,
            stamina = villager.stamina.after(event),
        )
    return state.copy(
        villagers = state.villagers + (villager.id to back),
        fields = touched(state.fields, event, villager.id),
        // Resting is resting *at the well*: the villager keeps its place until a turn really ends,
        // and is given one if the refusal is the first this window heard of it.
        wellQueue =
            when {
                !home -> state.wellQueue
                !resting -> state.wellQueue - villager.id
                villager.id in state.wellQueue -> state.wellQueue
                else -> state.wellQueue + villager.id
            },
        weather = weather,
        night = nightAfter(state.night, event),
        bin = state.bin.shipped(event),
        days = state.days.heard(session, event),
    )
}

/**
 * The client left mid-stream: lightning, and the bucket that turn was filling spills. Whose bucket
 * is only knowable through the exchange id, because this line carries no `agent`; an exchange
 * nobody here holds — a window that connected mid-stream — turns the sky and nothing else.
 */
private fun clientGone(state: FarmState, event: JsonObject): FarmState {
    val exchange = event["exchangeId"].text()
    val spiller = state.villagers.values.firstOrNull { exchange != null && exchange in it.inFlight }
    val villagers =
        spiller?.let { state.villagers + (it.id to it.copy(spills = it.spills + 1)) }
            ?: state.villagers
    return state.copy(villagers = villagers, weather = Weather.LIGHTNING)
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
internal fun nameFor(id: String): String = VILLAGER_NAMES[id.hashCode().mod(VILLAGER_NAMES.size)]

/** Every path the turn's tools named, in the order the turn named them. */
private fun touched(
    fields: Map<String, Field>,
    event: JsonObject,
    villager: String,
): Map<String, Field> {
    val ts = event["ts"].text()
    return tools(event).fold(fields) { grown, tool -> grown.touch(tool, ts, villager) }
}

/**
 * One tool call against the fields: the directory is the field, the file is the crop. Separators
 * are normalised first, so a file written on Windows and read on a POSIX box is one crop and not
 * two. A Read plants nothing — a file only looked at is no crop of ours — but an edit to a path
 * never planted does, and that edit is the planting rather than a stage on top of it, because the
 * file existed before the app was watching. A tool that named no path, or named one but only
 * searched it, changes nothing.
 */
private fun Map<String, Field>.touch(
    tool: JsonObject,
    ts: String?,
    villager: String,
): Map<String, Field> {
    val path = touchedPath(tool) ?: return this
    val name = tool["name"].text()
    val directory = path.substringBeforeLast('/', ROOT_FIELD)
    val field = this[directory] ?: Field(label = directory, crops = emptyMap())
    val crop = field.crops[path]
    val next =
        when {
            crop == null && name == READ -> null
            crop == null ->
                Crop(label = path, growth = Growth.SEED, inspections = 0)
                    .touched(villager, ts, TouchKind.PLANTED)
            name == READ ->
                crop
                    .copy(inspections = crop.inspections + 1)
                    .touched(villager, ts, TouchKind.INSPECTED)
            else -> crop.copy(growth = crop.growth.next()).touched(villager, ts, TouchKind.GROWN)
        }
    return if (next == null) this
    else plus(directory to field.copy(crops = field.crops + (path to next)))
}

/** Growth stops at the last stage: a ripe crop stays ripe rather than starting over. */
private fun Growth.next(): Growth =
    Growth.entries[(ordinal + 1).coerceAtMost(Growth.entries.lastIndex)]
