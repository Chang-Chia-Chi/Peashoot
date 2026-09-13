package dev.peashoot.proxy

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Mode
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

/** A stored exchange: the context object rebuilt from its row, plus the frames it produced. */
data class Recorded(val exchange: Exchange, val frames: List<Frame>)

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
                it.execute(SCHEMA)
                it.execute(INDEX)
            }
        }

    suspend fun put(exchange: Exchange, frames: List<Frame>): Unit = io { handle ->
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
                "status" to exchange.status,
                "response_headers" to exchange.responseHeaders.toJson(),
                "frames" to framesInline,
                "frames_ref" to framesRef,
                "client_disconnected" to exchange.clientDisconnected,
            )
        val names = columns.keys.joinToString()
        val binds = columns.keys.joinToString { ":$it" }
        handle
            .createUpdate("INSERT INTO exchange ($names) VALUES ($binds)")
            .bindMap(columns)
            .execute()
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
        exchange.status = getInt("status").takeUnless { wasNull() }
        exchange.responseHeaders = headersFromJson(getString("response_headers"))
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
                status INTEGER,
                response_headers TEXT NOT NULL,
                frames BLOB,
                frames_ref TEXT,
                client_disconnected INTEGER NOT NULL
            )"""
        /** Matches the order [list] asks for, so newest-first needs no sort. */
        const val INDEX =
            """CREATE INDEX IF NOT EXISTS exchange_received_at_id
                ON exchange (received_at DESC, id DESC)"""
        const val SELECT = "SELECT * FROM exchange"
    }
}

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
