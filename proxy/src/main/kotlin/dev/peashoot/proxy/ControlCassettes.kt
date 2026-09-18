package dev.peashoot.proxy

import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * `GET /cassettes`, and the export and import the CLI runs, over HTTP: the same two functions, so a
 * cassette written here and one written by `proxy export` are the same file.
 */
internal fun Route.cassettes(api: ControlApi) {
    endpoint(HttpMethod.Get, "cassettes") { call.json(api.cassettesJson()) }
    export(api)
    endpoint(HttpMethod.Post, "cassettes/import") {
        val body = call.bodyObject()
        val file = refusing { Path.of(body["path"].string("path")) }
        val count = refusing { importCassette(api.store, api.live.current, file) }
        log.info("imported {} exchanges from {}", count, file)
        call.json(
            buildJsonObject {
                put("name", file.nameWithoutExtension)
                put("exchanges", count)
            }
        )
    }
}

/**
 * `POST /cassettes/export`, and with `?dryRun=true` the redaction preview alone: what each rule
 * would strip, where it was, and what it becomes, masked as the CLI masks it, because a preview
 * lands in terminals and CI logs and what a rule matched is usually the secret it exists to strip.
 * A dry run writes no file. `sessionIds` and `exchangeIds` each narrow what is exported when they
 * are given, and both given narrows by both.
 */
private fun Route.export(api: ControlApi) =
    endpoint(HttpMethod.Post, "cassettes/export") {
        val body = call.bodyObject()
        val name = refusing {
            body["name"].string("name").also {
                require(CASSETTE_NAME.matches(it)) {
                    "name must be a cassette name matching $CASSETTE_NAME, not '$it'"
                }
            }
        }
        val export = refusing {
            exportCassette(
                api.store,
                api.live.current,
                body.ids("sessionIds"),
                body.ids("exchangeIds"),
            )
        }
        val dryRun = call.request.queryParameters["dryRun"] == "true"
        val file =
            if (dryRun) null
            else
                Files.createDirectories(api.home.resolve(CASSETTES_DIR))
                    .resolve("$name.jsonl")
                    .also { Files.writeString(it, export.jsonl) }
        log.info("exported {} exchanges as {}, dry run {}", export.count, name, dryRun)
        call.json(
            buildJsonObject {
                put("name", name)
                put("dryRun", dryRun)
                put("path", file?.toString())
                put("exchanges", export.count)
                put("redactions", export.hits.values.sumOf { it.size })
                put(
                    "preview",
                    JsonArray(
                        export.hits.flatMap { (id, hits) ->
                            hits.map { hit ->
                                buildJsonObject {
                                    put("exchangeId", id)
                                    put("where", hit.where)
                                    put("matched", masked(hit.matched))
                                    put("becomes", hit.becomes)
                                }
                            }
                        }
                    ),
                )
            }
        )
    }

/** A list of strings under [key], or none; anything else is the caller's mistake. */
private fun JsonObject.ids(key: String): List<String> =
    (this[key] as? JsonArray)?.map { it.string(key) }
        ?: this[key]?.let { badRequest("$key must be a list of exchange or session ids, not $it") }
        ?: emptyList()

/**
 * What cassettes there are: the files in `cassettes/` and the names the store has rows tagged with,
 * merged by name, each with what is known of it. A name with no file was imported from elsewhere; a
 * file with no rows was written here and never imported back.
 *
 * ponytail: the rows are counted by reading them all. Upgrade: a GROUP BY in the store, if a home
 * ever holds enough recordings to feel it.
 */
private suspend fun ControlApi.cassettesJson(): JsonObject {
    val counts =
        store
            .list(Int.MAX_VALUE, ExchangeQuery(frames = false))
            .mapNotNull { it.cassette }
            .groupingBy { it }
            .eachCount()
    val dir = home.resolve(CASSETTES_DIR)
    val files =
        if (Files.isDirectory(dir))
            dir.listDirectoryEntries("*.jsonl").associateBy { it.nameWithoutExtension }
        else emptyMap()
    return buildJsonObject {
        put(
            "cassettes",
            JsonArray(
                (files.keys + counts.keys).sorted().map { name ->
                    val file = files[name]
                    buildJsonObject {
                        put("name", name)
                        put("exchanges", counts[name])
                        put("path", file?.toString())
                        put("bytes", file?.let { Files.size(it) })
                        put(
                            "modified",
                            file?.let { Files.getLastModifiedTime(it).toInstant().toString() },
                        )
                    }
                }
            ),
        )
    }
}
