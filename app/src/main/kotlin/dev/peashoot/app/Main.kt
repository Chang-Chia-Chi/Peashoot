package dev.peashoot.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.reduce
import dev.peashoot.core.homeDir
import java.nio.file.Path
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** The port the proxy listens on unless its environment says otherwise. */
private const val DEFAULT_PORT = 8787

/** How many event lines the window keeps: a scrollback, not a second copy of the table. */
private const val MAX_LINES = 500

/** How often the app asks again whether the proxy it just started is up yet. */
private const val POLL_MS = 200L

/** How often the uptime and the route modes are refreshed once the proxy is up. */
private const val HEALTH_MS = 2_000L

/** 10 seconds for a JVM to start and bind: "within a few seconds", with room for a cold disk. */
private const val PROXY_TRIES = 50

fun main() = application {
    val model = remember { AppModel() }
    LaunchedEffect(model) { model.watch() }
    DisposableEffect(model) { onDispose { model.close() } }
    Window(onCloseRequest = ::exitApplication, title = "Peashoot") { Dashboard(model) }
}

/**
 * Which port the proxy is expected on. It is the port the app connects to and the port it starts a
 * proxy on, from one place, because a child told to bind a different one could never be reached.
 *
 * ponytail: `PEASHOOT_PORT` or the default, never `peashoot.toml`'s `port`. Upgrade: read the file
 * too, once the app has any other reason to parse TOML.
 */
private fun proxyPort(env: (String) -> String? = System::getenv): Int =
    env("PEASHOOT_PORT")?.toIntOrNull() ?: DEFAULT_PORT

/**
 * What the window shows and the one thing that fills it: health as it is polled, the feed's lines
 * newest first, the farm every line has been folded into, and whatever the connection is doing. The
 * renderer (#19, #20) replaces the list with the farm it is drawn from; everything worth testing is
 * in [ControlClient] and in [reduce], neither of which needs a window.
 */
class AppModel(private val home: Path = homeDir(), private val port: Int = proxyPort()) {
    private val url = "http://127.0.0.1:$port"

    var status by mutableStateOf("looking for a proxy on $url")
        private set

    var health by mutableStateOf<Health?>(null)
        private set

    val lines = mutableStateListOf<String>()

    /** Every line the window has heard, folded into one farm. */
    var farm by mutableStateOf(FarmState())
        private set

    /** Only ever a proxy this app started: one that was already up belongs to whoever ran it. */
    private var owned: OwnedProxy? = null

    /**
     * The last id the window has shown. Kept here and not only inside one feed, so that a feed
     * started again — after a token was fixed, or a window reopened — resumes rather than skipping
     * whatever happened in between.
     */
    private var lastId: Long? = null

    /** Runs until the window closes: a proxy if there is none, then health and the feed. */
    suspend fun watch() {
        ControlClient(url, { readToken(home) }).use { client ->
            if (!ensureProxy(client)) return
            coroutineScope {
                val polling = launch { pollHealth(client) }
                client.events(lastId).collect { feed ->
                    when (feed) {
                        is Feed.State -> status = feed.detail
                        is Feed.Line -> add(feed)
                    }
                }
                // The feed only ends when another attempt could not help, so there is nothing
                // left to poll for; without this the scope would wait on the poller for ever.
                polling.cancel()
            }
        }
    }

    fun close() = owned?.close() ?: Unit

    /**
     * True once Peashoot answers. A port that answers anything at all is a port a second proxy
     * could not bind, so only silence is taken as permission to start one.
     */
    private suspend fun ensureProxy(client: ControlClient): Boolean {
        val found = probe(client)
        if (found !is Probe.Silent) return found is Probe.Healthy
        status = "nothing on $url; starting a proxy"
        owned =
            try {
                OwnedProxy.start(home, port)
            } catch (e: IllegalStateException) {
                status = e.message ?: "no proxy is running, and none could be started"
                null
            }
        return owned != null && awaitAnswer(client)
    }

    private suspend fun awaitAnswer(client: ControlClient): Boolean {
        repeat(PROXY_TRIES) {
            if (probe(client) is Probe.Healthy) {
                // While the launcher script is still there to name it: once it has gone, the JVM
                // it left behind cannot be found from here, and would outlive the window.
                owned?.track()
                return true
            }
            delay(POLL_MS)
        }
        status = "the proxy was started but never answered on $url"
        return false
    }

    /**
     * The probe is the display as well as the decision: health is the one call needing no token.
     */
    private suspend fun probe(client: ControlClient): Probe =
        client.probe().also { if (it is Probe.Healthy) health = it.health }

    private suspend fun pollHealth(client: ControlClient) {
        while (true) {
            delay(HEALTH_MS)
            probe(client)
        }
    }

    private fun add(line: Feed.Line) {
        lastId = line.id
        farm = reduce(farm, line.event)
        lines.add(0, describe(line))
        while (lines.size > MAX_LINES) lines.removeAt(lines.lastIndex)
    }
}

/**
 * One event line as one row: its feed id, when, what happened, and to which exchange. The line is
 * JSON from another process, so a field that is missing, or is an object where a name was expected,
 * prints as nothing rather than throwing out of the collector that is reading the feed.
 */
private fun describe(line: Feed.Line): String {
    fun field(name: String) = (line.event[name] as? JsonPrimitive)?.content.orEmpty()
    return "${line.id}  ${field("ts")}  ${field("event")}  ${field("exchangeId")}"
}

/** The farm in one line, until there is a farm to look at (#19). */
private fun summary(farm: FarmState): String {
    val crops = farm.fields.values.sumOf { it.crops.size }
    return "farm: ${farm.villagers.size} villagers, ${farm.wellQueue.size} at the well, $crops crops"
}

// Block bodies, not expression bodies, throughout: without type resolution the Compose rules
// cannot tell what an expression-bodied composable returns, so `= Column { … }` puts a composable
// outside ComposableNaming and its neighbours rather than past them.
@Composable
private fun Dashboard(model: AppModel) {
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(all = 12.dp)) {
            Text(model.status, style = MaterialTheme.typography.subtitle1)
            HealthLines(model.health)
            Text(summary(model.farm))
            Divider(Modifier.padding(vertical = 8.dp))
            Text("events, newest first", style = MaterialTheme.typography.caption)
            EventLines(model.lines)
        }
    }
}

@Composable
private fun HealthLines(health: Health?) {
    Column {
        if (health == null) {
            Text("no health yet")
        } else {
            Text("peashoot ${health.version}, up ${health.uptimeSeconds}s")
            health.routes.forEach { (name, mode) -> Text("route $name: $mode") }
        }
    }
}

@Composable
private fun EventLines(lines: List<String>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
