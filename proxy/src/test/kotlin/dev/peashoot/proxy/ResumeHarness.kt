package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val MESSAGES_PATH = "/v1/messages"
internal const val CHAT_PATH = "/v1/chat/completions"
internal const val RESPONSES_PATH = "/v1/responses"

/** A Responses turn. Its own cursor is #27's; an equal re-issue resumes here like any other. */
internal const val RESPONSES_REQUEST =
    """{"model":"gpt-5","stream":true,"input":[{"type":"message","role":"user",""" +
        """"content":[{"type":"input_text","text":"count to three"}]}]}"""

/** How many frames a leaving client reads first: mid-stream, with frames on both sides of it. */
internal const val LEFT_AFTER = 6

/**
 * A Messages request shaped as the spike recorded Claude Code's
 * (`docs/research/claude-code-stream-drop-retry.md`): `system` blocks, a tool list, a last `user`
 * message whose content is an array of text blocks, and a `system`-role message after it, so the
 * last user message is not the last message. [blocks] is that user message's content.
 */
internal fun messagesRequest(
    blocks: String = ORIGINAL_BLOCKS,
    model: String = "claude-sonnet-5",
    system: String = "You count.",
    tools: String = """[{"name":"Read","input_schema":{"type":"object"}}]""",
    messagesAfter: String = "",
): String =
    """{"model":"$model","max_tokens":64000,"stream":true,""" +
        """"system":[{"type":"text","text":"$system"}],"tools":$tools,""" +
        """"messages":[{"role":"user","content":[$blocks]},""" +
        """{"role":"system","content":"session-start reminder"}$messagesAfter]}"""

/** A Chat Completions turn: the surface that gets the exact match and nothing else. */
internal const val CHAT_REQUEST =
    """{"model":"gpt-4o-mini","stream":true,"stream_options":{"include_usage":true},""" +
        """"messages":[{"role":"user","content":"count to three"}]}"""

/** `stream: false`, so the whole answer is one frame and takes the same path. */
internal const val NON_STREAMING_REQUEST =
    """{"model":"claude-sonnet-5","max_tokens":64,"stream":false,""" +
        """"messages":[{"role":"user","content":[{"type":"text","text":"one line, please."}]}]}"""

/** What the upstream answers such a request with: one frame, byte for byte. */
internal const val WHOLE_BODY =
    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",""" +
        """"content":[{"type":"text","text":"one line"}],"stop_reason":"end_turn",""" +
        """"usage":{"input_tokens":9,"output_tokens":3}}"""

internal const val FIRST_BLOCK = """{"type":"text","text":"context the client prepends"}"""
internal const val ORIGINAL_BLOCKS =
    """$FIRST_BLOCK,{"type":"text","text":"Count from 1 to 300, one number per line."}"""

/**
 * The re-issue after a mid-stream cut, exactly as the spike measured its shape: a `\n` on the end
 * of the last block, and one text block after it. The wording is this test's own and nothing like
 * the client's, because the match must not depend on a client constant with a shelf life.
 */
internal const val CONTINUED_BLOCKS =
    """$FIRST_BLOCK,{"type":"text","text":"Count from 1 to 300, one number per line.\n"},""" +
        """{"type":"text","text":"The line dropped. Carry on from wherever that leaves you."}"""

/**
 * A proxy with the whole v1 chain, Resume first, in front of one fake upstream for every surface.
 * It is the test's own scope too, so a test can `async` a re-issue and go on to release the
 * upstream under it.
 */
