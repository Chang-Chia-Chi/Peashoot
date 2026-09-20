package dev.peashoot.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.peashoot.app.farm.scalar
import dev.peashoot.app.render.Hit
import dev.peashoot.core.text
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// What the detail panes show, and the state behind them. Everything here but [PaneModel] is pure:
// a JSON answer in, rows out, with no window and no socket, which is the seam #22 is tested at.

/**
 * How many characters of a body the viewer shows. What is past it is counted and not shown.
 *
 * This caps what is *kept*, not what arrives: the whole answer is a string and a parsed tree for as
 * long as it takes to read the one field out of it, which is why that parse is not done on the
 * window's thread. Only the one body being looked at is held after that.
 *
 * ponytail: the proxy has no way to be asked for part of a body. Upgrade: a range request, if the
 * control API ever serves one.
 */
private const val BODY_CAP = 64 * 1024

/**
 * How many completed lines the window remembers, keyed by exchange. Since #85 a summary row says
 * what the turn used, cost, took and whether it was a hit, so what is left here is the model it
 * answered as, the paths its tools named, and `resumed` — the fields the exchanges endpoint has
 * nowhere to put, because they are the answer's and not the row's.
 *
 * Those are not gone from the proxy either: `GET /events?since=0` backfills the whole event table.
 * The window never asks for it, because `AppModel.watch`'s first connection deliberately takes no
 * backfill, so what a pane can say about *them* for a turn from before it connected is bounded by
 * that choice rather than by the API.
 *
 * ponytail: the newest [HEARD_EXCHANGES], which is `MAX_LINES`' order of magnitude for the same
 * reason — a scrollback, not a second copy of the table. Upgrade: the model and the tools on a
 * summary row would retire this outright, which is the same move #85 made for the other four.
 */
private const val HEARD_EXCHANGES = 500

/** Enough hex to tell two files apart in a pane at a glance, and not enough to be a path. */
private const val HASH_DIGITS = 6

private const val HASH_RADIX = 16

/**
 * One row of a villager's timeline: the exchanges endpoint's summary, plus what only the event line
 * carries. A field nothing said is null rather than zero — "nobody said" and "it cost nothing" are
 * different, and a replay hit really does cost nothing.
 */
internal data class ExchangeRow(
    val id: String,
    /** When the proxy received it, as the endpoint spells it. */
    val at: String,
    /** The sub-agent whose turn it was, or null for the session's own thread. */
    val agent: String?,
    val model: String?,
    val usage: String?,
    val costUsd: Double?,
    val latencyMs: Long?,
    val replayHit: Boolean,
    /**
     * Stream-resume answered it from the buffer, as the `exchange.completed` line says since #26;
     * an absent field reads as false, which is what an absent one means. No row the exchanges
     * endpoint serves can carry it, though: a resumed answer is not stored, for the reason a replay
     * hit is not — it made no upstream call — so on this timeline it is the ORIGINAL row, the one
     * whose client left, that a resumed turn shows up as.
     *
     * The same holds for [replayHit], which a summary row does carry since #85: it reads false on
     * every row the proxy recorded, for exactly this reason, and a replay hit shows up on a
     * timeline only as the recording it was served from.
     */
    val resumed: Boolean,
    val clientDisconnected: Boolean,
    val status: Int?,
    /** The paths this turn's tools named, which the pane shows through [pathLabel] and not raw. */
    val paths: List<String>,
)

/**
 * One page of a session's timeline: the rows the endpoint gave, and whether it said there are more
 * behind them. [more] is the `nextCursor` the endpoint sets when a page is full, kept because a
 * pane that shows the newest fifty of two hundred turns and says nothing is a pane that lies by
 * omission.
 */
internal data class Timeline(val rows: List<ExchangeRow>, val more: Boolean)

