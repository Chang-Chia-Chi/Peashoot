package dev.peashoot.app.farm

import dev.peashoot.core.EDIT_TOOLS
import dev.peashoot.core.Mode
import dev.peashoot.core.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// What one event line says, field by field, and what each field means on the farm: the sky, night,
// stamina, the bin, water, and the paths a turn touched. `Farm.kt` decides what happens to whom;
// this is where a line from another process is read, which is always as "maybe".

/** The provider has no capacity: the sky over the whole farm, not one villager's bad luck. */
private const val OVERLOADED = 529

/** This key is rate-limited: the villager rests at the well until the window comes round. */
internal const val TOO_MANY_REQUESTS = 429

/** A turn that ended, rather than one that stopped to call a tool and will carry on. */
internal const val END_TURN = "end_turn"

/**
 * Answered, as against refused or failed: only these drop produce in the bin, and only one of these
 * can be a line that is no turn at all — a failure reported no usage because it failed.
 */
internal val ANSWERED = 200..299

/** What one event line says, read the way a line from another process has to be read: as maybe. */
internal fun status(event: JsonObject): Int? = event.scalar("status") { intOrNull }

/** What the turn reported using, or null for a turn that reported nothing at all. */
internal fun usage(event: JsonObject): JsonObject? = event["usage"] as? JsonObject

/**
 * The sky after one completed line, in precedence order: a client that left is lightning whatever
 * else the line says, an overloaded provider is a storm, a cache read is rain, and a turn that says
 * none of those is a clear one.
 *
 * ponytail: only a 529 *status* is a storm. An `overloaded_error` inside a 200 stream is a real
 * overload the client saw, and it is nowhere on the event line. Upgrade: read an error type, once
 * the Deriver emits one.
 *
 * ponytail: no decay. The weather lasts until the next completed line says otherwise, so a storm at
 * the end of a run is the farm's sky until something else happens. Upgrade: let [tick] clear it
 * after a while, if a sky stuck on lightning ever reads wrong.
 */
internal fun weatherOf(event: JsonObject): Weather =
    when {
        event.scalar("clientDisconnected") { booleanOrNull } == true -> Weather.LIGHTNING
        status(event) == OVERLOADED -> Weather.STORM
        (usage(event)?.scalar("cacheRead") { intOrNull } ?: 0) > 0 -> Weather.RAIN
        else -> Weather.CLEAR
    }

/** Water is what the turn reported as output; a turn that reported no usage carried none. */
internal fun outputTokens(event: JsonObject): Int =
    usage(event)?.scalar("output") { intOrNull } ?: 0

/** Replay is night. A line with no `mode`, or a spelling this app does not know, says nothing. */
internal fun nightAfter(night: Boolean, event: JsonObject): Boolean =
    event["mode"].text()?.let(Mode::of)?.let { it == Mode.REPLAY } ?: night

/**
 * Stamina after a completed line: what the provider's headers said is left, and the most this
 * villager has ever been told it had, because the line carries no limit for a bar to be a fraction
 * of. Nothing is forgotten by a line that says nothing: no `rateLimit`, or a null field inside one,
 * leaves the villager what it had.
 */
internal fun Stamina.after(event: JsonObject): Stamina {
    val limit = event["rateLimit"] as? JsonObject ?: return this
    val tokens = limit.scalar("remainingTokens") { longOrNull } ?: remainingTokens
    return Stamina(
        remainingTokens = tokens,
        remainingRequests = limit.scalar("remainingRequests") { longOrNull } ?: remainingRequests,
        peakTokens = listOfNotNull(peakTokens, tokens).maxOrNull(),
    )
}

/**
 * The bin after a completed line: one produce for a turn that actually ended, and the cost added
 * when the line names one. A null `costUsd` on a turn that reported usage is not a free turn —
 * subscription traffic and a model with no price both look like that — so it is counted instead,
 * and the ledger's silence can be explained rather than read as zero. A turn that reported no usage
 * either, a 429 or a 529, had nothing to price and is not counted: it is a failure, and the sky and
 * the well already say so.
 */
internal fun ShippingBin.shipped(event: JsonObject): ShippingBin {
    val cost = event.scalar("costUsd") { doubleOrNull }
    val ended =
        event["stopReason"].text() == END_TURN && status(event)?.let { it in ANSWERED } == true
    return ShippingBin(
        produce = if (ended) produce + 1 else produce,
        ledger = ledger + (cost ?: 0.0),
        unpriced = if (cost == null && usage(event) != null) unpriced + 1 else unpriced,
    )
}

/** The turn's tool calls; a `tools` of any other shape is a turn that named none. */
internal fun tools(event: JsonObject): List<JsonObject> =
    (event["tools"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

/**
 * The path a tool call touched, separators normalised, or null when it named none or only searched
 * one. One rule for both readers of it: what plants and grows crops, and what a day's card counts
 * as a file touched.
 */
internal fun touchedPath(tool: JsonObject): String? {
    val name = tool["name"].text()
    if (name != READ && name !in EDIT_TOOLS) return null
    return tool["path"].text()?.replace('\\', '/')
}

/**
 * One scalar field: null when it is absent, JSON null, or of some other type than the one asked.
 */
internal inline fun <T> JsonObject.scalar(name: String, read: JsonPrimitive.() -> T?): T? =
    (this[name] as? JsonPrimitive)?.read()
