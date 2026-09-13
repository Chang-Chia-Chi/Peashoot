package dev.peashoot.proxy

import dev.peashoot.core.Exchange
import dev.peashoot.core.Frame
import dev.peashoot.core.Mode
import io.ktor.http.Headers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** A stored exchange: the context object rebuilt from its row, plus the frames it produced. */
data class Recorded(val exchange: Exchange, val frames: List<Frame>)

/**
 * The store of record: `peashoot.db` in the data directory, plus `bodies/` for request bodies and
 * frame lists over 64 KB, named by their SHA-256. One connection, one caller at a time, off the
 * request threads.
 */
class Store(home: Path) : AutoCloseable {
    private val bodies: Path =
        Files.createDirectories(home.resolve("bodies")).also { dir ->
            // A spill interrupted between staging and its move leaves a .tmp nothing references.
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().endsWith(".tmp") }.forEach(Files::delete)
            }
        }
    private val connection: Connection =
        DriverManager.getConnection("jdbc:sqlite:${home.resolve("peashoot.db")}").also { c ->
            c.createStatement().use {
                it.executeUpdate(SCHEMA)
                it.executeUpdate(INDEX)
            }
        }
    private val lock = Mutex()

    suspend fun put(exchange: Exchange, frames: List<Frame>): Unit = io {
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
        val marks = columns.keys.joinToString { "?" }
        connection.prepareStatement("INSERT INTO exchange ($names) VALUES ($marks)").use { statement
            ->
            columns.values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }
    }

    suspend fun get(id: String): Recorded? = io {
        connection.prepareStatement("$SELECT WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toRecorded() else null }
        }
    }

    /** Newest first. */
    suspend fun list(limit: Int = DEFAULT_LIMIT): List<Recorded> = io {
        connection.prepareStatement("$SELECT ORDER BY received_at DESC, id DESC LIMIT ?").use {
            statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.toRecorded() else null }.toList()
            }
        }
    }

    /** Waits for an in-flight call rather than closing the connection under it. */
    override fun close() = runBlocking { lock.withLock { connection.close() } }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) { lock.withLock { block() } }

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
        const val INLINE_LIMIT = 64 * 1024
        const val DEFAULT_LIMIT = 100
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
        const val INDEX =
            "CREATE INDEX IF NOT EXISTS exchange_received_at ON exchange (received_at)"
        const val SELECT = "SELECT * FROM exchange"
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
