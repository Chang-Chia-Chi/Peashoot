package dev.peashoot.proxy

import dev.peashoot.core.Mode
import dev.peashoot.core.Route
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * When the Responses cursor is *not* served from the proxy's own buffer (#27): the response is
 * unknown here, it was created in background mode, the window has passed, its client never left,
 * the request is a cancel rather than a cursor, or the cursor names no sequence number at all.
 * Every one of these but the last is an honest miss that reaches the provider exactly as #25
 * relayed it, and a test that a request goes upstream is only worth anything if the upstream
 * answers something the buffer never could — so the fake one answers a cursor with [FROM_UPSTREAM],
 * which is in no fixture.
 *
 * Criterion 2 lives here: background responses pass through unchanged. So does the check that
 * Resume does not shadow Replay, which the chain order makes worth asserting — Resume is first.
 */
class ResumeCursorMissTest {
    private val events = fixtureFrames("/openai-responses/stream-with-function-call.sse")

    /**
     * What only the provider can have said: a sequence number past the end of every fixture, so a
     * body carrying it cannot have come out of any buffer of ours.
     */
    private val fromUpstream =
        "event: response.completed\n" +
            """data: {"type":"response.completed","sequence_number":$FROM_UPSTREAM,""" +
            """"response":{"id":"$RESPONSES_ID","object":"response","status":"completed"}}""" +
            "\n\n"

    private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    /**
     * The upstream streams the fixture to a create, holding at [LEFT_AFTER], and answers any cursor
     * with [fromUpstream]. Branching on the method is what lets one fake upstream stand for both.
     */
    private fun ResumeRig.answersCursorsItself() {
        upstream.reply = { received ->
            if (received.method == "GET") {
                FakeUpstream.Reply(
                    contentType = ContentType.Text.EventStream,
                    frames = listOf(fromUpstream),
                )
            } else {
                FakeUpstream.Reply(
                    contentType = ContentType.Text.EventStream,
                    frames = events,
                    beforeFrame = { index -> if (index == LEFT_AFTER) release.await() },
                )
            }
        }
    }

    /** A create that streams, whose client leaves [LEFT_AFTER] events in and is heard to go. */
    private suspend fun ResumeRig.droppedMidStream(body: String = RESPONSES_REQUEST) {
        answersCursorsItself()
        dropMidStream(body, RESPONSES_PATH)
    }

    /** The provider answered this cursor, not us, and no line claims otherwise. */
    private suspend fun ResumeRig.assertRelayed(path: String, why: String) {
        assertEquals(fromUpstream, streamedGet(path), why)
        assertEquals(2, upstream.received.size, why)
        assertFalse(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") }, why)
    }

    /**
     * Criterion 2. A background response is the provider's own to resume: it keeps the events for
     * about ten minutes and answers `starting_after` itself, so the create, the cursor and a cancel
     * all pass through and are recorded and replayed as #25 built them. Nothing about this answer
     * is served from our buffer even though the drop put it there, which is the only thing that
     * could go wrong.
     */
    @Test
    fun `a cursor for a response created in background mode goes upstream`() = withResume {
        droppedMidStream(BACKGROUND_REQUEST)
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertRelayed(cursorPath("$CUT_AT"), "background mode passes through")
    }

    /** The same drop without `background`, so the only difference is the field under test. */
    @Test
    fun `the same drop without background mode is served from the buffer`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertEquals(events.drop(CUT_AT + 1).joinToString(""), streamedGet(cursorPath("$CUT_AT")))
        assertEquals(1, upstream.received.size, "no second call, where background made one")
    }

