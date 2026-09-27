package dev.peashoot.app

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.snapshot
import dev.peashoot.app.render.Hit
import java.io.BufferedWriter
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Godot executable that draws the farm; unset, the farm stays in the window's own tab. */
private const val GODOT_ENV = "PEASHOOT_GODOT"

/** The Godot project the farm is, which is `farm/` in a checkout. */
private const val PROJECT_ENV = "PEASHOOT_FARM_PROJECT"
private const val DEFAULT_PROJECT = "farm"

/**
 * How often the app says it is still there: an empty line, so that a window whose app has gone
 * closes itself. A pipe that has been closed reads the same as one with nothing on it yet, and
 * Godot can only ask after its own children, not its parent, so silence is the one sign it has; it
 * gives up after three of these go missing.
 */
private const val HEARTBEAT_MILLIS = 5_000L

/** How to start the farm window, or null when no Godot is configured. */
internal fun farmWindowCommand(env: (String) -> String? = System::getenv): List<String>? {
    val godot = env(GODOT_ENV)?.takeIf { it.isNotBlank() } ?: return null
    val project = env(PROJECT_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROJECT
    return listOf(
        godot,
        "--path",
        project,
        "--",
        "--from-app",
    )
}

/**
 * What a line from the farm window says was clicked, or null for anything else. Godot writes its
 * own banner and warnings to the same stream, so a line that is not one of ours is not an error,
 * just not a click.
 */
internal fun hitOf(line: String): Hit? {
    val click = if (line.startsWith("{")) objectOf(line) else null
    val villager = click?.string("villager")
    val crop = click?.string("crop")
    return when {
        villager != null -> Hit.OnVillager(villager)
        crop != null -> Hit.OnCrop(crop)
        else -> null
    }
}

private fun objectOf(line: String): JsonObject? =
    try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Runs the farm window until it closes: every farm [farms] gives is written to it as one line, and
 * every click it reports goes to [onHit] on the caller's own thread. Only the latest farm matters,
 * so a window slower than the feed is sent the newest one rather than a backlog.
 *
 * ponytail: a window closed by hand stays closed until the app starts again. Upgrade: a button that
 * opens it again, once the Compose farm is retired and the window is the only farm there is.
 */
internal suspend fun runFarmWindow(
    command: List<String>,
    farms: Flow<FarmState>,
    onHit: (Hit) -> Unit,
) {
    val process =
        try {
            withContext(Dispatchers.IO) {
                ProcessBuilder(command).redirectError(Redirect.DISCARD).start()
            }
        } catch (_: IOException) {
            return
        }
    try {
        coroutineScope {
            val hits = Channel<Hit>(Channel.UNLIMITED)
            val writing =
                launch(Dispatchers.IO) {
                    try {
                        process.outputStream.bufferedWriter().use { writeFarms(it, farms) }
                    } catch (_: IOException) {
                        // The window has gone; the reader below sees it end and so does the scope.
                    }
                }
            launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        hitOf(line)?.let { hits.send(it) }
                    }
                }
                hits.close()
                writing.cancel()
            }
            // Clicks are answered here, on the caller's own thread, which is the one the panes
            // it hands them to are written from.
            for (hit in hits) onHit(hit)
        }
    } finally {
        process.destroy()
    }
}

/**
 * Every farm as a line, and an empty line every [HEARTBEAT_MILLIS] whatever the farm is doing. Both
 * go through one lock, so a heartbeat never lands in the middle of a farm.
 */
private suspend fun writeFarms(out: BufferedWriter, farms: Flow<FarmState>) = coroutineScope {
    val lock = Mutex()
    launch {
        while (true) {
            delay(HEARTBEAT_MILLIS)
            lock.withLock {
                out.newLine()
                out.flush()
            }
        }
    }
    farms.conflate().collect { farm ->
        lock.withLock {
            out.write(snapshot(farm))
            out.newLine()
            out.flush()
        }
    }
}