/**
 * The pane's page for one session, from the exchanges endpoint's own answer, in its own order, each
 * row with whatever [heard] has for that exchange id folded onto it. The endpoint decides which
 * rows there are and in what order; the feed only fills in what a summary row does not carry.
 *
 * Null means the answer could not be read at all, which is a different thing from a session with
 * nothing in it and must not be reported as one: "this session has done nothing" is the one wrong
 * thing to say about a truncated answer. It is news rather than a throw, because the answer came
 * over a socket from another process. A row naming no id is dropped rather than kept under a blank
 * one, since the list is drawn keyed by it and two blanks would take the window down.
 */
internal fun timelineOf(body: String, heard: (String) -> JsonObject?): Timeline? = runCatching {
    // `exchanges` has to be there and has to be an array, or this is not the endpoint
    // answering: absent or of another shape is unreadable, not a session with nothing in it.
    val answer = checkNotNull(jsonOf(body))
    Timeline(
        rows =
            (answer["exchanges"] as JsonArray)
                .filterIsInstance<JsonObject>()
                .map { rowOf(it, heard(it["id"].text().orEmpty())) }
                .filter { it.id.isNotEmpty() },
        more = answer["nextCursor"].text() != null,
    )
}
    .getOrNull()

/**
 * The one line a timeline says about itself: why there is nothing, or that what is shown is not all
 * there is. Null is a page that speaks for itself, which is the ordinary case.
 */
internal fun timelineNote(session: String, page: Timeline?): String? =
    when {
        page == null -> "that session's exchanges came back unreadable"
        page.rows.isEmpty() -> "no exchanges for $session yet"
        page.more -> "the newest ${page.rows.size} shown; this session has more"
        else -> null
    }

/**
 * One turn against one file, as `GET /touches?path=` serves it: which turn, when, whose villager's
 * it was, and the tools it named that path with — `["Read", "Edit"]` for a turn that read the file
 * and then edited it. The tools are the line's own names and not the farm's three kinds: the farm
 * reads a turn as planting, growing or inspecting, and this pane says what the proxy said.
 *
 * [villager] is keyed as the reducer keys villagers, so a helper's touch names the helper.
 */
internal data class TouchRow(
    val eventId: Long,
    val exchangeId: String,
    val at: String?,
    val villager: String,
    val tools: List<String>,
)

/** One page of a file's history: the rows the endpoint gave, and whether more lie behind them. */
internal data class Touches(val rows: List<TouchRow>, val more: Boolean)

/**
 * A file's touch history from the endpoint's own answer, in its own order, newest first. Since #85
 * this is the proxy's whole record and not the window's: it reaches back past the moment this
 * window connected, which is as far as the reducer's own touches ever went.
 *
 * Null is an answer that could not be read, which is not a file nothing has touched, for the reason
 * [timelineOf] gives. A row naming no session is dropped rather than drawn under a blank villager.
 */
internal fun touchesOf(body: String): Touches? = runCatching {
    val answer = checkNotNull(jsonOf(body))
    Touches(
        rows =
            (answer["touches"] as JsonArray).filterIsInstance<JsonObject>().mapNotNull(::touchOf),
        more = answer["nextCursor"] != null && answer["nextCursor"] !is JsonNull,
    )
}
    .getOrNull()

/**
 * The one line a touch history says about itself: why there is nothing, or that what is shown is
 * not all there is. A list that simply ends looks like a complete list.
 */
internal fun touchNote(page: Touches?): String? =
    when {
        page == null -> "that file's touches came back unreadable"
        page.rows.isEmpty() -> "nothing the proxy heard has touched this file"
        page.more -> "the newest ${page.rows.size} shown; this file has been touched more"
        else -> null
    }

/**
 * One summary row, and the completed line for it when the window heard one.
 *
 * Usage, cost, latency and the replay flag are asked of the row first and of the line second. They
 * are one value either way — the row carries the line the proxy stored — but only the row has it
 * for a turn from before this window connected, and only the line has it while a proxy older
 * than #85 is what is answering.
 */
