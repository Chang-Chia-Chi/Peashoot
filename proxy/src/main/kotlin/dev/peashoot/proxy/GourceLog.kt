package dev.peashoot.proxy

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

/** Gource's own default, for a file tool the palette below does not name. */
private const val DEFAULT_COLOUR = "FFFFFF"

/**
 * A UUID's first eight hex characters are its conventional short form, and fit on a Gource node.
 */
private const val USER_LENGTH = 8

/** The tools that change a file. Everything else that names one only looked at it. */
private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

/** Far enough apart to read in motion: green looks, teal searches, orange edits, red creates. */
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
 * The Gource custom-format log, so a repository can be watched as its agents move through it: one
 * line per file tool of a completed turn, the session as the committer. Never throws: a line that
 * cannot be written is one WARN, never a failed request.
 */
class GourceLog(private val file: Path) {
    /** One log per file, so one lock keeps concurrent completions from interleaving. */
    private val lock = Mutex()

    /**
     * The timestamp is read inside the lock, so a turn's lines share one second and the file stays
     * in the order Gource plays it back. A turn that touched no file leaves the log untouched.
     */
    suspend fun append(session: String?, tools: List<ToolCall>) {
        val user = session?.substringAfterLast(':')?.take(USER_LENGTH) ?: "unknown"
        val entries = tools.mapNotNull { it.entry(user) }
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                lock.withLock {
                    val at = Instant.now().epochSecond
                    Files.writeString(
                        file,
                        entries.joinToString("") { "$at|$it\n" },
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
 * Everything of a line but its timestamp, or null for a tool that named no file. Gource splits the
 * path on `/` to build its tree, so a Windows path written as it came renders as one flat leaf.
 */
private fun ToolCall.entry(user: String): String? = path?.let { file ->
    val type = if (name in EDIT_TOOLS) "M" else "A"
    "$user|$type|${file.replace('\\', '/')}|${TOOL_COLOURS[name] ?: DEFAULT_COLOUR}"
}