    @Test
    fun `a cursor naming a response the proxy never buffered goes upstream`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertRelayed(cursorPath("$CUT_AT", id = "resp_SOMEONE_ELSE"), "an id we never saw")
    }

    /**
     * The window bounds a cursor exactly as it bounds a re-issue: past it the answer is no longer
     * offered to anyone, and the provider is asked. A few hundred milliseconds against the default
     * five minutes, and only the negative is asserted, so a slow box makes this slower and not
     * flaky — the same bargain `ResumeBoundsTest` strikes.
     */
    @Test
    fun `a cursor past the resume window goes upstream`() =
        withResume({ it.copy(resumeWindow = WINDOW) }) {
            droppedMidStream()
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)
            delay(WINDOW * EXPIRY_MARGIN)

            assertRelayed(cursorPath("$CUT_AT"), "the window had passed")
        }

    /**
     * An answer whose client stayed to the end is dropped from the buffer when it completes (#26),
     * so a cursor for it is an honest miss. That is the one eligibility rule a cursor keeps from
     * the re-issue path, and it keeps it by inheritance rather than by choice: nothing is holding
     * the answer any more, so there is nothing to serve.
     */
    @Test
    fun `a cursor for an answer whose client never left goes upstream`() = withResume {
        answersCursorsItself()
        release.complete(Unit)
        assertEquals(events.joinToString(""), streamed(RESPONSES_REQUEST, RESPONSES_PATH))
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertRelayed(cursorPath("$CUT_AT"), "nothing was buffered for it")
    }

    /**
     * A cancel is an instruction to the provider and can never be answered from a buffer, whatever
     * is in one. Two guards keep it out — it is a `POST`, and it has a path segment after the id —
     * and this asserts the outcome rather than either of them.
     */
    @Test
    fun `a cancel under a buffered response id still reaches the provider`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        post("{}", "$RESPONSES_PATH/$RESPONSES_ID/cancel")
        assertEquals(2, upstream.received.size, "a cancel is relayed, never served")
        assertEquals("POST", upstream.received.last().method)
    }

    /**
     * Without `stream=true` the API answers one response object, and our buffer holds SSE frames,
     * so the request is not a cursor at all and is relayed like any other get-by-id.
     */
    @Test
    fun `a get-by-id that asked for no stream goes upstream`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertRelayed(cursorPath(after = null, stream = false), "no stream asked for, none served")
    }

    /**
     * A `starting_after` that is no sequence number can never name a place in a stream, whoever
     * answers it, so it is refused here rather than relayed — in the proxy's own error shape, which
     * a client's retry logic cannot mistake for a provider's. OpenAI declares no 400 for this
     * endpoint and documents no validation of the parameter, so there is nothing to imitate either.
     */
    @Test
    fun `a starting_after that is no sequence number is refused, and never relayed`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        listOf("abc", "-1", "3.5", "").forEach { bad ->
            val response = get(cursorPath(bad))
            assertEquals(400, response.status.value, "starting_after=$bad")
            val body = Json.parseToJsonElement(response.bodyAsText()) as JsonObject
            assertEquals("peashoot_error", body.getValue("type").jsonPrimitive.content)
            assertEquals("bad_starting_after", body.getValue("error").jsonPrimitive.content)
            assertEquals(bad, body.getValue("detail").jsonPrimitive.content, "it says which value")
        }
        assertEquals(1, upstream.received.size, "not one of them reached the provider")
    }

    /**
     * Resume is first in the chain, so a recorded cursor could be shadowed by it before Replay ever
     * sees the request. It is not: Resume stands aside on a replay route entirely, because a hit
     * there is free already and a cassette must answer the same way on every run. This is #25's
     * `ResponsesCursorSeamTest` with the interceptor that did not exist then put in front.
     */
    @Test
    fun `a recorded cursor replays from the store, with Resume first in the chain`() = runBlocking {
        val home = Files.createTempDirectory("peashoot-home")
        val cursor = cursorPath("$CUT_AT")
        Store(home).use { store ->
            FakeUpstream().use { upstream ->
                upstream.reply = {
                    FakeUpstream.Reply(
                        contentType = ContentType.Text.EventStream,
                        frames = events.drop(CUT_AT + 1),
                    )
                }
                fun config(mode: Mode) =
                    ProxyConfig(
                        port = 0,
                        anthropicUpstream = upstream.url,
                        openaiUpstream = upstream.url,
                        routes = mapOf(DEFAULT_ROUTE to Route(mode)),
                    )

                val record = config(Mode.RECORD)
                val live =
                    ProxyServer(record, listOf(Resume(record), Recorder(store))).use {
                        it.fetch(cursor)
                    }
                withTimeout(ResumeRig.TIMEOUT_MS) {
                    while (store.list().isEmpty()) delay(ResumeRig.POLL_MS)
                }
                upstream.received.clear()

                val replay = config(Mode.REPLAY)
                val chain = listOf(Resume(replay), Replay(store, replay), Recorder(store))
                val served = ProxyServer(replay, chain).use { it.fetch(cursor) }

                assertEquals(
                    events.drop(CUT_AT + 1).joinToString(""),
                    live,
                    "recorded from upstream",
                )
                assertEquals(live, served, "and replayed byte for byte")
                assertTrue(upstream.received.isEmpty(), "Replay answered it, not the upstream")
                assertEquals(1, store.list().size, "a replay hit is never re-recorded")
            }
        }
    }

    private suspend fun ProxyServer.fetch(path: String): String =
        HttpClient(CIO).use { client -> client.get("$url$path").bodyAsText() }

    private companion object {
        /** Where a cursor asks from, matching [ResumeCursorSeamTest]'s. */
        const val CUT_AT = 3

        /** Past the last sequence of every fixture, so only the provider can have sent it. */
        const val FROM_UPSTREAM = 99

        val WINDOW = 400.milliseconds

        /** Three windows past the drop: long enough that a loaded box still sees it expire. */
        const val EXPIRY_MARGIN = 3
    }
}