private fun rowOf(summary: JsonObject, line: JsonObject?): ExchangeRow {
    fun said(name: String): JsonElement? =
        summary[name]?.takeUnless { it is JsonNull } ?: line?.get(name)
    return ExchangeRow(
        id = summary["id"].text().orEmpty(),
        at = summary["receivedAt"].text().orEmpty(),
        agent = summary["agent"].text(),
        model = line?.get("model").text(),
        usage = usageText(said("usage") as? JsonObject),
        costUsd = (said("costUsd") as? JsonPrimitive)?.doubleOrNull,
        latencyMs = (said("latencyMs") as? JsonPrimitive)?.longOrNull,
        replayHit = (said("replayHit") as? JsonPrimitive)?.booleanOrNull == true,
        resumed = line?.scalar("resumed") { booleanOrNull } == true,
        // Either source saying the client left is the client having left: the summary is the
        // store's flag and the line is the deriver's reading of the same exchange.
        clientDisconnected =
            summary.scalar("clientDisconnected") { booleanOrNull } == true ||
                line?.scalar("clientDisconnected") { booleanOrNull } == true,
        status = summary.scalar("status") { intOrNull },
        // Every path the turn's tools named, in the order the turn named them; a tool that named
        // none contributes none, and a row with no line behind it has none at all.
        paths =
            (line?.get("tools") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull {
                it["path"].text()
            },
    )
}

/**
 * What a pane writes where a path would go. The one function every path in every pane goes through,
 * so the show-paths toggle has one place to be obeyed and no pane can forget it.
 *
 * Hidden, a file is [HASH_DIGITS] hex digits of its path's `String.hashCode`. What that buys is
 * exactly two things: the spelling of the path does not appear on screen or in a screenshot, and
 * one file stays recognisable as itself across the timeline, the touch history and the pane title.
 * It is not anonymity and must not be sold as it: the stand-in is an equality-preserving pseudonym,
 * so anyone holding a candidate path can confirm it by hashing, and 24 bits means two files in a
 * repository of a few thousand can share one. Neither matters for what the toggle is for — keeping
 * a path out of a picture — and widening the hash would not fix the first at all.
 *
 * `String.hashCode` is specified, so the stand-in is the same on every JVM, for the reason
 * `nameFor` relies on: a villager and a file both have to look the same in every run.
 */
internal fun pathLabel(path: String, hidden: Boolean): String =
    if (hidden) "file ${shortHash(path)}" else path

/**
 * One line of a crop's touch history. The villager's name is not a path and is written plainly; the
 * time is the event line's own `ts`, and a line that carried none says so rather than inventing
 * one.
 */
internal fun touchText(row: TouchRow, name: String): String =
    "${row.at ?: "no time given"}  $name  ${row.tools.joinToString(", ").ifEmpty { "touched" }}"

/**
 * One row of a touch page. A row naming no session is dropped: the pane draws a touch under its
 * villager, and the farm keys a villager by session, or by session and agent for a helper.
 */
private fun touchOf(row: JsonObject): TouchRow? {
    val session = row["session"].text()
    val eventId = row.scalar("eventId") { longOrNull }
    if (session == null || eventId == null) return null
    val agent = row["agent"].text()
    return TouchRow(
        eventId = eventId,
        exchangeId = row["exchangeId"].text().orEmpty(),
        at = row["ts"].text(),
        villager = agent?.let { "$session/$it" } ?: session,
        tools = (row["tools"] as? JsonArray).orEmpty().mapNotNull { it.text() },
    )
}

/**
 * The body in a `GET /exchanges/{id}` answer, capped, with what was left off said out loud. Bodies
 * are prompts: they are never logged, never put in an error, and only the one being looked at is
 * held anywhere.
 */
internal fun bodyText(detail: String): String = runCatching {
    val body = checkNotNull(jsonOf(detail))["requestBody"].text().orEmpty()
    if (body.length <= BODY_CAP) return@runCatching body
    // Characters and not bytes: [BODY_CAP] counts `String` units, so calling them bytes was simply
    // wrong for any prompt with a non-ASCII character in it. Counted as code points, so an emoji
    // counts once, and never cut between the halves of a surrogate pair — half a pair is not a
    // character and draws as a replacement box.
    val end = if (body[BODY_CAP - 1].isHighSurrogate()) BODY_CAP - 1 else BODY_CAP
    val characters = body.codePointCount(0, body.length)
    body.take(end) + "\n\n… truncated, $characters characters in all"
}
    .getOrDefault("the proxy answered something this window cannot read as an exchange")

private fun shortHash(path: String): String =
    path.hashCode().toUInt().toString(HASH_RADIX).takeLast(HASH_DIGITS).padStart(HASH_DIGITS, '0')

/** What the turn reported using, or null where nothing reported it at all. */
private fun usageText(usage: JsonObject?): String? {
    if (usage == null) return null
    fun count(name: String) = usage.scalar(name) { intOrNull } ?: 0
    return "${count("input")} in, ${count("output")} out, " +
        "${count("cacheRead")} cached, ${count("cacheWrite")} written"
}

/**
 * What the panes are showing and how it got there. Its state is touched only from the thread
 * `AppModel.watch` runs on — a Compose Desktop click handler is on the window's thread, which is
 * that one — for exactly the reason `AppModel.showPaths` documents.
 *
 * Nothing here throws at a click: a load that fails leaves its reason in [note], and a load still
 * running is cancelled by the next one, so two quick clicks cannot answer in the wrong order.
 *
 * [labelsHidden] is read here and not taken as an argument, so that "no body while paths are
 * hidden" is enforced where the fetch happens rather than trusted to every caller.
 */
class PaneModel(private val labelsHidden: () -> Boolean) {
    /** What was clicked, or nothing, which is a closed pane. */
    var selected by mutableStateOf<Hit?>(null)
        private set

    /** The selected villager's timeline, as the exchanges endpoint gave it, newest first. */
    internal val rows = mutableStateListOf<ExchangeRow>()

    /** The selected crop's touch history, as the touches endpoint gave it, newest first. */
    internal val touches = mutableStateListOf<TouchRow>()

    /** What the pane is doing or why it cannot: one line, always safe to show. */
    var note by mutableStateOf<String?>(null)
        private set

    /** The body being looked at, which is the only one held anywhere. */
    var body by mutableStateOf<String?>(null)
        private set

    private val lines = LinkedHashMap<String, JsonObject>()

    private var scope: CoroutineScope? = null
    private var client: ControlClient? = null

    /**
     * Whichever list the pane is filling — a villager's timeline rows or a crop's touches, since
     * only one pane is open at a time — and, beside it, the one body being read.
     *
     * One job each, rather than one between them: they are independent things to be waiting for,
     * and sharing a job meant opening a body cancelled a timeline still arriving. Nothing can reach
     * that today, because the button that opens a body is only drawn once rows exist — which is
     * exactly the kind of reason that stops being true when someone moves the button.
     */
    private var loadingList: Job? = null
    private var loadingBody: Job? = null

    /** Given a proxy to ask and a scope to ask from, for as long as [AppModel.watch] has both. */
    internal fun attach(scope: CoroutineScope, client: ControlClient) {
        this.scope = scope
        this.client = client
    }

    /**
     * Given up again, before the client is closed under it. A pane asking a closed client would
     * fail in a way nobody could read, so it is given nothing to ask and says so instead.
     */
    internal fun detach() {
        loadingList?.cancel()
        loadingBody?.cancel()
        loadingList = null
        loadingBody = null
        scope = null
        client = null
    }

    /** One completed line, kept by exchange id so a timeline row can say what it cost. */
    internal fun heard(event: JsonObject) {
        if (event["event"].text() != "exchange.completed") return
        val id = event["exchangeId"].text() ?: return
        lines.remove(id)
        lines[id] = event
        while (lines.size > HEARD_EXCHANGES) lines.remove(lines.keys.first())
    }

    /** A click on the farm: either pane asks the proxy, since #85 gave both a question to ask. */
    fun select(hit: Hit) {
        clear()
        selected = hit
        when (hit) {
            is Hit.OnVillager -> load(hit.id)
            is Hit.OnCrop -> loadTouches(hit.path)
        }
    }

    fun dismiss() {
        clear()
        selected = null
    }

    private fun clear() {
        loadingList?.cancel()
        loadingBody?.cancel()
        rows.clear()
        touches.clear()
        body = null
        note = null
    }

    /**
     * The body of one exchange, fetched on demand. Refused outright while paths are hidden: a body
     * is nothing but paths and prompts, and a pane that would mask it line by line would be one
     * mask away from leaking the lot.
     */
    fun showBody(id: String) {
        val asking = client
        if (labelsHidden()) return
        if (asking == null) {
            note = "there is no proxy to ask"
            return
        }
        loadingBody?.cancel()
        body = null
        note = "reading exchange $id"
        loadingBody = scope?.launch {
            asking
                .get("/exchanges/${id.encodeURLParameter()}")
                .fold(
                    { answer ->
                        // Off this thread: the feed collector, the health poll and the frame
                        // loop are all on it, and an exchange of a few megabytes would stall
                        // every one of them for as long as the parse took.
                        val text = withContext(Dispatchers.Default) { bodyText(answer) }
                        body = text
                        note = null
                    },
                    { note = "that exchange's body could not be read: ${it.message}" },
                )
        }
    }

    fun hideBody() {
        body = null
    }

    /**
     * A villager's session, from the endpoint. A helper's exchanges are its session's — the
     * endpoint filters by session and has no agent parameter — so clicking a helper shows the whole
     * session's timeline with each row naming the agent whose turn it was, rather than this window
     * filtering a page the proxy already narrowed.
     */
    private fun load(villager: String) {
        val session = villager.substringBefore('/')
        fetch("$session's exchanges", "/exchanges?session=${session.encodeURLParameter()}") {
            val page = timelineOf(it, lines::get)
            rows.addAll(page?.rows.orEmpty())
            timelineNote(session, page)
        }
    }

    /**
     * A crop's history, from the proxy rather than from the farm. The reducer's own touches are
     * only what this window heard, and since #85 the endpoint answers the whole of it — which is
     * what #22 asked for and could not have while this was the feed's to remember.
     *
     * The path goes out as the crop is keyed, with separators already normalised; the endpoint
     * normalises the stored ones the same way, so a file written on Windows and read on a POSIX box
     * is one file to both.
     */
    private fun loadTouches(path: String) {
        fetch("this file's touches", "/touches?path=${path.encodeURLParameter()}") {
            val page = touchesOf(it)
            touches.addAll(page?.rows.orEmpty())
            touchNote(page)
        }
    }

    /**
     * One page from the proxy into whatever the pane holds, with [read] answering the note to show.
     * Nothing throws at the click: a call that failed leaves its reason in [note].
     *
     * [read] runs on this thread, where a body deliberately does not. A page is bounded by the
     * endpoint's own limit and its rows are small flat objects, where a body has no bound at all;
     * and a timeline reads `lines`, which only this thread writes, so moving it would trade a parse
     * nobody can feel for a race on the map the feed is filling.
     *
     * ponytail: one page, at the endpoint's own default, and the cursor is read only to say that
     * there are more rather than to fetch them. Upgrade: pass it back when someone scrolls to the
     * end.
     */
    private fun fetch(what: String, path: String, read: (String) -> String?) {
        val asking = client
        if (asking == null) {
            note = "there is no proxy to ask"
            return
        }
        note = "asking the proxy for $what"
        loadingList = scope?.launch {
            asking
                .get(path)
                .fold({ note = read(it) }, { note = "$what could not be read: ${it.message}" })
        }
    }
}
