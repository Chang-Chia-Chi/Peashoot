package dev.peashoot.app

import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.snapshot
import dev.peashoot.app.render.Hit
import java.io.BufferedWriter
import java.io.File
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

/**
 * The Godot project the farm is: `farm/` in a checkout, looked for from the repository root and
 * from one directory down, where `./gradlew :app:run` starts the app — the same two places the app
 * looks for the proxy's jar.
 */
private const val PROJECT_ENV = "PEASHOOT_FARM_PROJECT"
private val DEFAULT_PROJECTS = listOf("farm", "../farm")

/**
 * How often the app says it is still there: an empty line, so that a window whose app has gone
 * closes itself. A pipe that has been closed reads the same as one with nothing on it yet, and
 * Godot can only ask after its own children, not its parent, so silence is the one sign it has; it
 * gives up after three of these go missing.
 */
private const val HEARTBEAT_MILLIS = 5_000L

/** Where an installed app's resources are, the same property `OwnedProxy` reads. */
private const val PACKAGED_RESOURCES = "compose.application.resources.dir"

/** The farm `farm/export.sh` exports and a release carries, one name per OS it is built for. */
private val BUNDLED_FARMS = listOf("farm/peashoot-farm.exe", "farm/peashoot-farm.x86_64")

/**
 * How to start the farm window, or null when there is none to start. A Godot named by [GODOT_ENV]
 * runs the project in a checkout, which is how the farm is developed; failing that, an installed
 * app starts the farm it carries, exported by `farm/export.sh` into its resources.
 */
internal fun farmWindowCommand(
    env: (String) -> String? = System::getenv,
    isProject: (String) -> Boolean = { File(it, "project.godot").isFile },
    packaged: String? = System.getProperty(PACKAGED_RESOURCES),
    isFile: (File) -> Boolean = File::isFile,
): List<String>? {
    val godot = env(GODOT_ENV)?.takeIf { it.isNotBlank() }
    if (godot == null) {
        val bundled = packaged?.let { dir ->
            BUNDLED_FARMS.map { File(dir, it) }.firstOrNull(isFile)
        }
        return bundled?.let { listOf(it.path, "--", "--from-app") }
    }
    val project =
        env(PROJECT_ENV)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PROJECTS.firstOrNull(isProject)
            ?: DEFAULT_PROJECTS.first()
    return listOf(godot, "--path", project, "--", "--from-app")
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

/** Whether a line from the farm window is a click on the "For sale" sign. */
internal fun buysPlot(line: String): Boolean =
    line.startsWith("{") && objectOf(line)?.string("buy") == "plot"

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
 * every click it reports goes to [onHit], or to [onBuy] for the sign, on the caller's own thread.
 * Only the latest farm matters, so a window slower than the feed is sent the newest one rather than
 * a backlog.
 *
 * ponytail: a window closed by hand stays closed until the app starts again. Upgrade: a button that
 * opens it again, once the Compose farm is retired and the window is the only farm there is.
 */
internal suspend fun runFarmWindow(
    command: List<String>,
    farms: Flow<FarmState>,
    onHit: (Hit) -> Unit,
    onBuy: () -> Unit = {},
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
            val clicks = Channel<() -> Unit>(Channel.UNLIMITED)
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
                        val hit = hitOf(line)
                        when {
                            hit != null -> clicks.send { onHit(hit) }
                            buysPlot(line) -> clicks.send(onBuy)
                        }
                    }
                }
                clicks.close()
                writing.cancel()
            }
            // Clicks are answered here, on the caller's own thread, which is the one the panes
            // it hands them to are written from.
            for (click in clicks) click()
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
