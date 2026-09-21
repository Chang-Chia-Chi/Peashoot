package dev.peashoot.proxy

import dev.peashoot.core.FrameParser
import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import dev.peashoot.core.text
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The `x-peashoot-session` header at the proxy's HTTP boundary (#27, criterion 3). Design section 9
 * says it "wins outright and is honored for any client", which is what makes the OpenCode plugin a
 * plugin and not a patch: OpenCode sends no session header of its own, so its three-line hook sends
 * this one and its turns group like anybody else's.
 *
 * Four things have to be true for that to be worth documenting, and only the first is about
 * grouping. The header must reach no provider, because it is ours and naming a user's turns to a
 * third party is not the proxy's to do. It must be no part of the fingerprint, or every session
 * would record and replay separately. And its value is a string a client chose, so it must be
 * bounded before anything writes it — `Client.detect` beating Claude Code's and Codex's own headers
 * is `ClientTest`'s, since that needs no server to prove.
 */
class SessionHeaderSeamTest {
    private fun home(): Path = Files.createTempDirectory("peashoot-home")

    private fun events(home: Path): List<JsonObject> =
        home.resolve(EVENTS_FILE).readLines().map { Json.parseToJsonElement(it).jsonObject }

    /** The sessions the completed lines named, in order. */
    private fun sessions(home: Path): List<String?> =
        events(home)
            .filter { it["event"].text() == "exchange.completed" }
            .map { it["session"].text() }

    private suspend fun post(url: String, path: String, body: String, session: String?): String =
        HttpClient(CIO).use { client ->
            client
                .post("$url$path") {
                    session?.let { header("x-peashoot-session", it) }
                    setBody(body)
                }
                .bodyAsText()
        }

    private fun config(upstream: FakeUpstream, mode: Mode = Mode.RECORD) =
        ProxyConfig(
            port = 0,
            anthropicUpstream = upstream.url,
            openaiUpstream = upstream.url,
            routes = mapOf(DEFAULT_ROUTE to Route(mode)),
        )

