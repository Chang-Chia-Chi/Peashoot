package dev.peashoot.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.peashoot.app.farm.scalar
import dev.peashoot.core.text
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The control plane (#23): what every screen that writes to the proxy shares, and the routes and
// config screens themselves. The rules editor is in `RulesEditor.kt` and the export dialog in
// `CassetteExport.kt`, each for the reason a file gets split here — detekt's eleven functions to a
// class — and because a test-before-save and a preview-before-write are two state machines, not
// one. Everything pure in these three files is asserted without a window, as #22's halves are.

/** How much of a refusal that is not a problem object is shown, when something else answered. */
private const val REFUSAL_CAP = 300

/** Pretty, because a config and a rule set are read by people: the reason `rules.json` is. */
private val PRETTY = Json { prettyPrint = true }

/**
 * One route as `GET /routes` serves it: what it does, whether a miss is refused, and from where.
 */
internal data class RouteRow(
    val name: String,
    val mode: String,
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
    /** What this panel is doing, or why it cannot: one line, always safe to show. */
    var note by mutableStateOf<String?>(null)
        protected set

    /** Whether a call is out. Every button this panel draws is disabled while it is. */
    var busy by mutableStateOf(false)
        private set

    private var asking: Asking? = null
    private var job: Job? = null

    internal open fun attach(asking: Asking) {
        this.asking = asking
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
                    work(client)
                    busy = false
                }
            }
        }
    }
}

/**
 * The route table and the config as the proxy runs them, and the one thing this window changes
 * about either: a route's mode. `PUT /routes/{name}` takes effect on the proxy's next request, so a
 * switch here is a switch in what the next call through the relay does, not a preference this
 * window keeps.
 *
 * Config is read-only on purpose. `GET /config` holds no secret by design — the token is not a
 * config value at all and `secretHeaders` is names only — so nothing here re-filters it; and a
 * general config editor is not what #23 asks for, where the route modes are.
 */
internal class ControlPlaneModel : Panel() {
    /** The routes as `GET /routes` last served them, in the proxy's own order. */
    val routes = mutableStateListOf<RouteRow>()

    /** `GET /config` as text, or null for a config this window has not read yet. */
    var config by mutableStateOf<String?>(null)
        private set

    val rules = RulesModel()
    val export = ExportModel()

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
     * The routes and the config, in that order and in one call's worth of waiting, because they are
     * one screen. A config that could not be read leaves the routes that could.
     */
    fun reload() =
        start("asking the proxy for its routes and config") { client ->
            val table = client.send(HttpMethod.Get, "/routes")
            table.fold(
                { answer ->
                    val read = routesOf(answer)
                    routes.clear()
                    routes.addAll(read.orEmpty())
                    // Three answers, three sentences: unreadable is not empty, and empty is not
                    // fine.
                    note =
                        when {
                            read == null -> "the proxy answered routes this window cannot read"
                            read.isEmpty() -> "the proxy serves no routes"
                            else -> null
                        }
                },
                { note = whyNot("read the routes", it) },
            )
            if (table.isFailure) return@start
            client
                .send(HttpMethod.Get, "/config")
                .fold({ config = pretty(it) }, { note = whyNot("read the config", it) })
        }

    /**
     * One route's mode and strictness, which the proxy applies to its next request. The answer is
     * the route the proxy now holds, so the row is replaced from it rather than from what was asked
     * for: what a switch did is the proxy's to say.
     */
    fun setMode(name: String, mode: String, strict: Boolean) =
        start("switching $name to $mode") { client ->
            val body = buildJsonObject {
                put("mode", mode)
                put("strict", strict)
            }
            client
                .send(HttpMethod.Put, "/routes/${name.encodeURLPathPart()}", body.toString())
                .fold(
                    { answer ->
                        val changed = routeOf(name, answer)
                        val at = routes.indexOfFirst { it.name == name }
                        when {
                            changed == null ->
                                note = "$name was switched, but the answer could not be read"
                            at < 0 -> routes.add(changed)
                            else -> {
                                routes[at] = changed
                                note = null
                            }
                        }
                    },
                    { note = whyNot("switch $name to $mode", it) },
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
    runCatching { (Json.parseToJsonElement(body) as JsonObject)["detail"].text() }.getOrNull()
        ?: body.take(REFUSAL_CAP).ifBlank { "the proxy gave no reason" }

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
    (Json.parseToJsonElement(body) as JsonObject).map { (name, value) ->
        rowOf(name, value as JsonObject)
    }
}
    .getOrNull()

/**
 * One route out of a `PUT /routes/{name}` answer, which names the route's shape and not its name.
 */
internal fun routeOf(name: String, body: String): RouteRow? = runCatching {
    rowOf(name, Json.parseToJsonElement(body) as JsonObject)
}
    .getOrNull()

/**
 * JSON as a person reads it, or the answer exactly as it came when it turns out not to be JSON. A
 * screen showing a config is showing whatever the proxy said, not this window's idea of it.
 */
internal fun pretty(body: String): String = runCatching {
    PRETTY.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(body) as JsonObject)
}
    .getOrDefault(body)

/** A route object, which must at least say what mode it is in to be a route at all. */
private fun rowOf(name: String, route: JsonObject): RouteRow =
    RouteRow(
        name = name,
        mode = checkNotNull(route["mode"].text()) { "a route says what mode it is in" },
        strict = route.scalar("strict") { booleanOrNull } == true,
        cassette = route["cassette"].text(),
    )
