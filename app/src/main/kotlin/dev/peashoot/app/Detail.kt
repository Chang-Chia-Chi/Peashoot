package dev.peashoot.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.peashoot.app.farm.Touch
import dev.peashoot.app.farm.scalar
import dev.peashoot.app.render.Hit
import dev.peashoot.core.text
import io.ktor.http.encodeURLParameter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// What the detail panes show, and the state behind them. Everything here but [PaneModel] is pure:
// a JSON answer in, rows out, with no window and no socket, which is the seam #22 is tested at.

/**
 * How much of a body the viewer shows. What is past it is counted and not shown.
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
 * How many completed lines the window remembers, keyed by exchange. The exchanges endpoint serves
 * summary rows, and usage, cost, latency and the replay flag are on the event line alone, so this
 * is the only place they can come from for an exchange this window heard.
 *
 * ponytail: the newest [HEARD_EXCHANGES], which is `MAX_LINES`' order of magnitude for the same
 * reason — a scrollback, not a second copy of the table. An exchange older than that, or one from
 * before the window connected, shows what the endpoint knows and blanks for the rest. Upgrade: a
 * control API that serves usage and timings on a summary row, which would retire this outright.
 */
private const val HEARD_EXCHANGES = 500

/** Enough hex to tell two files apart in a pane, and not enough to be a path. */
private const val HASH_DIGITS = 6

private const val HASH_RADIX = 16

/**
 * One row of a villager's timeline: the exchanges endpoint's summary, plus what only the event line
 * carries. A field the window never heard a line for is null rather than zero — "nobody said" and
 * "it cost nothing" are different, and a replay hit really does cost nothing.
 */
