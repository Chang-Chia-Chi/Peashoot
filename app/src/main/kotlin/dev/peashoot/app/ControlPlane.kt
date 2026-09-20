package dev.peashoot.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.peashoot.app.farm.scalar
import dev.peashoot.core.Mode
import dev.peashoot.core.text
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// The control plane (#23): what every screen that writes to the proxy shares, and the routes and
// config screens themselves. The rules editor is in `RulesEditor.kt` and the export dialog in
// `CassetteExport.kt`, each for the reason a file gets split here — detekt's eleven functions to a
// class — and because a test-before-save and a preview-before-write are two state machines, not
// one. Everything pure in these three files is asserted without a window, as #22's halves are.

/** How much of a refusal that is not a problem object is shown, when something else answered. */
private const val REFUSAL_CAP = 300

/**
 * The farm's day boundary until a proxy has said otherwise, and again for one that cannot. It is
 * `ProxyConfig`'s default written out a second time on purpose: nothing in this module knows the
 * proxy is Kotlin, and a window with no boundary at all would never end a day.
 */
private val DEFAULT_IDLE: Duration = Duration.ofMinutes(30)

/** The widest idle window this window will take from a proxy; see [idleAfterOf]. */
private const val MAX_IDLE_MINUTES = 1440L

/** Pretty, because a config and a rule set are read by people: the reason `rules.json` is. */
private val PRETTY = Json { prettyPrint = true }

/**
 * One route as `GET /routes` serves it: what it does, whether a miss is refused, and from where.
 *
 * [mode] is null for a spelling this build has no [Mode] for, which is what a newer proxy serving a
 * mode that did not exist when this window was built looks like. The row still shows [spelled] —
 * refusing to draw a route because its mode is unfamiliar would hide the route — but no button
 * offers a mode this window cannot name, and switching away from it is still allowed.
 */
internal data class RouteRow(
    val name: String,
    val mode: Mode?,
    /** Exactly what the proxy called it, which is what is shown. */
    val spelled: String,
    val strict: Boolean,
    val cassette: String?,
)

/**
 * A proxy to ask and a scope to ask it from, which [ControlPlaneModel] is handed for as long as
 * [AppModel.watch] has both and passes to every panel. One object and not two fields per panel,
 * because a panel holding a scope but no client — or the other way about — is a state no panel
 * should have to have an opinion about.
 */
internal class Asking(private val scope: CoroutineScope, private val client: ControlClient) {
    /** [work], in the window's own scope, so what it writes is written on the window's thread. */
    fun launch(work: suspend (ControlClient) -> Unit): Job = scope.launch { work(client) }
}

/**
 * What every control-plane panel has: a proxy to ask, one call at a time, and one line saying what
 * it is doing or why it could not. Its state is touched only from the thread [AppModel.watch] runs
 * on — a Compose Desktop click handler is on the window's thread, which is that one — for exactly
 * the reason [AppModel.showPaths] documents.
 *
 * One call at a time is the whole of the answer to a stale response overwriting a fresh one: a
 * second call cannot start while the first is out, and the button that would start it is drawn
 * disabled, so there is never an older answer in flight to arrive late. It is also why nothing here
 * cancels-and-relaunches the way [PaneModel] does — a click on a villager replaces what the pane
 * was showing, where a `PUT` that has left is a change the proxy may already have made.
 *
 * Nothing here throws at a click: [ControlClient.send] answers a `Result` and a panel folds it.
 */
internal abstract class Panel {
    /**
     * What this panel is doing, or why it cannot: one line, always safe to show. It starts saying
     * there is no proxy, because the window draws this tab from the moment it opens and `watch`
     * attaches only once it has found or started one — a panel with nothing in it and nothing to
     * say would otherwise read as a proxy that answered with nothing.
     */
    var note by mutableStateOf<String?>("no proxy yet")
        protected set

