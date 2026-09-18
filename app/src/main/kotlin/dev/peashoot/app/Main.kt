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
import dev.peashoot.core.homeDir
import java.nio.file.Path
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/** The port the proxy listens on unless its environment says otherwise. */
private const val DEFAULT_PORT = 8787

/** How many event lines the window keeps: a scrollback, not a second copy of the table. */
private const val MAX_LINES = 500

private const val POLL_MS = 200L
private const val HEALTH_MS = 2_000L

/** 10 seconds for a JVM to start and bind: "within a few seconds", with room for a cold disk. */
private const val PROXY_TRIES = 50

fun main() = application {
    val model = remember { AppModel() }
    LaunchedEffect(model) { model.watch() }
    DisposableEffect(model) { onDispose { model.close() } }
    Window(onCloseRequest = ::exitApplication, title = "Peashoot") { dashboard(model) }
}

/**
 * Where the proxy listens.
 *
 * ponytail: `PEASHOOT_PORT` or the default, never `peashoot.toml`'s `port`. Upgrade: read the file
 * too, once the app has any other reason to parse TOML.
 */
internal fun baseUrl(env: (String) -> String? = System::getenv): String =
    "http://127.0.0.1:${env("PEASHOOT_PORT")?.toIntOrNull() ?: DEFAULT_PORT}"

/**
 * What the window shows and the one thing that fills it: health as it is polled, the feed's lines
 * newest first, and whatever the connection is doing. The farm reducer replaces this (#17); until
 * then the window is a plain list, and everything worth testing is in [ControlClient].
 */
class AppModel(private val home: Path = homeDir(), private val url: String = baseUrl()) {
    var status by mutableStateOf("looking for a proxy on $url")
        private set

    var health by mutableStateOf<Health?>(null)
        private set

    val lines = mutableStateListOf<String>()

    /** Only ever a proxy this app started: one that was already up belongs to whoever ran it. */
    private var owned: OwnedProxy? = null

    /** Runs until the window closes: a proxy if there is none, then health and the feed. */
    suspend fun watch() {
        ControlClient(url, { readToken(home) }).use { client ->
            if (!ensureProxy(client)) return
            coroutineScope {
                launch { pollHealth(client) }
                client.events().collect { feed ->
                    when (feed) {
                        is Feed.State -> status = feed.detail
                        is Feed.Line -> add(feed)
                    }
                }
            }
        }
    }

    fun close() = owned?.close() ?: Unit

    /** True once something answers: the proxy already there, or the one started here. */
    private suspend fun ensureProxy(client: ControlClient): Boolean {
        if (answering(client)) return true
        status = "nothing on $url; starting a proxy"
        owned =
            try {
                OwnedProxy.start(home)
            } catch (e: IllegalStateException) {
                status = e.message ?: "no proxy is running, and none could be started"
                null
            }
        return owned != null && awaitAnswer(client)
    }

    private suspend fun awaitAnswer(client: ControlClient): Boolean {
        repeat(PROXY_TRIES) {
            if (answering(client)) return true
            delay(POLL_MS)
        }
        status = "the proxy was started but never answered on $url"
        return false
    }

    /** Health is the probe as well as the display: it is the one call that needs no token. */
    private suspend fun answering(client: ControlClient): Boolean {
        val answer = runCatching { client.health() }.getOrNull()
        answer?.let { health = it }
        return answer != null
    }

    private suspend fun pollHealth(client: ControlClient) {
        while (true) {
            delay(HEALTH_MS)
            runCatching { client.health() }.onSuccess { health = it }
        }
    }

    private fun add(line: Feed.Line) {
        lines.add(0, describe(line))
        while (lines.size > MAX_LINES) lines.removeAt(lines.lastIndex)
    }
}

/** One event line as one row: its feed id, when, what happened, and to which exchange. */
private fun describe(line: Feed.Line): String {
    fun field(name: String) = line.event[name]?.jsonPrimitive?.content.orEmpty()
    return "${line.id}  ${field("ts")}  ${field("event")}  ${field("exchangeId")}"
}

// Composables in camelCase, against the Compose convention: detekt's FunctionNaming is one of this
// build's fixed gates and does not exempt @Composable. The name is all that gives.
@Composable
private fun dashboard(model: AppModel) = MaterialTheme {
    Column(Modifier.fillMaxSize().padding(all = 12.dp)) {
        Text(model.status, style = MaterialTheme.typography.subtitle1)
        healthLines(model.health)
        Divider(Modifier.padding(vertical = 8.dp))
        Text("events, newest first", style = MaterialTheme.typography.caption)
        eventLines(model.lines)
    }
}

@Composable
private fun healthLines(health: Health?) {
    if (health == null) {
        Text("no health yet")
    } else {
        Text("peashoot ${health.version}, up ${health.uptimeSeconds}s")
        health.routes.forEach { (name, mode) -> Text("route $name: $mode") }
    }
}

@Composable
private fun eventLines(lines: List<String>) =
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