internal class ResumeRig(
    val store: Store,
    val upstream: FakeUpstream,
    val proxy: ProxyServer,
    scope: CoroutineScope,
) : CoroutineScope by scope {
    /** Completed by a test to let a held upstream go on. */
    val release = CompletableDeferred<Unit>()

    /**
     * The upstream streams [frames], stopping before frame [holdAt] until [release]: the stream is
     * still in flight for as long as the test needs it to be.
     */
    fun streams(frames: List<String>, holdAt: Int = LEFT_AFTER) {
        upstream.reply = {
            FakeUpstream.Reply(
                contentType = ContentType.Text.EventStream,
                frames = frames,
                beforeFrame = { index -> if (index == holdAt) release.await() },
            )
        }
    }

    /** Completed when the upstream has a request in hand, by [answersOnRelease]. */
    private val received = CompletableDeferred<Unit>()

    /**
     * The upstream takes each request and says nothing, not even its headers, until [release]: the
     * relay is parked in the connect, which is where a client that leaves before the first byte
     * leaves it.
     */
    fun answersOnRelease(reply: FakeUpstream.Reply) {
        upstream.reply = {
            received.complete(Unit)
            release.await()
            reply
        }
    }

    /**
     * A raw socket with [body] posted to [path]: no client library leaves a response on demand.
     * Inline, so [then] may suspend in the caller's coroutine while the socket stays open.
     */
    private inline fun <T> posted(body: String, path: String, then: (Socket) -> T): T {
        val (host, port) = proxy.url.removePrefix("http://").split(":")
        return Socket(host, port.toInt()).use { socket ->
            // A regression that never answers fails in five seconds, not never.
            socket.soTimeout = TIMEOUT_MS.toInt()
            val bytes = body.toByteArray()
            socket.getOutputStream().apply {
                write(
                    ("POST $path HTTP/1.1\r\nHost: $host:$port\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n\r\n")
                        .toByteArray() + bytes
                )
                flush()
            }
            then(socket)
        }
    }

    /**
     * Posts [body] and leaves as soon as the upstream has it, before any byte of an answer exists,
     * then gives the close [SETTLE_MS] to reach the engine: nothing the proxy writes says when it
     * has, because nobody looks at the channel until the upstream answers.
     */
    suspend fun leaveBeforeAnswer(body: String, path: String = MESSAGES_PATH) {
        posted(body, path) { withTimeout(TIMEOUT_MS) { received.await() } }
        delay(SETTLE_MS)
    }

    /** Posts [body], reads until [frames] frames are out, then closes. */
    fun leaveAfter(frames: Int, body: String, path: String = MESSAGES_PATH) =
        posted(body, path) { socket ->
            val input = socket.getInputStream()
            val buffer = ByteArray(READ_BUFFER)
            var seen = 0
            var previous = 0
            while (seen < frames) {
                val read = input.read(buffer)
                check(read != -1) { "the response ended after $seen frames" }
                for (i in 0 until read) {
                    // A frame ends in a blank line; chunked framing never puts two together.
                    if (buffer[i].toInt() == '\n'.code && previous == '\n'.code) seen++
                    previous = buffer[i].toInt()
                }
            }
            // Bytes left unread would turn the close into a reset.
            while (input.available() > 0) input.read(buffer)
        }

    /**
     * The response body with the proxy's own keep-alive comments taken out.
     *
     * This is still the criterion's "byte-equal to the fixture". A keep-alive is the proxy's line
     * and not the provider's — #11 writes it into any stream that has been silent for the interval,
     * SSE clients ignore comment lines by specification, and `clientBytes` already excludes them
     * from what the client is counted as having taken. Whether one falls between two frames is
     * timing, so leaving them in would make a held upstream and a loaded box able to fail an
     * assertion about frames. That pings do reach a resumed stream is asserted on its own, once.
     */
    fun String.withoutPings(): String = replace(KEEP_ALIVE, "")

    /** Posts [body] and reads the whole answer back, as the provider's own frames alone. */
    suspend fun streamed(body: String, path: String = MESSAGES_PATH): String =
        post(body, path).bodyAsText().withoutPings()

    suspend fun post(body: String, path: String = MESSAGES_PATH): HttpResponse =
        HttpClient(CIO).use { client ->
            client.post("${proxy.url}$path") {
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }

    /** The event lines of one kind, in the order the deriver stored them. */
    suspend fun events(kind: String): List<JsonObject> =
        store.events().values.filter { it.getValue("event").jsonPrimitive.content == kind }

    /** Waits for the [count]th line of [kind]: the chain runs off the test's thread. */
    suspend fun awaitEvents(kind: String, count: Int): List<JsonObject> =
        withTimeout(TIMEOUT_MS) {
            while (events(kind).size < count) delay(POLL_MS)
            events(kind)
        }

    /**
     * The client reads [after] frames of the answer to [body] and leaves, and the proxy has heard
     * it go: the ping interval is short, so the writer looks at the channel while the upstream is
     * held.
     */
    suspend fun dropMidStream(
        body: String,
        path: String = MESSAGES_PATH,
        after: Int = LEFT_AFTER,
        drops: Int = 1,
    ) {
        leaveAfter(after, body, path)
        awaitEvents(CLIENT_GONE, drops)
    }

    companion object {
        const val STARTED = "exchange.started"
        const val COMPLETED = "exchange.completed"
        const val CLIENT_GONE = "exchange.client_gone"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
        const val READ_BUFFER = 4096
        /**
         * Short enough that the client writer looks at its channel, and so hears its client leave,
         * while the upstream is held and no frame is coming to wake it — that look is the whole of
         * how a mid-stream drop is heard here. Not shorter: a keep-alive comment goes into the body
         * the tests compare byte for byte, so every interval that passes between a joiner draining
         * the buffer and the upstream being released is a chance to fail a comparison that has
         * nothing to do with pings.
         */
        const val PING_MS = 250L

        /**
         * How long a close is given to reach the engine where nothing observable says it has.
         * ClientGoneTest allows 100 ms for the same FIN on the same loopback; three times that
         * leaves room for a loaded CI box without making the suite slow.
         */
        const val SETTLE_MS = 300L
    }
}

internal fun fixtureFrames(resource: String): List<String> =
    FrameParser.parse(
            checkNotNull(ResumeRig::class.java.getResourceAsStream(resource)) { resource }
                .readBytes()
        )
        .map { it.raw }

/**
 * Runs [block] against a proxy whose chain is the one `serve` builds, Resume first, with a ping
 * interval short enough that a held upstream still lets the writer hear its client leave.
 */
internal fun withResume(
    configure: (ProxyConfig) -> ProxyConfig = { it },
    grace: Duration = CONTINUATION_GRACE,
    block: suspend ResumeRig.() -> Unit,
) = runBlocking {
    val home: Path = Files.createTempDirectory("peashoot-home")
    Store(home).use { store ->
        FakeUpstream().use { upstream ->
            val config =
                configure(
                    ProxyConfig(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        openaiUpstream = upstream.url,
                        pingInterval = ResumeRig.PING_MS.milliseconds,
                    )
                )
            val chain =
                listOf(
                    Resume(config, grace),
                    Replay(store, config),
                    Recorder(store),
                    Deriver(store, home.resolve(EVENTS_FILE)),
                )
            ProxyServer(config, chain).use { proxy ->
                ResumeRig(store, upstream, proxy, this).block()
            }
        }
    }
}