    /** Whether a call is out. Every button this panel draws is disabled while it is. */
    var busy by mutableStateOf(false)
        private set

    private var asking: Asking? = null
    private var job: Job? = null

    internal open fun attach(asking: Asking) {
        this.asking = asking
        note = null
    }

    /**
     * Given up again, before the client is closed under it, for the reason [PaneModel.detach] is: a
     * panel asking a closed client would fail in a way nobody could read.
     */
    internal open fun detach() {
        job?.cancel()
        job = null
        asking = null
        // Set here and not in a `finally` inside the call: a cancelled call's unwinding runs after
        // whatever cancelled it has already moved on, and a `finally` would clear a flag that by
        // then belongs to something else.
        busy = false
    }

    /**
     * [work] against the proxy, with [doing] on screen until it answers. A call that arrives while
     * one is out is dropped rather than queued: the button is disabled, so the only way here is a
     * key repeat or a test, and neither wants two writes of the same thing.
     */
    protected fun start(doing: String, work: suspend (ControlClient) -> Unit) {
        val asking = asking
        when {
            asking == null -> note = "there is no proxy to ask"
            busy -> Unit
            else -> {
                busy = true
                note = doing
                job = asking.launch { client ->
                    // These panels are children of `AppModel.watch`'s scope, so a throwable out of
                    // one would cancel that scope and take the feed, the health poll and the farm
                    // clock down with it — the whole window lost to one bad control answer. Every
                    // call answers a `Result` and every parse is guarded, so nothing is expected
                    // here; what is caught is the unexpected, and a panel saying so is a window
                    // that still works. Cancellation is rethrown, because a cancelled panel is
                    // [detach] doing its job and has no news to report.
                    val failure = runCatching { work(client) }.exceptionOrNull()
                    if (failure is CancellationException) throw failure
                    busy = false
                    failure?.let { note = whyNot(doing, it) }
                }
            }
        }
    }
}

/**
 * The route table and the config as the proxy runs them, and the one thing this window changes
 * about either: a route's mode and its strictness. `PUT /routes/{name}` takes effect on the proxy's
 * next request, so a switch here is a switch in what the next call through the relay does, not a
 * preference this window keeps.
 *
 * Config is read-only on purpose. `GET /config` holds no secret by design — the token is not a
 * config value at all and `secretHeaders` is names only — so nothing here re-filters it; and a
 * general config editor is not what #23 asks for, where the route modes are.
 *
 * It also owns the other two panels, which is how the one thing they have to say to each other gets
 * said: saving a rule set changes what an export would redact, so it retires the export's preview.
 */
internal class ControlPlaneModel : Panel() {
    /** The routes as `GET /routes` last served them, in the proxy's own order. */
    val routes = mutableStateListOf<RouteRow>()

    /** `GET /config` as text, or null for a config this window has not read yet. */
    var config by mutableStateOf<String?>(null)
        private set

    /**
     * How long a session must be quiet before the farm ends its day with a card: the proxy's
     * `idleSessionMinutes`, as the last read of the config answered it. The proxy owns the number
     * because the day is about the traffic it sees, and it is read here because this is the one
     * place that reads the config at all.
     *
     * Not Compose state: the farm's clock reads it, the window never draws it, and a read that is
     * one poll old only ever moves a boundary measured in minutes.
     */
    var idleAfter: Duration = DEFAULT_IDLE
        private set

    val export = ExportModel()

    /**
     * Declared after [export] and told to retire its preview on a save: redaction runs under the
     * rules the proxy holds when the export runs, so a preview taken before a rule change is a
     * preview of what would have happened.
     */
    val rules = RulesModel { export.invalidate() }

    /** Given a proxy to ask and a scope to ask from, for as long as [AppModel.watch] has both. */
    internal fun attach(scope: CoroutineScope, client: ControlClient) {
        val asking = Asking(scope, client)
        attach(asking)
        rules.attach(asking)
        export.attach(asking)
        reload()
        rules.load()
    }

