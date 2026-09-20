package dev.peashoot.proxy

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import dev.peashoot.core.Usage
import dev.peashoot.core.text
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

/**
 * A stored exchange: the context object rebuilt from its row, plus the frames it produced, and the
 * cassette a stored row belongs to. [Store.put] takes the cassette as its own argument.
 */
data class Recorded(
    val exchange: Exchange,
    val frames: List<Frame>,
    val cassette: String? = null,
    /**
     * The `exchange.completed` line the deriver wrote for it, as it wrote it, or null for a row
     * nothing here derived: an imported cassette's. What a turn used, cost, took, and whether it
     * was a replay hit lives on that line and nowhere else, so a summary row that did not carry it
     * could be filled in only by whoever happened to hear the line live (#85).
     */
    val completed: JsonObject? = null,
)

/**
 * Which exchanges [Store.list] returns: those of one [fingerprint], [cassette], [session], or
 * client type, only live recordings when [live], and only those after the exchange [cursor] in the
 * list's own order. Frames are read unless [frames] is off, which the control API's list turns off
 * because they are most of a row's size.
 */
data class ExchangeQuery(
    val fingerprint: String? = null,
    val cassette: String? = null,
    val session: String? = null,
    val client: String? = null,
    val cursor: String? = null,
    val live: Boolean = false,
    val frames: Boolean = true,
)

/**
 * One turn against one file: which turn it was, when, whose villager's it is, and the tools that
 * named that path. A turn that reads a file and then edits it is one row naming both.
 */
