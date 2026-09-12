package dev.peashoot.proxy

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/** Appends one upstream response, raw, under a header line. A debug aid for capturing fixtures. */
object FrameDump {
    fun open(file: Path, method: String, uri: String, status: Int): OutputStream {
        val out = Files.newOutputStream(file, CREATE, APPEND)
        out.write("--- $method $uri $status ${Instant.now()} ---".toByteArray())
        out.write(10)
        return out
    }
}
