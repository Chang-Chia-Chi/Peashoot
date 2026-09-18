package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Messages
import dev.peashoot.core.Mode
import dev.peashoot.core.Redacted
import dev.peashoot.core.Route
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where `proxy export` writes, in the data directory. */
const val CASSETTES_DIR = "cassettes"

/** A cassette name is a file name in the data directory and a tag, so it may not name a path. */
internal val CASSETTE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/** The cassette format's version: a record with any other is refused, never guessed at. */
private const val VERSION = 1

/**
 * An export: the JSONL text, how many exchanges it holds, and the redactions it made, by the id of
 * the exchange they were in, in export order.
 */
class Export(val jsonl: String, val count: Int, val hits: Map<String, List<Redacted>>)

/**
 * Every live recording, oldest first, as cassette records with the redaction rules applied. Each of
 * [sessions] and [ids] narrows that when it is given, and both given narrows by both. An imported
 * cassette's rows are left out: they are already some cassette's. The fingerprint is kept as
 * recorded, so a redacted request still replays.
 *
 * ponytail: every recording is held in memory at once. Upgrade: page through the store and stream
 * lines to the file, if a cassette ever outgrows a heap.
 */
suspend fun exportCassette(
    store: Store,
    config: ProxyConfig,
    sessions: Collection<String> = emptyList(),
    ids: Collection<String> = emptyList(),
): Export {
    val hits = linkedMapOf<String, List<Redacted>>()
    val lines =
        store
            .list(Int.MAX_VALUE, ExchangeQuery(live = true))
            .asReversed()
            .filter { sessions.isEmpty() || it.exchange.client.session in sessions }
            .filter { ids.isEmpty() || it.exchange.id in ids }
            .map { recorded ->
                val found = mutableListOf<Redacted>()
                val line = recorded.toRecord(config, found).toString()
                if (found.isNotEmpty()) hits[recorded.exchange.id] = found
                line
            }
    return Export(lines.joinToString("") { "$it\n" }, lines.size, hits)
}

/**
 * Imports [file] as the cassette named by its base name, replacing whatever that cassette held, and
 * returns how many exchanges it has. Secret headers are dropped, as capture drops them. A record
 * the format does not allow fails the whole import, naming its line, before anything is replaced.
 */
suspend fun importCassette(store: Store, config: ProxyConfig, file: Path): Int {
    check(Files.isRegularFile(file)) { "no such cassette: $file" }
    val name = file.nameWithoutExtension
    check(CASSETTE_NAME.matches(name)) {
        "$file: '$name' is not a usable cassette name; the file's base name must match $CASSETTE_NAME"
    }
    val recordings =
        Files.readAllLines(file)
            .withIndex()
            .filter { it.value.isNotBlank() }
            .map { (i, line) ->
                val where = "$file:${i + 1}"
                // Every way a record can be wrong is one of these three, so none reaches the user
                // as a stack trace.
                try {
                    line.toRecorded(config.lowercaseSecretHeaders)
                } catch (e: IllegalArgumentException) {
                    error("$where: ${e.message}")
                } catch (e: IllegalStateException) {
                    error("$where: ${e.message}")
                } catch (e: DateTimeException) {
                    error("$where: ${e.message}")
                }
            }
    store.put(recordings, cassette = name)
    return recordings.size
}

/**
 * Design section 6's record. Request headers are the ones the rules keep, response headers all of
 * them, and neither ever a secret header: capture already dropped those, and a rule set that keeps
 * one must still not export it. A stream's frames keep their offsets; any other response is one
 * body.
 */
private fun Recorded.toRecord(config: ProxyConfig, hits: MutableList<Redacted>): JsonObject {
    val secrets = config.lowercaseSecretHeaders
    val request = exchange.request
    val response = checkNotNull(exchange.response) { "a stored exchange has one" }
    val redaction = config.redaction
    val reader = Messages.Reader().also { reader -> frames.forEach(reader::read) }
    return buildJsonObject {
        put("v", VERSION)
        put("fingerprint", exchange.fingerprint)
        putJsonObject("request") {
            put("method", request.method)
            put("path", request.path)
            put("headers", JsonObject(config.rules.keptHeaders(request.headers) - secrets))
            val json = request.json
            when {
                json != null -> put("body", redaction.request(json, hits))
                request.body.isNotEmpty() ->
                    put("body", redaction.text(request.body.decodeToString(), "request body", hits))
            }
        }
        putJsonObject("response") {
            put("status", response.status)
            put("headers", response.headers.toJson(without = secrets))
            if (response.headers.declaresEventStream()) {
                val redacted = frames.mapIndexed { i, frame ->
                    frame.copy(raw = redaction.text(frame.raw, "response frame $i", hits))
                }
                put("frames", redacted.toJson())
            } else {
                val body = frames.joinToString("") { it.raw }
                put("body", redaction.text(body, "response body", hits))
            }
        }
        putJsonObject("meta") {
            put("client", exchange.client.type)
            put("model", reader.model ?: Messages.model(request.json))
            put("usage", usageJson(reader.usage))
            put("stopReason", reader.stopReason)
            put("recordedAt", exchange.receivedAt.toString())
        }
    }
}

/**
 * The exchange a record describes, under the default route, less any [secrets] header. It takes a
 * fresh id, and its recorded time as its arrival, so a retry sequence keeps its order.
 */
private fun String.toRecorded(secrets: Set<String>): Recorded {
    val record = Json.parseToJsonElement(this) as? JsonObject ?: error("a record must be an object")
    val version = (record["v"] as? JsonPrimitive)?.intOrNull
    check(version == VERSION) { "cassette version ${record["v"]} is not $VERSION" }
    val request = record.field("request")
    val response = record.field("response")
    val recordedAt = (record["meta"] as? JsonObject)?.get("recordedAt")?.string("meta.recordedAt")
    val exchange =
        Exchange(
            Exchange.Request(
                request["method"].string("request.method"),
                request["path"].string("request.path"),
                request.field("headers").toHeaders(without = secrets),
                when (val body = request["body"]) {
                    null -> ByteArray(0)
                    is JsonPrimitive if body.isString -> body.content.encodeToByteArray()
                    else -> body.toString().encodeToByteArray()
                },
            ),
            route = DEFAULT_ROUTE,
            routing = Route(Mode.RECORD),
            receivedAt = recordedAt?.let(Instant::parse) ?: Instant.now(),
        )
    exchange.fingerprint = record["fingerprint"].string("fingerprint")
    val status = (response["status"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    exchange.response =
        Exchange.Response(
            requireNotNull(status) { "response.status must be a number" },
            response.field("headers").toHeaders(without = secrets),
        )
    return Recorded(exchange, response.frames())
}

/** A stream's frames as recorded; a whole body is one frame, and an empty one none, as captured. */
private fun JsonObject.frames(): List<Frame> =
    when (val frames = this["frames"]) {
        null ->
            listOfNotNull(
                this["body"]
                    .string("response.body")
                    .takeIf { it.isNotEmpty() }
                    ?.let { Frame(it, 0) }
            )
        else -> requireNotNull(frames as? JsonArray) { "response.frames must be a list" }.toFrames()
    }

private fun JsonObject.field(key: String): JsonObject =
    requireNotNull(this[key] as? JsonObject) { "$key must be an object" }