    override fun detach() {
        super.detach()
        rules.detach()
        export.detach()
    }

    /**
     * The routes and the config, in one call's worth of waiting, because they are one screen. One
     * of them failing leaves the other alone: either can be the half that arrived.
     */
    fun reload() =
        start("asking the proxy for its routes and config") { client ->
            client
                .send(HttpMethod.Get, "/routes")
                .fold(
                    { answer ->
                        val read = routesOf(answer)
                        // Replaced only when something was read: one truncated answer must not
                        // throw away a table that is on screen and right. Three answers, three
                        // sentences — unreadable is not empty, and empty is not fine.
                        read?.let {
                            routes.clear()
                            routes.addAll(it)
                        }
                        note =
                            when {
                                read == null -> "the proxy answered routes this window cannot read"
                                read.isEmpty() -> "the proxy serves no routes"
                                else -> null
                            }
                    },
                    { note = whyNot("read the routes", it) },
                )
            // Asked for whatever the routes did: either half can be the one that arrived, and a
            // config section stuck on "not read yet" because of a routes failure says nothing true.
            client
                .send(HttpMethod.Get, "/config")
                .fold(
                    {
                        config = pretty(it)
                        idleAfter = idleAfterOf(it)
                    },
                    { note = whyNot("read the config", it) },
                )
        }

    /**
     * One route's mode and strictness, which the proxy applies to its next request.
     *
     * The route is read from the proxy immediately before it is written, and never from the row on
     * screen. `PUT /routes/{name}` replaces the *whole* route, so everything this switch does not
     * mean to change has to be sent back with it — and a body saying nothing about the cassette
     * unpins a pinned route, which would leave a replay answering from any recording at all. The
     * row is the wrong thing to read it off: it is as old as the last poll, so another client that
     * pinned a cassette in between would have its pin written away by a switch that never knew
     * about it. A route the proxy does not have is said out loud rather than invented.
     */
    fun setMode(name: String, mode: Mode, strict: Boolean) =
        start("switching $name to ${mode.spelling}") { client ->
            client
                .send(HttpMethod.Get, "/routes")
                .fold(
                    { answer ->
                        val current = routesOf(answer)?.firstOrNull { it.name == name }
                        if (current == null) {
                            note = "the proxy has no route named $name to switch"
                        } else {
                            sendMode(client, current, mode, strict)
                        }
                    },
                    { note = whyNot("read $name before switching it", it) },
                )
        }

    /**
     * [route] put back with its mode and strictness replaced and everything else — the cassette —
     * as the proxy just said it was. The answer is the route the proxy now holds, so the row is
     * replaced from that rather than from what was asked for: what a switch did is the proxy's to
     * say. Every way out of here leaves the panel's line saying something other than what it was
     * doing, because "switching default to replay" left on screen for ever is a lie about a call
     * that finished.
     */
    private suspend fun sendMode(
        client: ControlClient,
        route: RouteRow,
        mode: Mode,
        strict: Boolean,
    ) {
        val body = buildJsonObject {
            put("mode", mode.spelling)
            put("strict", strict)
            put("cassette", route.cassette)
        }
        client
            .send(HttpMethod.Put, "/routes/${route.name.encodeURLPathPart()}", body.toString())
            .fold(
                { answer ->
                    val changed = routeOf(route.name, answer)
                    val at = routes.indexOfFirst { it.name == route.name }
                    when {
                        changed == null ->
                            note = "${route.name} was switched, but the answer could not be read"
                        at < 0 -> {
                            routes.add(changed)
                            note = null
                        }
                        else -> {
                            routes[at] = changed
                            note = null
                        }
                    }
                },
                { note = whyNot("switch ${route.name} to ${mode.spelling}", it) },
            )
    }
}

