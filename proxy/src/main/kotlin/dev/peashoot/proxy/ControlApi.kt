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
 * Shorter than this the file holds no token we wrote: base64url of 32 bytes is 43 characters, and a
 * hand-written one this short is a mistake worth refusing rather than a secret.
 */
private const val MIN_TOKEN_LENGTH = 32

/**
 * How many lines a feed subscriber may fall behind before it is cut off: minutes of a busy agent's
 * traffic, and a reconnect's backfill covers the rest.
 */
internal const val FEED_BUFFER = 1024

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

/**
 * What the control API serves from: the store, the route table the relay reads, the live event feed
 * the deriver publishes to, and the token every call but health must carry. The table and the feed
 * are made where the chain is, and handed to both.
 */
class ControlApi(
    val store: Store,
    home: Path,
    val routes: RouteTable,
    val feed: EventFeed = EventFeed(),
) {
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
class EventFeed(private val buffer: Int = FEED_BUFFER) {
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<Pair<Long, JsonObject>>>()

    /** How many feeds are open: one whose reader has gone is gone from here too. */
    internal val subscriberCount: Int
        get() = subscribers.size

    fun publish(id: Long, event: JsonObject) = subscribers.forEach {
        if (it.trySend(id to event).isFailure) it.close()
    }

    /** Runs [block] with every line published from now until it returns. */
    suspend fun <T> subscribe(block: suspend (ReceiveChannel<Pair<Long, JsonObject>>) -> T): T {
        val channel = Channel<Pair<Long, JsonObject>>(buffer)
        subscribers += channel
        try {
            return block(channel)
        } finally {
            subscribers -= channel
        }
    }
}

/**
 * The token in [home], written on first start: staged owner-only, then linked into place, so no
 * reader ever sees it readable by others or half written, and two starts at once cannot each end up
 * with a token of their own. A token already there is kept, and tightened if anyone else could read
 * it; one too short to be a token stops the proxy rather than guarding it with nothing.
 */
internal fun loadToken(home: Path): String {
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
            // A link, not a move: whatever the file system, it fails rather than replaces, so the
            // start that lost the race reads the winner's token instead of overwriting it.
            Files.createLink(file, staging)
        } catch (_: FileAlreadyExistsException) {
            // Another start won the race; its token is the one to read.
        } finally {
            Files.deleteIfExists(staging)
        }
    } else if (readableByOthers(file)) {
        // The permissions are said out loud, never the token: a token others could already read is
        // worth rotating, but only its owner can decide that.
        log.warn("{} was readable by others; restricting it to its owner", file)
        restrictToOwner(file)
    }
    val token = Files.readString(file).trim()
    check(token.length >= MIN_TOKEN_LENGTH) {
        "$file holds no usable control API token; delete it and start again for a fresh one"
    }
    return token
}

/** Whether anyone but the owner is allowed anything on [file]. */
private fun readableByOthers(file: Path): Boolean {
    val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
    val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
    return when {
        posix != null -> posix.readAttributes().permissions() != OWNER_ONLY
        acl != null -> acl.acl.any { it.principal() != acl.owner }
        else -> false
    }
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

/**
 * `rw-------` where the file system has POSIX permissions; on Windows, an ACL naming the owner
 * alone. Java writes that ACL protected (`D:PAI`), so the data directory's inheritable entries
 * neither stay on the file nor come back when the directory's own ACL changes.
 */
private fun restrictToOwner(file: Path) {
    val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
    val acl = Files.getFileAttributeView(file, AclFileAttributeView::class.java)
    when {
        posix != null -> posix.setPermissions(OWNER_ONLY)
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
