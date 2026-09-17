package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Messages
import dev.peashoot.core.Mode
import dev.peashoot.core.Redacted
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where `proxy export` writes, in the data directory. */
const val CASSETTES_DIR = "cassettes"

/** The cassette format's version: a record with any other is refused, never guessed at. */
private const val VERSION = 1

/**
 * An export: the JSONL text, how many exchanges it holds, and every redaction it made, with the id
 * of the exchange each was in.
 */
class Export(val jsonl: String, val count: Int, val hits: List<Pair<String, Redacted>>)

/**
 * Every stored exchange, oldest first, or only [session]'s, as cassette records with the redaction
 * rules applied. The fingerprint is kept as recorded, so a redacted request still replays.
 *
 * ponytail: every recording is held in memory at once. Upgrade: page through the store and stream
 * lines to the file, if a cassette ever outgrows a heap.
 */
suspend fun exportCassette(store: Store, config: ProxyConfig, session: String?): Export {
    val hits = mutableListOf<Pair<String, Redacted>>()
    val lines =
        store
            .list(Int.MAX_VALUE)
            .asReversed()
            .filter { session == null || it.exchange.client.session == session }
            .map { recorded ->
                val found = mutableListOf<Redacted>()
                val line = recorded.toRecord(config, found).toString()
                found.mapTo(hits) { recorded.exchange.id to it }
                line
            }
    return Export(lines.joinToString("") { "$it\n" }, lines.size, hits)
}

/**
 * Imports [file] as the cassette named by its base name, replacing whatever that cassette held, and
 * returns how many exchanges it has. A record the format does not allow fails the whole import,
 * naming its line, before anything is replaced.
 */
suspend fun importCassette(store: Store, file: Path): Int {
    val recordings =
        Files.readAllLines(file)
            .withIndex()
            .filter { it.value.isNotBlank() }
            .map { (i, line) -> line.toRecorded("$file:${i + 1}") }
    store.put(recordings, cassette = file.nameWithoutExtension)
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
    val streamed =
        response.headers[HttpHeaders.ContentType]
            .orEmpty()
            .trimStart()
            .startsWith(
                ContentType.Text.EventStream.toString(),
                ignoreCase = true,
            )
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
            put("headers", response.headers.toJson(secrets))
            if (streamed) {
                put(
                    "frames",
                    buildJsonArray {
                        frames.forEachIndexed { i, frame ->
                            add(
                                buildJsonObject {
                                    put("t", frame.offsetMillis)
                                    put("raw", redaction.text(frame.raw, "response frame $i", hits))
                                }
                            )
                        }
                    },
                )
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
 * The exchange a record describes, under the default route. It takes a fresh id, and its recorded
 * time as its arrival, so a retry sequence keeps its order.
 */
private fun String.toRecorded(where: String): Recorded {
    val parsed =
        try {
            Json.parseToJsonElement(this)
        } catch (e: SerializationException) {
            error("$where: ${e.message}")
        }
    val record = parsed as? JsonObject ?: error("$where: a record must be a JSON object")
    val version = (record["v"] as? JsonPrimitive)?.intOrNull
    check(version == VERSION) { "$where: cassette version ${record["v"]} is not $VERSION" }
    val request = record.field<JsonObject>("request", where)
    val response = record.field<JsonObject>("response", where)
    val recordedAt = ((record["meta"] as? JsonObject)?.get("recordedAt") as? JsonPrimitive)?.content
    val exchange =
        Exchange(
            Exchange.Request(
                request.field<JsonPrimitive>("method", where).content,
                request.field<JsonPrimitive>("path", where).content,
                request.field<JsonObject>("headers", where).toHeaders(),
                when (val body = request["body"]) {
                    null -> ByteArray(0)
                    is JsonPrimitive if body.isString -> body.content.encodeToByteArray()
                    else -> body.toString().encodeToByteArray()
                },
            ),
            route = DEFAULT_ROUTE,
            mode = Mode.RECORD,
            receivedAt = recordedAt?.let(Instant::parse) ?: Instant.now(),
        )
    exchange.fingerprint = record.field<JsonPrimitive>("fingerprint", where).content
    exchange.response =
        Exchange.Response(
            checkNotNull(response.field<JsonPrimitive>("status", where).intOrNull) {
                "$where: response.status must be a number"
            },
            response.field<JsonObject>("headers", where).toHeaders(),
        )
    return Recorded(exchange, response.frames(where))
}

/** A stream's frames as recorded; a whole body is one frame, and an empty one none, as captured. */
private fun JsonObject.frames(where: String): List<Frame> {
    val frames =
        this["frames"]
            ?: return listOfNotNull(
                field<JsonPrimitive>("body", where)
                    .content
                    .takeIf { it.isNotEmpty() }
                    ?.let { Frame(it, 0) }
            )
    return (frames as? JsonArray ?: error("$where: response.frames must be a list")).map {
        val frame = it as? JsonObject ?: error("$where: a frame must be an object")
        Frame(
            frame.field<JsonPrimitive>("raw", where).content,
            frame.field<JsonPrimitive>("t", where).longOrNull
                ?: error("$where: a frame's t must be a number"),
        )
    }
}

private inline fun <reified T : JsonElement> JsonObject.field(key: String, where: String): T =
    this[key] as? T ?: error("$where: $key is required")

private fun JsonObject.toHeaders(): Headers = Headers.build {
    forEach { (name, values) ->
        (values as? JsonArray)?.forEach { append(name, (it as JsonPrimitive).content) }
    }
}

private fun Headers.toJson(without: Set<String>): JsonObject = buildJsonObject {
    entries()
        .filter { it.key.lowercase() !in without }
        .forEach { (name, values) -> put(name, JsonArray(values.map(::JsonPrimitive))) }
}
