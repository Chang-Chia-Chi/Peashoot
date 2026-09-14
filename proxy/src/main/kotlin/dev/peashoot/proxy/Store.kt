package dev.peashoot.proxy

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Mode
import dev.peashoot.core.Usage
import io.ktor.http.Headers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

/** A stored exchange: the context object rebuilt from its row, plus the frames it produced. */
data class Recorded(val exchange: Exchange, val frames: List<Frame>)

/** One row of the session view: what one session and agent have spent. */
data class Session(
    val session: String,
    val agent: String?,
    val exchanges: Int,
    val usage: Usage,
    val costUsd: Double?,
    val lastSeen: Instant,
)

/**
 * The store of record: `peashoot.db` in the data directory, plus `bodies/` for request bodies and
 * frame lists over 64 KB, named by their SHA-256. The only path to the database: one pool, so the
 * control API can read while the recorder writes, with WAL for the readers and a busy timeout to
 * queue the writers.
 */
class Store(home: Path) : AutoCloseable {
    private val bodies: Path =
        Files.createDirectories(home.resolve("bodies")).also { dir ->
            // A spill interrupted between staging and its move leaves a .tmp nothing references.
            // The next open sweeps it, which is soon enough for a local tool: no background task.
            dir.listDirectoryEntries("*.tmp").forEach { it.deleteIfExists() }
        }
    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl =
                    "jdbc:sqlite:${home.resolve("peashoot.db")}?journal_mode=WAL&busy_timeout=5000"
                poolName = "peashoot"
                maximumPoolSize = POOL_SIZE
            }
        )
    private val jdbi: Jdbi =
        Jdbi.create(pool).also { db ->
            db.useHandle<Exception> {
                // One statement per execute: sqlite-jdbc runs no more than that.
                it.execute(SCHEMA)
                it.execute(INDEX)
                it.execute(EVENT_SCHEMA)
                it.execute(EVENT_INDEX)
                it.execute(SESSION_VIEW)
            }
        }

    suspend fun put(exchange: Exchange, frames: List<Frame>): Unit = io { handle ->
        // Only a sourced exchange is persisted; one without a response is a programming error.
        val response = checkNotNull(exchange.response) { "exchange ${exchange.id} has no response" }
        val (body, bodyRef) = inlineOrSpill(exchange.request.body)
        val (framesInline, framesRef) = inlineOrSpill(frames.toJson().toByteArray())
        val columns =
            linkedMapOf(
                "id" to exchange.id,
                "received_at" to exchange.receivedAt.toEpochMilli(),
                "route" to exchange.route,
                "mode" to exchange.mode.name,
                "method" to exchange.request.method,
                "path" to exchange.request.path,
                "request_headers" to exchange.request.headers.toJson(),
                "request_body" to body,
                "request_body_ref" to bodyRef,
                "status" to response.status,
                "response_headers" to response.headers.toJson(),
                "frames" to framesInline,
                "frames_ref" to framesRef,
                "client_disconnected" to exchange.clientDisconnected,
            )
        handle.createUpdate(INSERT).bindMap(columns).execute()
    }

    suspend fun get(id: String): Recorded? = io { handle ->
        handle
            .createQuery("$SELECT WHERE id = :id")
            .bind("id", id)
            .map { rows, _ -> rows.toRecorded() }
            .findOne()
            .orElse(null)
    }

    /** Newest first. */
    suspend fun list(limit: Int = DEFAULT_LIMIT): List<Recorded> = io { handle ->
        handle
            .createQuery("$SELECT ORDER BY received_at DESC, id DESC LIMIT :limit")
            .bind("limit", limit)
            .map { rows, _ -> rows.toRecorded() }
            .list()
    }

    /** One event line, as the deriver built it: the object is the row's body, verbatim. */
    suspend fun putEvent(event: JsonObject): Unit = io { handle ->
        val columns =
            linkedMapOf(
                "ts" to Instant.parse(event.getValue("ts").jsonPrimitive.content).toEpochMilli(),
                "event" to event.getValue("event").jsonPrimitive.content,
                "exchange_id" to event.getValue("exchangeId").jsonPrimitive.content,
                "session" to event.text("session"),
                "agent" to event.text("agent"),
                "body" to event.toString(),
            )
        handle.createUpdate(INSERT_EVENT).bindMap(columns).execute()
    }

    /** Every event line the store holds, oldest first. */
    suspend fun events(): List<JsonObject> = io { handle ->
        handle
            .createQuery(SELECT_EVENTS)
            .map { rows, _ -> Json.parseToJsonElement(rows.getString("body")).jsonObject }
            .list()
    }

    suspend fun sessions(): List<Session> = io { handle ->
        handle.createQuery(SELECT_SESSIONS).map { rows, _ -> rows.toSession() }.list()
    }

    override fun close() = pool.close()

    /** Off the request threads, on a pooled connection returned when the block ends. */
    private suspend fun <T> io(block: (Handle) -> T): T =
        withContext(Dispatchers.IO) { jdbi.open().use(block) }

    /** Up to the limit the bytes go in the row; over it they go to a spill file the row names. */
    private fun inlineOrSpill(bytes: ByteArray): Pair<ByteArray?, String?> {
        if (bytes.size <= INLINE_LIMIT) return bytes to null
        val name = sha256(bytes)
        val target = bodies.resolve(name)
        if (Files.notExists(target)) {
            val staging = Files.createTempFile(bodies, "spill", ".tmp")
            Files.write(staging, bytes)
            Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        return null to name
    }

    private fun ResultSet.inlineOrSpilled(inline: String, ref: String): ByteArray =
        getBytes(inline) ?: Files.readAllBytes(bodies.resolve(getString(ref)))

    private fun ResultSet.toRecorded(): Recorded {
        val exchange =
            Exchange(
                Exchange.Request(
                    getString("method"),
                    getString("path"),
                    headersFromJson(getString("request_headers")),
                    inlineOrSpilled("request_body", "request_body_ref"),
                ),
                route = getString("route"),
                mode = Mode.valueOf(getString("mode")),
                id = getString("id"),
                receivedAt = Instant.ofEpochMilli(getLong("received_at")),
            )
        exchange.response =
            Exchange.Response(getInt("status"), headersFromJson(getString("response_headers")))
        exchange.clientDisconnected = getBoolean("client_disconnected")
        val frames = framesFromJson(inlineOrSpilled("frames", "frames_ref").decodeToString())
        return Recorded(exchange, frames)
    }

    private companion object {
        /**
         * Design section 3. SQLite's own measurements put the crossover where a blob reads faster
         * from a file than from the database at about 100 KB; 64 KB leaves headroom and keeps the
         * row small.
         */
        const val INLINE_LIMIT = 64 * 1024
        const val DEFAULT_LIMIT = 100
        const val POOL_SIZE = 4

        /**
         * Created if absent, never altered: before v1 a schema change means a fresh database. The
         * first release adds `PRAGMA user_version` and a migration per bump.
         */
        const val SCHEMA =
            """CREATE TABLE IF NOT EXISTS exchange (
                id TEXT PRIMARY KEY,
                received_at INTEGER NOT NULL,
                route TEXT NOT NULL,
                mode TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                request_headers TEXT NOT NULL,
                request_body BLOB,
                request_body_ref TEXT,
                status INTEGER NOT NULL,
                response_headers TEXT NOT NULL,
                frames BLOB,
                frames_ref TEXT,
                client_disconnected INTEGER NOT NULL
            )"""
        /**
         * Names the same 14 columns as [SCHEMA] and [put]'s map; RecorderTest's round trip is the
         * check when one is added.
         */
        const val INSERT =
            """INSERT INTO exchange (
                id, received_at, route, mode, method, path, request_headers, request_body,
                request_body_ref, status, response_headers, frames, frames_ref, client_disconnected
            ) VALUES (
                :id, :received_at, :route, :mode, :method, :path, :request_headers, :request_body,
                :request_body_ref, :status, :response_headers, :frames, :frames_ref,
                :client_disconnected
            )"""
        /** Matches the order [list] asks for, so newest-first needs no sort. */
        const val INDEX =
            """CREATE INDEX IF NOT EXISTS exchange_received_at_id
                ON exchange (received_at DESC, id DESC)"""
        const val SELECT = "SELECT * FROM exchange"

        /** The event line, kept whole in [body] so any tool reads the same JSON the file has. */
        const val EVENT_SCHEMA =
            """CREATE TABLE IF NOT EXISTS event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                event TEXT NOT NULL,
                exchange_id TEXT NOT NULL,
                session TEXT,
                agent TEXT,
                body TEXT NOT NULL
            )"""
        /** What the session view groups by. */
        const val EVENT_INDEX = "CREATE INDEX IF NOT EXISTS event_session ON event (session, agent)"
        /**
         * What one session and agent have spent, read back out of the stored event lines.
         *
         * ponytail: a scan of every completed event per query, which a local tool's event count can
         * afford. Upgrade: materialise this into a table the deriver upserts, if `/sessions` ever
         * feels slow.
         */
        const val SESSION_VIEW =
            """CREATE VIEW IF NOT EXISTS session AS
                SELECT session, agent, count(*) AS exchanges,
                    sum(coalesce(json_extract(body, '$.usage.input'), 0)) AS input_tokens,
                    sum(coalesce(json_extract(body, '$.usage.output'), 0)) AS output_tokens,
                    sum(coalesce(json_extract(body, '$.usage.cacheRead'), 0)) AS cache_read_tokens,
                    sum(coalesce(json_extract(body, '$.usage.cacheWrite'), 0))
                        AS cache_write_tokens,
                    sum(json_extract(body, '$.costUsd')) AS cost_usd,
                    max(ts) AS last_seen
                FROM event
                WHERE event = 'exchange.completed' AND session IS NOT NULL
                GROUP BY session, agent"""
        const val INSERT_EVENT =
            """INSERT INTO event (ts, event, exchange_id, session, agent, body)
                VALUES (:ts, :event, :exchange_id, :session, :agent, :body)"""
        /** The insertion order, which is arrival order: the id is the only monotonic column. */
        const val SELECT_EVENTS = "SELECT body FROM event ORDER BY id"
        const val SELECT_SESSIONS = "SELECT * FROM session ORDER BY session, agent"
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun ResultSet.toSession(): Session =
    Session(
        session = getString("session"),
        agent = getString("agent"),
        exchanges = getInt("exchanges"),
        usage =
            Usage(
                input = getInt("input_tokens"),
                output = getInt("output_tokens"),
                cacheRead = getInt("cache_read_tokens"),
                cacheWrite = getInt("cache_write_tokens"),
            ),
        // SQL sum skips nulls, so a session whose every exchange was unpriced has no cost at all.
        costUsd = getDouble("cost_usd").takeUnless { wasNull() },
        lastSeen = Instant.ofEpochMilli(getLong("last_seen")),
    )

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

private fun Headers.toJson(): String {
    val json = buildJsonObject {
        forEach { name, values -> put(name, JsonArray(values.map(::JsonPrimitive))) }
    }
    return json.toString()
}

private fun headersFromJson(text: String): Headers = Headers.build {
    Json.parseToJsonElement(text).jsonObject.forEach { (name, values) ->
        values.jsonArray.forEach { append(name, it.jsonPrimitive.content) }
    }
}

/** The cassette shape: `[{"t": offsetMillis, "raw": text}]`. */
private fun List<Frame>.toJson(): String {
    val json = buildJsonArray {
        forEach { frame ->
            add(
                buildJsonObject {
                    put("t", frame.offsetMillis)
                    put("raw", frame.raw)
                }
            )
        }
    }
    return json.toString()
}

private fun framesFromJson(text: String): List<Frame> =
    Json.parseToJsonElement(text).jsonArray.map {
        val frame = it.jsonObject
        Frame(frame.getValue("raw").jsonPrimitive.content, frame.getValue("t").jsonPrimitive.long)
    }