data class Touched(
    val eventId: Long,
    val exchangeId: String,
    val ts: String?,
    val session: String?,
    val agent: String?,
    val tools: List<String>,
)

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
                // Otherwise every recording fails at insert, each one a WARN line nobody reads.
                check("cassette" in it.createQuery(COLUMNS).mapTo(String::class.java).list()) {
                    "${home.resolve("peashoot.db")} predates cassettes: move it aside for a fresh " +
                        "one, since before v1 a schema change means a fresh database"
                }
                it.execute(INDEX)
                it.execute(FINGERPRINT_INDEX)
                it.execute(EVENT_SCHEMA)
                it.execute(EVENT_INDEX)
                it.execute(EVENT_EXCHANGE_INDEX)
                it.execute(SESSION_VIEW)
            }
        }

    /**
     * Stores [recordings]; live ones have no [cassette]. A cassette's recordings replace every row
     * it already had, in one transaction, so importing the same file on every start leaves it once.
     * A replaced row's spill file stays: it is named by its content, and a re-import reuses it.
     */
    suspend fun put(recordings: List<Recorded>, cassette: String? = null): Unit = io { handle ->
        handle.useTransaction<Exception> { tx ->
            if (cassette != null) {
                tx.createUpdate(DELETE_CASSETTE).bind("cassette", cassette).execute()
            }
            recordings.forEach { (exchange, frames) ->
                // Only a sourced exchange is persisted; one without a response is a programming
                // error.
                val response =
                    checkNotNull(exchange.response) { "exchange ${exchange.id} has no response" }
                val (body, bodyRef) = inlineOrSpill(exchange.request.body)
                val (framesInline, framesRef) =
                    inlineOrSpill(frames.toJson().toString().toByteArray())
                val columns =
                    linkedMapOf(
                        "id" to exchange.id,
                        "received_at" to exchange.receivedAt.toEpochMilli(),
                        "fingerprint" to exchange.fingerprint,
                        "route" to exchange.route,
                        // A stored row keeps the mode it ran under; strict and the cassette are
                        // the route's at the time, and the cassette column is put's own argument.
                        "mode" to exchange.mode.name,
                        "method" to exchange.request.method,
                        "path" to exchange.request.path,
                        "request_headers" to exchange.request.headers.toJson().toString(),
                        "request_body" to body,
                        "request_body_ref" to bodyRef,
                        "status" to response.status,
                        "response_headers" to response.headers.toJson().toString(),
                        "frames" to framesInline,
                        "frames_ref" to framesRef,
                        "client_disconnected" to exchange.clientDisconnected,
                        "cassette" to cassette,
                    )
                tx.createUpdate(INSERT).bindMap(columns).execute()
            }
        }
    }

    /** The exchange [id], or null; its frames are read unless [frames] is off. */
    suspend fun get(id: String, frames: Boolean = true): Recorded? = io { handle ->
        handle
            .createQuery("$SELECT WHERE id = :id")
            .bind("id", id)
            .map { rows, _ -> rows.toRecorded(bodies, frames) }
            .findOne()
            .orElse(null)
    }

    /**
     * Newest first, as [query] narrows them.
     *
     * A session or client is known from the exchange's event lines, joined by exchange id, so an
     * imported cassette's rows, which no deriver saw, match neither filter. ponytail: the client
     * filter reads it out of the event body, a scan of the event table per query. Upgrade: a
     * `client` column on `event` with an index, if `/exchanges?client=` ever feels slow.
     */
    suspend fun list(
        limit: Int = DEFAULT_LIMIT,
        query: ExchangeQuery = ExchangeQuery(),
    ): List<Recorded> = io { handle ->
        val filters =
            mapOf(
                    "fingerprint" to query.fingerprint,
                    "cassette" to query.cassette,
                    "session" to query.session,
                    "client" to query.client,
                    "cursor" to query.cursor,
                )
                .filterValues { it != null }
        val conditions =
            filters.keys.map { LIST_CONDITIONS[it] ?: "$it = :$it" } +
                listOfNotNull("cassette IS NULL".takeIf { query.live })
        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", "WHERE ")
        handle
            .createQuery("$SELECT $where ORDER BY received_at DESC, id DESC LIMIT :limit")
            .bind("limit", limit)
            .bindMap(filters)
            .map { rows, _ -> rows.toRecorded(bodies, query.frames) }
            .list()
    }

    /**
     * One event line, as the deriver built it: the object is the row's body, verbatim. Returns the
     * row's id, which is the line's place in the feed.
     */
    suspend fun putEvent(event: JsonObject): Long = io { handle ->
        val columns =
            linkedMapOf(
                "ts" to Instant.parse(event.getValue("ts").jsonPrimitive.content).toEpochMilli(),
                "event" to event.getValue("event").jsonPrimitive.content,
                "exchange_id" to event.getValue("exchangeId").jsonPrimitive.content,
                "session" to event["session"].text(),
                "agent" to event["agent"].text(),
                "body" to event.toString(),
            )
        handle
            .createUpdate(INSERT_EVENT)
            .bindMap(columns)
            .executeAndReturnGeneratedKeys("id")
            .mapTo(Long::class.java)
            .one()
    }

    /**
     * The event lines after the id [after], by id, oldest first: all of them from 0.
     *
     * ponytail: a backfill from 0 reads the whole table into memory. Upgrade: page it, if a feed
     * client ever asks for months of history at once.
     */
    suspend fun events(after: Long = 0): Map<Long, JsonObject> = io { handle ->
        handle
            .createQuery(SELECT_EVENTS)
            .bind("after", after)
            .map { rows, _ ->
                rows.getLong("id") to Json.parseToJsonElement(rows.getString("body")).jsonObject
            }
            .list()
            .toMap()
    }

    /**
     * Which turns touched [path], newest first, a page at a time: [cursor] is the event id of the
     * last row of the page before, and a page is [limit] rows.
     *
     * Read from the event table and not from the exchange table, which is the whole point of it: a
     * replay hit and a resumed answer are no rows there — neither made an upstream call — and both
     * touched the file all the same. Separators are normalised on both sides, so a file written on
     * Windows and read on a POSIX box is one file here, as it is one crop in the farm.
     *
     * ponytail: a scan of the completed lines per query, which a local tool's event count can
     * afford, as `/exchanges?client=` already does. Upgrade: a touch table the deriver fills, if
     * this ever feels slow.
     */
    suspend fun touches(path: String, limit: Int, cursor: Long?): List<Touched> = io { handle ->
        handle
            .createQuery(SELECT_TOUCHES)
            .bind("path", path)
            .bind("limit", limit)
            // The newest page asks for everything before the end of the table, so no cursor and a
            // cursor are one query and neither binds a null.
            .bind("cursor", cursor ?: Long.MAX_VALUE)
            .map { rows, _ -> rows.toTouched() }
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
                fingerprint TEXT,
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
                client_disconnected INTEGER NOT NULL,
                cassette TEXT
            )"""
        /** The columns the table has, which a database made before the last one added lacks. */
        const val COLUMNS = "SELECT name FROM pragma_table_info('exchange')"
        /**
         * Names the same 16 columns as [SCHEMA] and [put]'s map; RecorderTest's round trip is the
         * check when one is added.
         */
        const val INSERT =
            """INSERT INTO exchange (
                id, received_at, fingerprint, route, mode, method, path, request_headers,
                request_body, request_body_ref, status, response_headers, frames, frames_ref,
                client_disconnected, cassette
            ) VALUES (
                :id, :received_at, :fingerprint, :route, :mode, :method, :path, :request_headers,
                :request_body, :request_body_ref, :status, :response_headers, :frames, :frames_ref,
                :client_disconnected, :cassette
            )"""
        const val DELETE_CASSETTE = "DELETE FROM exchange WHERE cassette = :cassette"
        /** Matches the order [list] asks for, so newest-first needs no sort. */
        const val INDEX =
            """CREATE INDEX IF NOT EXISTS exchange_received_at_id
                ON exchange (received_at DESC, id DESC)"""
        /** What replay and resume look an exchange up by, newest first within one fingerprint. */
        const val FINGERPRINT_INDEX =
            """CREATE INDEX IF NOT EXISTS exchange_fingerprint
                ON exchange (fingerprint, received_at DESC)"""
        /**
         * Every column of the row, and with it the `exchange.completed` line the deriver wrote for
         * that exchange: usage, cost, latency and the replay flag are on that line alone, and a
         * summary row is where a pane needs them (#85). The newest such line wins, though only a
         * retried write would ever leave two.
         */
        const val SELECT =
            """SELECT exchange.*, (
                    SELECT body FROM event
                    WHERE event.exchange_id = exchange.id
                        AND event.event = 'exchange.completed'
                    ORDER BY event.id DESC LIMIT 1
                ) AS completed
                FROM exchange"""
        /**
         * [list]'s conditions that are not a plain column match. The cursor compares in the list's
         * own order, so a page continues where the last one ended; the control API refuses a cursor
         * naming no row, which would otherwise compare null and return nothing.
         */
        val LIST_CONDITIONS =
            mapOf(
                "session" to "id IN (SELECT exchange_id FROM event WHERE session = :session)",
                "client" to
                    "id IN (SELECT exchange_id FROM event " +
                        "WHERE json_extract(body, '$.client') = :client)",
                "cursor" to
                    "(received_at, id) < (SELECT received_at, id FROM exchange WHERE id = :cursor)",
            )

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
        /** What [SELECT] joins on: a summary row reads the completed line of its own exchange. */
        const val EVENT_EXCHANGE_INDEX =
            "CREATE INDEX IF NOT EXISTS event_exchange ON event (exchange_id)"
        /**
         * The completed lines whose tools name one path, newest first. The tool names are gathered
         * in SQL rather than in Kotlin so that the rule for what counts as this path — separators
         * normalised, the path matched whole — is written once, where the filter is.
         *
         * `char(92)` is the backslash, spelled that way because JDBI's own parser reads a backslash
         * in the SQL as an escape and loses track of where the string literal ends, which silently
         * binds the parameters to the wrong places.
         */
        const val SELECT_TOUCHES =
            """SELECT id, exchange_id, session, agent,
                    json_extract(body, '$.ts') AS line_ts,
                    (SELECT json_group_array(json_extract(tool.value, '$.name'))
                        FROM json_each(coalesce(json_extract(body, '$.tools'), '[]')) AS tool
                        WHERE replace(coalesce(json_extract(tool.value, '$.path'), ''),
                            char(92), '/') = :path) AS tools
                FROM event
                WHERE event = 'exchange.completed' AND id < :cursor
                    AND json_array_length(tools) > 0
                ORDER BY id DESC LIMIT :limit"""
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
        const val SELECT_EVENTS = "SELECT id, body FROM event WHERE id > :after ORDER BY id"
        const val SELECT_SESSIONS = "SELECT * FROM session ORDER BY session, agent"
    }
}

/** The bytes of a column that are in the row, or of the spill file in [bodies] that it names. */
private fun ResultSet.inlineOrSpilled(bodies: Path, inline: String, ref: String): ByteArray =
    getBytes(inline) ?: Files.readAllBytes(bodies.resolve(getString(ref)))

/**
 * One exchange row, with its frames unless [withFrames] is off, and the completed line the select
 * read beside it. A row mapper beside the others, rather than a member: it needs nothing of the
 * store but where the spilled bodies are.
 */
private fun ResultSet.toRecorded(bodies: Path, withFrames: Boolean = true): Recorded {
    val exchange =
        Exchange(
            Exchange.Request(
                getString("method"),
                getString("path"),
                Json.parseToJsonElement(getString("request_headers")).jsonObject.toHeaders(),
                inlineOrSpilled(bodies, "request_body", "request_body_ref"),
            ),
            route = getString("route"),
            // A row keeps the mode it ran under; strict and the cassette were the route's at the
            // time, and the cassette it belongs to now is [Recorded.cassette].
            routing = Route(Mode.valueOf(getString("mode"))),
            id = getString("id"),
            receivedAt = Instant.ofEpochMilli(getLong("received_at")),
        )
    exchange.response =
        Exchange.Response(
            getInt("status"),
            Json.parseToJsonElement(getString("response_headers")).jsonObject.toHeaders(),
        )
    exchange.fingerprint = getString("fingerprint")
    exchange.clientDisconnected = getBoolean("client_disconnected")
    val frames =
        if (!withFrames) emptyList()
        else
            Json.parseToJsonElement(
                    inlineOrSpilled(bodies, "frames", "frames_ref").decodeToString()
                )
                .jsonArray
                .toFrames()
    return Recorded(
        exchange,
        frames,
        getString("cassette"),
        getString("completed")?.let { Json.parseToJsonElement(it).jsonObject },
    )
}

private fun ResultSet.toTouched(): Touched =
    Touched(
        eventId = getLong("id"),
        exchangeId = getString("exchange_id"),
        // The line's own `ts` and not the row's epoch column, so a touch and the feed's line spell
        // one turn's time the same way.
        ts = getString("line_ts"),
        session = getString("session"),
        agent = getString("agent"),
        tools = Json.parseToJsonElement(getString("tools")).jsonArray.mapNotNull { it.text() },
    )

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

/**
 * Header names to lists of values, the shape both the store and a cassette keep, less the names in
 * [without] (lower-case).
 */
internal fun Headers.toJson(without: Set<String> = emptySet()): JsonObject = buildJsonObject {
    forEach { name, values ->
        if (name.lowercase() !in without) put(name, JsonArray(values.map(::JsonPrimitive)))
    }
}

/**
 * The headers [toJson] wrote, less the names in [without] (lower-case). A hand-written cassette may
 * give one value as a plain string; anything else is an IllegalArgumentException.
 */
internal fun JsonObject.toHeaders(without: Set<String> = emptySet()): Headers = Headers.build {
    filterKeys { it.lowercase() !in without }
        .forEach { (name, values) ->
            (values as? JsonArray ?: listOf(values)).forEach { append(name, it.string(name)) }
        }
}

/** The cassette shape: `[{"t": offsetMillis, "raw": text}]`. */
internal fun List<Frame>.toJson(): JsonArray = buildJsonArray {
    forEach { frame ->
        add(
            buildJsonObject {
                put("t", frame.offsetMillis)
                put("raw", frame.raw)
            }
        )
    }
}

/** The frames [toJson] wrote; anything else is an IllegalArgumentException. */
internal fun JsonArray.toFrames(): List<Frame> = map {
    val frame = requireNotNull(it as? JsonObject) { "a frame must be an object" }
    val offset = (frame["t"] as? JsonPrimitive)?.takeUnless { t -> t.isString }?.longOrNull
    Frame(
        frame["raw"].string("a frame's raw"),
        requireNotNull(offset) { "a frame's t must be a number" },
    )
}

/** This element as a string, or an IllegalArgumentException naming [what] it should have been. */
internal fun JsonElement?.string(what: String): String {
    val primitive = this as? JsonPrimitive
    require(primitive != null && primitive.isString) { "$what must be a string" }
    return primitive.content
}
