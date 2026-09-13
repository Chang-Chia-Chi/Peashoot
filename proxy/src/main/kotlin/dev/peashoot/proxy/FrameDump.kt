package dev.peashoot.proxy

import java.io.ByteArrayOutputStream
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

/**
 * Collects one upstream response and appends it, whole, under a header line when it completes.
 * Responses stream concurrently, so writing chunk by chunk would interleave them.
 */
class FrameDump(
    private val file: Path,
    method: String,
    uri: String,
    status: Int,
) {
    private val header = "--- $method $uri $status ${Instant.now()} ---"
    private val bytes = ByteArrayOutputStream()

    fun append(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) = bytes.write(buffer, offset, length)

    /** Appends the response; a dump the client abandoned mid-stream is still written. */
    suspend fun close() =
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                Files.newOutputStream(file, CREATE, APPEND).use { out ->
                    out.write(header.toByteArray())
                    out.write('\n'.code)
                    bytes.writeTo(out)
                    out.write('\n'.code)
                }
            }
        }

    companion object {
        // ponytail: one lock for every dump: one dump path per process; re-key by path if that
        // ever changes.
        private val lock = Mutex()
    }
}
