package dev.peashoot.proxy

import dev.peashoot.core.EDIT_TOOLS
import dev.peashoot.core.ToolCall
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.peashoot.proxy")

const val GOURCE_FILE = "gource.log"

/**
 * A UUID's first eight hex characters are its conventional short form, and fit on a Gource node.
 */
private const val USER_LENGTH = 8

/**
 * Gource's action letters: a file added, a file modified. It also knows `D`, which no tool does.
 */
private const val ADDED = "A"
private const val MODIFIED = "M"

/**
 * Far enough apart to read in motion: green looks, teal searches, orange edits, red creates. A tool
 * not named here leaves the colour to Gource, which hashes the file's extension.
 */
private val TOOL_COLOURS =
    mapOf(
        "Read" to "4CAF50",
        "Grep" to "26A69A",
        "Glob" to "26C6DA",
        "Edit" to "FF9800",
        "MultiEdit" to "FF9800",
        "NotebookEdit" to "FF9800",
        "Write" to "F44336",
    )

/**
 * A pipe or a control character (a line break, most sharply) inside a field would forge another
 * field or a whole line: Gource cannot escape. `\r` and `\n` are themselves control characters, so
 * one class covers both the path (already checked below) and the session, which is used here too.
 */
private val FIELD_BREAKERS = Regex("[|\\p{Cntrl}]")

/**
 * The Gource custom-format log, so a repository can be watched as its agents move through it: one
 * line per file tool of a completed turn, the session as the committer. Never throws: a line that
 * cannot be written is one WARN, never a failed request.
 */
class GourceLog(private val file: Path) {
    /** One log per file, so one lock keeps concurrent completions from interleaving. */
    private val lock = Mutex()

    /**
     * The timestamp is read once, under the lock: a turn's lines share one second, and the file
     * stays in timestamp order, which is the order Gource plays it back in. A turn that touched no
     * file leaves the log untouched.
     */
    suspend fun append(session: String?, tools: List<ToolCall>) {
        val user = session?.substringAfterLast(':')?.sanitisedUser() ?: "unknown"
        val fields = tools.mapNotNull { it.gourceFields() }
        if (fields.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                lock.withLock {
                    val at = Instant.now().epochSecond
                    Files.writeString(
                        file,
                        fields.joinToString("\n", postfix = "\n") { "$at|$user|$it" },
                        CREATE,
                        APPEND,
                    )
                }
            } catch (e: IOException) {
                log.warn("gource lines for session {} not written: {}", user, e.toString())
            }
        }
    }
}

/**
 * The session comes straight off a client header, so whoever sends the request chooses it: a
 * breaker sanitised before the truncation still yields one well-formed line, attributed to a
 * harmless name, rather than a forged field or a forged line.
 */
private fun String.sanitisedUser(): String = FIELD_BREAKERS.replace(this, "_").take(USER_LENGTH)

/**
 * The type, file, and colour fields of a line, or null for a tool that named no file the line can
 * carry. Gource splits the path on `/` to build its tree, so a Windows path written as it came
 * renders as one flat leaf.
 */
private fun ToolCall.gourceFields(): String? =
    path?.takeUnless(FIELD_BREAKERS::containsMatchIn)?.let { file ->
        val type = if (name in EDIT_TOOLS) MODIFIED else ADDED
        val colour = TOOL_COLOURS[name]?.let { "|$it" }.orEmpty()
        "$type|${file.replace('\\', '/')}$colour"
    }
