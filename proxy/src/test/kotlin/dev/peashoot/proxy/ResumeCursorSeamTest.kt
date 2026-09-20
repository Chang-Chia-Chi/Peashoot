package dev.peashoot.proxy

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Responses resume cursor served from the proxy's own buffer (#27, criterion 1): `GET
 * /v1/responses/{id}?stream=true&starting_after=N` answered with the events of that response whose
 * `sequence_number` is greater than N, from the answer the proxy went on reading after its client
 * left, with no upstream call.
 *
 * What is served is the tail of the original stream and nothing else, so every assertion here
 * compares bytes against the fixture's own frames. The misses — an unknown id, a background
 * response, a bad cursor, an expired window — are [ResumeCursorMissTest]'s, which keeps both
 * classes clear of detekt's `LargeClass` ceiling.
 */
class ResumeCursorSeamTest {
    private val events = fixtureFrames("/openai-responses/stream-with-function-call.sse")

    /**
     * The fixture numbers its events 0 to 11, one per frame, so the frames a cursor at [after]
     * leaves are simply the ones past that index. That correspondence is asserted once, below, so
     * the rest of the class can index by it.
     */
    private fun tailAfter(after: Int): String = events.drop(after + 1).joinToString("")

    private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    /** The client leaves [LEFT_AFTER] events in, with the upstream held at that same frame. */
    private suspend fun ResumeRig.droppedMidStream() {
        streams(events)
        dropMidStream(RESPONSES_REQUEST, RESPONSES_PATH)
    }

    @Test
    fun `the fixture numbers one event per frame, which every cursor here counts by`() {
        events.forEachIndexed { index, frame ->
            assertTrue(
                frame.contains("\"sequence_number\":$index"),
                "frame $index carries sequence_number $index: ${frame.take(80)}",
            )
        }
    }

    /**
     * Criterion 1, with the upstream still streaming: the cursor joins an answer in flight, is
     * handed the frames it has missed so far, and rides the rest live. Byte-equal to the fixture's
     * own tail across the hand-over, which is where a frame would be lost or sent twice.
     */
    @Test
    fun `a cursor cut at N resumes from N+1 while the upstream still streams`() = withResume {
        droppedMidStream()

        val resumed = async { streamedGet(cursorPath("$CUT_AT")) }
        // The cursor's own started line is written after Resume answered it, so by now it has
        // joined an answer whose upstream is still held at the frame the first client left on.
        awaitEvents(ResumeRig.STARTED, 2)
        release.complete(Unit)

        assertEquals(tailAfter(CUT_AT), resumed.await(), "the fixture's tail, across the hand-over")
        assertEquals(1, upstream.received.size, "the cursor never reached the upstream")
    }

    /** The same answer once it has finished: read out of the buffer with nothing left to ride. */
    @Test
    fun `a cursor after the answer completed is served wholly from the buffer`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertEquals(tailAfter(CUT_AT), streamedGet(cursorPath("$CUT_AT")))
        assertEquals(1, upstream.received.size)
    }

    /**
     * No `starting_after` is a cursor at the start: every event the response ever sent, the ones
     * the first client already had included. The API's parameter is what says where to begin, and a
     * client that names none is asking for all of it.
     */
    @Test
    fun `a cursor with no starting_after is served from the first event`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertEquals(events.joinToString(""), streamedGet(cursorPath(after = null)))
        assertEquals(1, upstream.received.size)
    }

    /**
     * A cursor at the last event of a completed response: an empty stream that still ends properly,
     * rather than an error or a hang. The client has everything, and saying so with a clean end is
     * the only honest answer a buffer can give.
     */
    @Test
    fun `a cursor at the last sequence is an empty but properly ended stream`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        val response = get(cursorPath("${events.lastIndex}"))
        assertEquals(200, response.status.value)
        assertEquals(
            ContentType.Text.EventStream.toString(),
            response.headers[HttpHeaders.ContentType],
            "the original's own headers, so an empty tail is still a stream",
        )
        assertEquals("", streamedGet(cursorPath("${events.lastIndex}")), "nothing after the last")
        assertEquals(1, upstream.received.size)
    }

    /**
     * Criterion 1 on the event line: the cursor made no call, so it is billed nothing, and the one
     * call and its cost stay on the line of the exchange whose client left.
     */
    @Test
    fun `a served cursor says resumed at a cost of zero, over one upstream call`() = withResume {
        droppedMidStream()
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)
        streamedGet(cursorPath("$CUT_AT"))

        val completed = awaitEvents(ResumeRig.COMPLETED, 2)
        val original = completed.single { it.flag("clientDisconnected") }
        val cursor = completed.single { it.flag("resumed") }
        assertEquals("0.0", cursor.getValue("costUsd").jsonPrimitive.content, "billed nothing")
        assertEquals("openai-responses", cursor.getValue("surface").jsonPrimitive.content)
        assertTrue(original.getValue("usage") is JsonObject, "the call's own usage is on its line")
        assertEquals(1, upstream.received.size)
        // The cursor is not recorded, for the reason a resumed re-issue is not: its frames are
        // this row's answer handed out again, and a second row under one fingerprint is one an
        // `inOrder` replay would serve twice.
        assertEquals(1, store.list().size, "two exchanges, one call, one row")
    }

    /**
     * A GET reads where a re-issued POST claims. Two cursors on one answer are two clients asking
     * what came after a number, which is a question with one true answer however often it is asked
     * — unlike two equal POSTs, where the second may well be a user wanting a fresh sample.
     */
    @Test
    fun `two cursors on one answer are both served, from the one upstream call`() = withResume {
        droppedMidStream()

        val first = async { streamedGet(cursorPath("$CUT_AT")) }
        val second = async { streamedGet(cursorPath("$LATER_CUT")) }
        awaitEvents(ResumeRig.STARTED, 3)
        release.complete(Unit)

        assertEquals(tailAfter(CUT_AT), first.await())
        assertEquals(tailAfter(LATER_CUT), second.await())
        assertEquals(1, upstream.received.size, "three exchanges, one call")
        assertEquals(2, awaitEvents(ResumeRig.COMPLETED, 3).count { it.flag("resumed") })
    }

    /** The upstream cuts its stream [CUT_AFTER] frames in, once the first client has left. */
    private fun ResumeRig.cutsAfterTheDrop() {
        upstream.reply = {
            FakeUpstream.Reply(
                contentType = ContentType.Text.EventStream,
                frames = events,
                beforeFrame = { index -> if (index == LEFT_AFTER) release.await() },
                cutAfterFrames = CUT_AFTER,
            )
        }
    }

    /**
     * A truncated original is served where a re-issued POST would be refused it, and the difference
     * is what the two clients asked for. A POST asks for an answer, so handing it a broken one as
     * though it were whole costs it the retry it made (#26). A cursor asks for the events after a
     * number of one named response: the honest answer is the ones that exist, ending where the
     * original ended, with no terminal event and nothing pretending otherwise. The alternative —
     * missing and going upstream — is worse, because for a response that was never created in
     * background mode there is nothing upstream to resume from at all.
     */
    @Test
    fun `a cursor on an answer whose upstream failed is served the tail that exists`() =
        withResume {
            cutsAfterTheDrop()
            dropMidStream(RESPONSES_REQUEST, RESPONSES_PATH)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)

            val served = streamedGet(cursorPath("$CUT_AT"))
            assertEquals(
                events.subList(CUT_AT + 1, CUT_AFTER).joinToString(""),
                served,
                "cut where the original was cut, not padded out to a completion",
            )
            assertTrue(
                served.isNotEmpty() && !served.contains("response.completed"),
                "and it says so by having no terminal event",
            )
            assertEquals(1, upstream.received.size)
        }

    private companion object {
        /**
         * Where a cursor asks from: before the drop, so frames on both sides of it are filtered.
         */
        const val CUT_AT = 3

        /** A second cursor, past the drop, so the two readers ask for different tails. */
        const val LATER_CUT = 7

        /** Where the upstream's stream breaks: after the drop at [LEFT_AFTER], before the end. */
        const val CUT_AFTER = 9
    }
}
