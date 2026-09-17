package dev.peashoot.proxy

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** The control API's bearer token, in the data directory. */
const val TOKEN_FILE = "token"

/** Where the control API lives on the proxy's own port; nothing under it is ever relayed. */
const val CONTROL_PREFIX = "/_peashoot"

/** 256 bits: nothing on this machine guesses that before the heat death of the laptop. */
private const val TOKEN_BYTES = 32

/**
 * How many lines a feed subscriber may fall behind before it is cut off: minutes of a busy agent's
 * traffic, and a reconnect's backfill covers the rest.
 */
private const val FEED_BUFFER = 1024

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * What the control API serves from: the store, the route table the relay reads, the live event
 * feed, and the token every call but health must carry. Built before the chain, so replay and the
 * deriver are given its table and its feed.
 */
class ControlApi(val store: Store, home: Path, config: ProxyConfig) {
    val routes = RouteTable(config.routes)
    val feed = EventFeed()
    internal val started = TimeSource.Monotonic.markNow()
    private val expected = "Bearer ${loadToken(home)}".toByteArray()

    /**
     * Compared in constant time, so a wrong guess learns nothing from how long the refusal took.
     */
    fun authorized(authorization: String?): Boolean =
        MessageDigest.isEqual(authorization.orEmpty().toByteArray(), expected)
}

/**
 * The live half of `GET /events`: each line the table stored, with its id. Every subscriber has its
 * own bounded buffer and publishing never waits, so a slow reader never holds up the deriver. A
 * subscriber whose buffer is full is cut off instead: it still receives what it had buffered, then
 * its response ends, and a reconnect with the last id it saw backfills the rest from the table.
 * Falling behind costs the reader a reconnect, never a line.
 */
class EventFeed {
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<Pair<Long, JsonObject>>>()

    fun publish(id: Long, event: JsonObject) = subscribers.forEach {
        if (it.trySend(id to event).isFailure) it.close()
    }

    /** Runs [block] with every line published from now until it returns. */
    suspend fun <T> subscribe(block: suspend (ReceiveChannel<Pair<Long, JsonObject>>) -> T): T {
        val channel = Channel<Pair<Long, JsonObject>>(FEED_BUFFER)
        subscribers += channel
        try {
            return block(channel)
        } finally {
            subscribers -= channel
        }
    }
}

/**
 * The token in [home], written on first start: staged owner-only, then moved into place, so no
 * reader ever sees it readable by others or half written. A token already there is kept as it is,
 * permissions included; so is one another first start moved in first.
 */
private fun loadToken(home: Path): String {
    val file = home.resolve(TOKEN_FILE)
    if (Files.notExists(file)) {
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        val staging = Files.createTempFile(home, "token", ".tmp")
        try {
            restrictToOwner(staging)
            Files.writeString(
                staging,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            )
            Files.move(staging, file)
        } catch (_: FileAlreadyExistsException) {
            // Another start won the race; its token is the one to read.
        } finally {
            Files.deleteIfExists(staging)
        }
    }
    return Files.readString(file).trim()
}

/**
 * `rw-------` where the file system has POSIX permissions; on Windows, an ACL naming the owner
 * alone, which also drops the entries the data directory would have passed down.
 */
private fun restrictToOwner(file: Path) {
    val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
    val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
    when {
        posix != null -> posix.setPermissions(PosixFilePermissions.fromString("rw-------"))
        acl != null ->
            acl.acl =
                listOf(
                    AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(acl.owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                        .build()
                )
        else -> log.warn("{} cannot be restricted to its owner on this file system", file)
    }
}
