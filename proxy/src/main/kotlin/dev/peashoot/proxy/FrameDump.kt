package dev.peashoot.proxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/**
 * Collects one upstream response and appends it, whole, under a header line when it completes.
 * Responses stream concurrently, so writing chunk by chunk would interleave them.
 */
class FrameDump(private val file: Path, method: String, uri: String, status: Int) : Closeable {
    private val header = "--- $method $uri $status ${Instant.now()} ---"
    private val bytes = ByteArrayOutputStream()

    fun append(buffer: ByteArray, offset: Int, length: Int) = bytes.write(buffer, offset, length)

    override fun close() {
        synchronized(FrameDump) {
            Files.newOutputStream(file, CREATE, APPEND).use { out ->
                out.write(header.toByteArray())
                out.write(NEWLINE)
                bytes.writeTo(out)
                out.write(NEWLINE)
            }
        }
    }

    private companion object {
        const val NEWLINE = 10
    }
}
