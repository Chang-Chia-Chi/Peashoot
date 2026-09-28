package dev.peashoot.app.farm

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The file in Peashoot's home that holds what the farm has earned between windows. */
private const val SAVE_FILE = "farm.json"

/**
 * What one window leaves the next: the purse, the plots bought, and the id of the last line that
 * went into the purse. The two are one write, so the next window resumes the feed from exactly
 * where the purse stops, and the proxy's store hands it every turn made while no window was open,
 * each counted once.
 */
data class Saved(val purse: Purse = Purse(), val land: Int = 0, val lastId: Long? = null)

/**
 * The save in [home], or a new farm's when there is none. One that cannot be read is also a new
 * farm's rather than a window that will not open.
 *
 * ponytail: an unreadable save is overwritten by the next line that earns, so its coins are lost.
 * Nothing but this file writes it, and it is written whole by a rename. Upgrade: keep the
 * unreadable one aside, if a save is ever edited by hand.
 */
fun readSave(home: Path): Saved {
    val json = parsed(home.resolve(SAVE_FILE)) ?: return Saved()
    return Saved(
        purse =
            Purse(
                coins = json.scalar("coins") { intOrNull } ?: 0,
                earned = json.scalar("earned") { intOrNull } ?: 0,
            ),
        land = json.scalar("land") { intOrNull } ?: 0,
        lastId = json.scalar("lastId") { longOrNull },
    )
}

/** The file as a JSON object, or null when it is missing or is not one. */
private fun parsed(file: Path): JsonObject? =
    try {
        Json.parseToJsonElement(Files.readString(file)) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }

/** [saved] as [home]'s save, written beside it and renamed over it, so no reader sees half. */
fun writeSave(home: Path, saved: Saved) {
    val file = home.resolve(SAVE_FILE)
    val next = home.resolve("$SAVE_FILE.new")
    val json = buildJsonObject {
        put("coins", saved.purse.coins)
        put("earned", saved.purse.earned)
        put("land", saved.land)
        saved.lastId?.let { put("lastId", it) }
    }
    Files.createDirectories(home)
    Files.writeString(next, json.toString())
    Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