/**
 * The `detail` of an RFC 9457 problem object, which is what the proxy says is wrong with a call.
 * Anything else the answer might be — a body cut short, or HTML from something that is not the
 * proxy at all — is handed back capped rather than swallowed, because a refusal nobody can read is
 * still news, and an empty one says so in words rather than as a blank line.
 */
internal fun problemDetail(body: String): String =
    jsonOf(body)?.get("detail").text()
        ?: body.take(REFUSAL_CAP).ifBlank { "the proxy gave no reason" }

/**
 * An answer from the proxy as a JSON object, or null for one that is not: the whole of "did this
 * come back readable". It was written out eight times across the window before this — every pane
 * and every panel parsing and casting in its own `runCatching` — and a reading that is wrong once
 * is wrong everywhere, so it is one function.
 */
internal fun jsonOf(body: String): JsonObject? = runCatching {
    Json.parseToJsonElement(body) as JsonObject
}
    .getOrNull()

/**
 * `idleSessionMinutes` out of a `GET /config` answer, or [DEFAULT_IDLE] for anything this window
 * could not end a day on: a proxy old enough not to serve the key, an answer that is not readable
 * at all, and a number outside 1 to [MAX_IDLE_MINUTES]. Checked here and not only at the proxy
 * because this window connects to whatever is listening on the port, and because a day that ends on
 * every tick — or never — is the kind of wrong nothing on screen would say out loud.
 */
internal fun idleAfterOf(body: String): Duration =
    (jsonOf(body)?.get("idleSessionMinutes") as? JsonPrimitive)
        ?.longOrNull
        ?.takeIf { it in 1..MAX_IDLE_MINUTES }
        ?.let(Duration::ofMinutes) ?: DEFAULT_IDLE

/**
 * What a panel writes when a call did not work. A refusal is the proxy's own words, whole and
 * unedited: the one use a rule the parser would not take has is being read. Anything else never
 * reached the proxy, and saying which is which is the difference between "fix your rule" and "your
 * proxy has gone".
 */
internal fun whyNot(doing: String, failure: Throwable): String =
    when (failure) {
        is Refused -> "the proxy refused to $doing (${failure.status}): ${failure.detail}"
        else -> "the proxy could not be reached to $doing: ${failure.message}"
    }

/**
 * The routes in a `GET /routes` answer, in the order the proxy listed them; null for an answer this
 * window cannot read at all. Null and empty are kept apart for the reason [timelineOf] keeps them
 * apart: "this proxy has no routes" is the one wrong thing to say about a truncated answer.
 */
internal fun routesOf(body: String): List<RouteRow>? = runCatching {
    jsonOf(body)?.map { (name, value) -> rowOf(name, value as JsonObject) }
}
    .getOrNull()

/**
 * One route out of a `PUT /routes/{name}` answer, which names the route's shape and not its name.
 */
internal fun routeOf(name: String, body: String): RouteRow? =
    jsonOf(body)?.let { runCatching { rowOf(name, it) }.getOrNull() }

/**
 * JSON as a person reads it, or the answer exactly as it came when it turns out not to be JSON. A
 * screen showing a config is showing whatever the proxy said, not this window's idea of it.
 */
internal fun pretty(body: String): String =
    jsonOf(body)?.let { PRETTY.encodeToString(JsonObject.serializer(), it) } ?: body

/**
 * A route object, which must at least say what mode it is in to be a route at all. The spelling is
 * kept whatever it says and [Mode.of] is allowed to answer null, so a mode from a proxy newer than
 * this window shows as itself instead of taking the whole table down with it.
 */
private fun rowOf(name: String, route: JsonObject): RouteRow {
    val spelled = checkNotNull(route["mode"].text()) { "a route says what mode it is in" }
    return RouteRow(
        name = name,
        mode = Mode.of(spelled),
        spelled = spelled,
        strict = route.scalar("strict") { booleanOrNull } == true,
        cassette = route["cassette"].text(),
    )
}