data class ExchangeRow(
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
     * Stream-resume answered it from the buffer. Always false today: the Deriver emits no `resumed`
     * until resume (#26) exists, and reading an absent field as false is what an absent one means.
     */
    val resumed: Boolean,
    val clientDisconnected: Boolean,
    val status: Int?,
    /** The paths this turn's tools named, which the pane shows through [pathLabel] and not raw. */
    val paths: List<String>,
)

/**
 * The pane's rows for one session, from the exchanges endpoint's own answer, in its own order, each
 * with whatever [heard] has for that exchange id folded onto it. The endpoint decides which rows
 * there are and in what order; the feed only fills in what a summary row does not carry.
 *
 * Null means the answer could not be read at all, which is a different thing from a session with
 * nothing in it and must not be reported as one: "this session has done nothing" is the one wrong
 * thing to say about a truncated answer. It is news rather than a throw, because the answer came
 * over a socket from another process. A row naming no id is dropped rather than kept under a blank
 * one, since the list is drawn keyed by it and two blanks would take the window down.
 */
internal fun exchangeRows(body: String, heard: (String) -> JsonObject?): List<ExchangeRow>? =
    runCatching {
        // `exchanges` has to be there and has to be an array, or this is not the endpoint
        // answering: absent or of another shape is unreadable, not a session with nothing in it.
        (Json.parseToJsonElement(body) as JsonObject)
            .let { it["exchanges"] as JsonArray }
            .filterIsInstance<JsonObject>()
            .map { rowOf(it, heard(it["id"].text().orEmpty())) }
            .filter { it.id.isNotEmpty() }
    }
    .getOrNull()

/** One summary row, and the completed line for it when the window heard one. */
private fun rowOf(summary: JsonObject, line: JsonObject?): ExchangeRow =
    ExchangeRow(
        id = summary["id"].text().orEmpty(),
        at = summary["receivedAt"].text().orEmpty(),
        agent = summary["agent"].text(),
        model = line?.get("model").text(),
        usage = line?.let(::usageText),
        costUsd = line?.scalar("costUsd") { doubleOrNull },
        latencyMs = line?.scalar("latencyMs") { longOrNull },
        replayHit = line?.scalar("replayHit") { booleanOrNull } == true,
        resumed = line?.scalar("resumed") { booleanOrNull } == true,
        // Either source saying the client left is the client having left: the summary is the
        // store's
        // flag and the line is the deriver's reading of the same exchange.
        clientDisconnected =
            summary.scalar("clientDisconnected") { booleanOrNull } == true ||
                line?.scalar("clientDisconnected") { booleanOrNull } == true,
        status = summary.scalar("status") { intOrNull },
        paths = paths(line),
    )

/**
 * What a pane writes where a path would go. The one function every path in every pane goes through,
 * so that the show-paths toggle has one place to be obeyed and no pane can forget it: hidden, a
 * file is its plant's short hash, which is stable for the life of the file and reveals nothing —
 * the same file reads the same in the timeline, in the touch history and in a screenshot of either.
 *
 * `String.hashCode` is specified, so the stand-in is the same on every JVM, for the reason
 * `nameFor` relies on: a villager and a file both have to look the same in every run.
 */
fun pathLabel(path: String, hidden: Boolean): String =
    if (hidden) "file ${shortHash(path)}" else path

/**
 * One line of a crop's touch history. The villager's name is not a path and is written plainly; the
 * time is the event line's own `ts`, and a line that carried none says so rather than inventing
 * one.
 */
internal fun touchText(touch: Touch, name: String): String =
    "${touch.ts ?: "no time given"}  $name  ${touch.kind.name.lowercase(Locale.ROOT)}"

/**
 * The body in a `GET /exchanges/{id}` answer, capped, with what was left off said out loud. Bodies
 * are prompts: they are never logged, never put in an error, and only the one being looked at is
 * held anywhere.
 */
internal fun bodyText(detail: String): String = runCatching {
    val body = (Json.parseToJsonElement(detail) as JsonObject)["requestBody"].text().orEmpty()
    if (body.length <= BODY_CAP) body
    else body.take(BODY_CAP) + "\n\n… truncated, ${body.length} bytes in all"
}
    .getOrDefault("the proxy answered something this window cannot read as an exchange")

private fun shortHash(path: String): String =
    path.hashCode().toUInt().toString(HASH_RADIX).takeLast(HASH_DIGITS).padStart(HASH_DIGITS, '0')

/** What the turn reported using, or null for a line that reported nothing at all. */
private fun usageText(line: JsonObject): String? {
    val usage = line["usage"] as? JsonObject ?: return null
    fun count(name: String) = usage.scalar(name) { intOrNull } ?: 0
    return "${count("input")} in, ${count("output")} out, " +
        "${count("cacheRead")} cached, ${count("cacheWrite")} written"
}

/** Every path the turn's tools named, in the order the turn named them; a line has none. */
private fun paths(line: JsonObject?): List<String> =
    (line?.get("tools") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull {
        it["path"].text()
    }

/**
 * What the panes are showing and how it got there. Its state is touched only from the thread
 * `AppModel.watch` runs on — a Compose Desktop click handler is on the window's thread, which is
 * that one — for exactly the reason `AppModel.showPaths` documents.
 *
 * Nothing here throws at a click: a load that fails leaves its reason in [note], and a load still
 * running is cancelled by the next one, so two quick clicks cannot answer in the wrong order.
 */
class PaneModel {
    /** What was clicked, or nothing, which is a closed pane. */
    var selected by mutableStateOf<Hit?>(null)
        private set

    /** The selected villager's timeline, as the exchanges endpoint gave it, newest first. */
    val rows = mutableStateListOf<ExchangeRow>()

    /** What the pane is doing or why it cannot: one line, always safe to show. */
    var note by mutableStateOf<String?>(null)
        private set

    /** The body being looked at, which is the only one held anywhere. */
    var body by mutableStateOf<String?>(null)
        private set

    private val lines = LinkedHashMap<String, JsonObject>()

    private var scope: CoroutineScope? = null
    private var client: ControlClient? = null
    private var loading: Job? = null

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
        loading?.cancel()
        loading = null
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

    /** A click on the farm. A crop answers from the farm itself; a villager asks the proxy. */
    fun select(hit: Hit) {
        loading?.cancel()
        rows.clear()
        body = null
        selected = hit
        note = null
        if (hit is Hit.OnVillager) load(hit.id)
    }

    fun dismiss() {
        loading?.cancel()
        selected = null
        rows.clear()
        body = null
        note = null
    }

    /**
     * The body of one exchange, fetched on demand. Refused outright while paths are hidden: a body
     * is nothing but paths and prompts, and a pane that would mask it line by line would be one
     * mask away from leaking the lot.
     */
    fun showBody(id: String, hidden: Boolean) {
        val asking = client
        if (hidden) return
        if (asking == null) {
            note = "there is no proxy to ask"
            return
        }
        loading?.cancel()
        body = null
        note = "reading exchange $id"
        loading = scope?.launch {
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
        val asking = client
        if (asking == null) {
            note = "there is no proxy to ask"
            return
        }
        note = "asking the proxy for $session's exchanges"
        // ponytail: one page, at the endpoint's own default, and `nextCursor` is ignored, so a
        // session past a page shows its newest. Upgrade: pass the cursor back when someone
        // scrolls to the end.
        loading = scope?.launch {
            asking
                .get("/exchanges?session=${session.encodeURLParameter()}")
                .fold(
                    { answer ->
                        val read = exchangeRows(answer, lines::get)
                        rows.addAll(read.orEmpty())
                        note =
                            when {
                                read == null -> "that session's exchanges came back unreadable"
                                read.isEmpty() -> "no exchanges for $session yet"
                                else -> null
                            }
                    },
                    { note = "that session's exchanges could not be read: ${it.message}" },
                )
        }
    }
}