    /**
     * Criterion 3. Two turns with nothing in common — different surfaces, different models,
     * different prompts, neither carrying a session header of its own — group under the one session
     * the header injected, and the farm has no other way of knowing they belong together.
     */
    @Test
    fun `two requests sharing only the injected header share a session`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { FakeUpstream.Reply(body = "{}") }
                val chain = listOf(Recorder(store), Deriver(store, home.resolve(EVENTS_FILE)))
                ProxyServer(config(upstream), chain).use { proxy ->
                    post(proxy.url, MESSAGES_PATH, ANTHROPIC_BODY, OPENCODE_SESSION)
                    post(proxy.url, CHAT_PATH, OPENAI_BODY, OPENCODE_SESSION)
                }
            }
            assertEquals(listOf(OPENCODE_SESSION, OPENCODE_SESSION), sessions(home))
            assertEquals(
                listOf("anthropic-messages", "openai-chat"),
                events(home)
                    .filter { it["event"].text() == "exchange.completed" }
                    .map { it["surface"].text() },
                "two surfaces, one session: the header is honoured for any client",
            )
            assertEquals(1, store.sessions().size, "and the store's own view agrees")
        }
    }

    /**
     * The header is the proxy's own and means nothing to a provider, so it is stripped on the way
     * out. Forwarding it would hand a third party a stable identifier tying a user's turns together
     * that they would not otherwise have, which is a thing a proxy in the path must not do by
     * accident.
     */
    @Test
    fun `the injected header never reaches the provider`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { FakeUpstream.Reply(body = "{}") }
                ProxyServer(config(upstream), listOf(Recorder(store))).use { proxy ->
                    post(proxy.url, MESSAGES_PATH, ANTHROPIC_BODY, OPENCODE_SESSION)
                }
                val forwarded = upstream.received.single().headers.mapKeys { it.key.lowercase() }
                assertFalse(
                    "x-peashoot-session" in forwarded,
                    "the header is ours; the provider is told nothing by it: $forwarded",
                )
            }
        }
    }

    /**
     * It is no part of the fingerprint either — it is absent from `Rules.DEFAULT.keepHeaders` — so
     * one recording answers the same prompt whichever session asked it. Were it kept, a cassette
     * recorded on Monday would miss on Tuesday for no reason a user could see.
     */
    @Test
    fun `a recording made in one session replays in another`() = runBlocking {
        val home = home()
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = { FakeUpstream.Reply(body = RECORDED_BODY) }
                val record = config(upstream)
                val live =
                    ProxyServer(record, listOf(Recorder(store))).use {
                        post(it.url, MESSAGES_PATH, ANTHROPIC_BODY, OPENCODE_SESSION)
                    }
                upstream.received.clear()

                val replay = config(upstream, Mode.REPLAY)
                val served =
                    ProxyServer(replay, listOf(Replay(store, replay), Recorder(store))).use {
                        post(it.url, MESSAGES_PATH, ANTHROPIC_BODY, "a-quite-different-session")
                    }

                assertEquals(RECORDED_BODY, live)
                assertEquals(live, served, "the other session's turn replayed from the recording")
                assertTrue(upstream.received.isEmpty(), "with no call of its own")
            }
        }
    }

    /**
     * The value is whatever the client put in the header, so it is bounded where it enters rather
     * than at each of the places that later write it. What can be asserted *here* is the length cap
     * and the field separator, because HTTP itself refuses the rest: a bare line break in a header
     * value is rejected by the client library and by the engine's own decoder before any code of
     * ours sees it. Control characters are `ClientTest`'s, which needs no socket — and they are not
     * theoretical there, because `Client.detect` also runs on the headers of an exchange rebuilt
     * from the store, and a cassette is a file that may have been hand-edited or imported.
     */
    @Test
    fun `a hostile session value is bounded before anything writes it`() = runBlocking {
        val home = home()
        val gource = home.resolve(GOURCE_FILE)
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = FrameParser.parse(fileToolsFixture()).map { it.raw },
                    )
                }
                val deriver = Deriver(store, home.resolve(EVENTS_FILE), gource = GourceLog(gource))
                ProxyServer(config(upstream), listOf(Recorder(store), deriver)).use { proxy ->
                    post(proxy.url, MESSAGES_PATH, ANTHROPIC_BODY, HOSTILE_SESSION)
                }
            }
        }

        val session = checkNotNull(sessions(home).single())
        assertTrue(session.length <= BOUND, "bounded to $BOUND, not ${session.length}")
        assertTrue(session.startsWith("opencode"), "and it is still recognisably theirs: $session")
        // The forged line and the forged fields in the header are in neither log: every Gource
        // line is one turn's own, and the count is the fixture's file tools and no more.
        val lines = gource.readLines()
        assertTrue(lines.isNotEmpty(), "the turn did touch files")
        assertTrue(
            lines.none { it.contains("/etc/passwd") },
            "nothing the header said became a line of its own: $lines",
        )
        assertEquals(
            lines.size,
            lines.count { it.split('|').size in FIELDS_PER_LINE },
            "every line has its own fields and no forged ones: $lines",
        )
    }

    private fun fileToolsFixture(): ByteArray =
        checkNotNull(
                javaClass.getResourceAsStream("/anthropic-messages/stream-with-file-tools.sse")
            )
            .readBytes()

    private companion object {
        /** What the documented OpenCode plugin injects: its own session id, under our name. */
        const val OPENCODE_SESSION = "ses_8Fq2xKp1"

        /**
         * A value trying to forge Gource fields of its own and then to run past any cap. Every
         * character in it is legal in an HTTP header value, which is exactly the point: this is
         * what a client can actually send, where a line break is refused by HTTP long before us.
         */
        val HOSTILE_SESSION = "opencode|forged|A|/etc/passwd" + "x".repeat(BOUND * 2)

        /** The cap `Client` applies, generous against a UUID's 36 characters. */
        const val BOUND = 128

        /** A Gource line is `ts|user|type|path`, with an optional colour field after it. */
        val FIELDS_PER_LINE = 4..5

        const val ANTHROPIC_BODY =
            """{"model":"claude-sonnet-5","max_tokens":64,""" +
                """"messages":[{"role":"user","content":"count to three"}]}"""

        const val OPENAI_BODY =
            """{"model":"gpt-4o-mini","messages":[{"role":"user","content":"something else"}]}"""

        const val RECORDED_BODY = """{"id":"msg_1","type":"message","role":"assistant"}"""
    }
}
