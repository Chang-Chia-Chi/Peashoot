package dev.peashoot.proxy

import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stream-resume at the proxy's HTTP boundary (#26): a client leaves mid-answer, re-issues the same
 * request, and is served the answer the proxy went on reading, with one upstream call between the
 * two of them.
 */
class ResumeSeamTest {
    private val frames = fixtureFrames("/anthropic-messages/stream-with-tool-use.sse")
    private val whole = frames.joinToString("")

    /** Both rows are in the store once both completions ran; the recorder writes off-thread. */
    private suspend fun ResumeRig.recordings(count: Int): List<Recorded> =
        withTimeout(ResumeRig.TIMEOUT_MS) {
            while (store.list().size < count) delay(ResumeRig.POLL_MS)
            store.list().asReversed()
        }

    private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    @Test
    fun `a re-issue while the upstream still streams gets the whole answer from one call`() =
        withResume {
            streams(frames)
            val request = messagesRequest()
            dropMidStream(request)

            val reissue = async { post(request).bodyAsText() }
            // The second started line is written after Resume answered, so by now the re-issue has
            // joined an answer whose upstream is still held at the frame the first client left on.
            awaitEvents(ResumeRig.STARTED, 2)
            release.complete(Unit)

            assertEquals(whole, reissue.await(), "byte-equal to the fixture, across the hand-over")
            assertEquals(1, upstream.received.size, "the re-issue never reached the upstream")
            val (original, resumed) = recordings(2)
            assertTrue(original.exchange.clientDisconnected, "the original's client left")
            assertEquals(frames, original.frames.map { it.raw }, "and it was still read to its end")
            assertFalse(resumed.exchange.clientDisconnected)
            assertEquals(
                frames,
                resumed.frames.map { it.raw },
                "the resumed exchange is its own row",
            )
            assertResumedLines(awaitEvents(ResumeRig.COMPLETED, 2))
        }

    /**
     * Criterion 3: what the two completed lines say, which is how anyone proves nothing re-billed.
     */
    private fun assertResumedLines(completed: List<JsonObject>) {
        // Picked by flag, never by position: the two exchanges end within milliseconds of each
        // other, each on its own drive, and nothing orders the lines of two separate exchanges.
        val original = completed.single { it.flag("clientDisconnected") }
        val resumed = completed.single { it.flag("resumed") }
        assertFalse(original.flag("resumed"), "the original made the upstream call")
        assertFalse(resumed.flag("clientDisconnected"), "the re-issue's client stayed")
        assertFalse(resumed.flag("replayHit"))
        assertEquals("0.0", resumed.getValue("costUsd").jsonPrimitive.content, "billed nothing")
        assertEquals(original.getValue("usage"), resumed.getValue("usage"), "the same answer")
    }

    @Test
    fun `a re-issue after the upstream finished is served wholly from the buffer`() = withResume {
        streams(frames)
        val request = messagesRequest()
        dropMidStream(request)
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertEquals(whole, post(request).bodyAsText())
        assertEquals(1, upstream.received.size, "the re-issue never reached the upstream")
        assertTrue(awaitEvents(ResumeRig.COMPLETED, 2).last().flag("resumed"))
    }

    /**
     * The eligibility rule (ADR 0002): two equal requests whose clients are both still there are a
     * user asking twice, and each is owed its own sample.
     */
    @Test
    fun `an equal request while the first client is still there is a second upstream call`() =
        withResume {
            streams(frames)
            val request = messagesRequest()
            val first = async { post(request).bodyAsText() }
            awaitEvents(ResumeRig.STARTED, 1)
            val second = async { post(request).bodyAsText() }
            withTimeout(ResumeRig.TIMEOUT_MS) {
                while (upstream.received.size < 2) delay(ResumeRig.POLL_MS)
            }
            release.complete(Unit)

            assertEquals(whole, first.await())
            assertEquals(whole, second.await())
            assertFalse(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
        }

    @Test
    fun `Chat Completions resumes an equal re-issue through the same interceptor`() = withResume {
        val chat = fixtureFrames("/openai-chat/stream-with-tool-calls.sse")
        streams(chat)
        dropMidStream(CHAT_REQUEST, CHAT_PATH)

        val reissue = async { post(CHAT_REQUEST, CHAT_PATH).bodyAsText() }
        awaitEvents(ResumeRig.STARTED, 2)
        release.complete(Unit)

        assertEquals(chat.joinToString(""), reissue.await())
        assertEquals(1, upstream.received.size)
        val resumed = awaitEvents(ResumeRig.COMPLETED, 2).single { it.flag("resumed") }
        assertEquals("openai-chat", resumed.getValue("surface").jsonPrimitive.content)
    }

    /**
     * `stream: false` is one frame and takes no path of its own. The client leaves before the
     * upstream has said anything, which is the drop the spike measured as re-issued byte for byte,
     * and the re-issue arrives while the original still has no response to serve: it waits in
     * Resume for the one the original is about to have, and never reaches the upstream.
     */
    @Test
    fun `a one-frame answer resumes for a client that left before its first byte`() = withResume {
        answersOnRelease(FakeUpstream.Reply(body = WHOLE_BODY))
        leaveBeforeAnswer(NON_STREAMING_REQUEST)

        val reissue = async { post(NON_STREAMING_REQUEST).bodyAsText() }
        delay(ResumeRig.SETTLE_MS)
        assertEquals(1, upstream.received.size, "the re-issue is parked in Resume, not relayed")
        release.complete(Unit)

        assertEquals(WHOLE_BODY, reissue.await())
        assertEquals(1, upstream.received.size)
        assertTrue(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
    }

    /** The wait above must end when the original never streams, or the re-issue hangs for good. */
    @Test
    fun `a re-issue waiting on an original that never streams goes upstream itself`() = withResume {
        answersOnRelease(
            FakeUpstream.Reply(contentType = ContentType.Application.OctetStream, body = "\u0000")
        )
        leaveBeforeAnswer(NON_STREAMING_REQUEST)

        val reissue = async { post(NON_STREAMING_REQUEST) }
        delay(ResumeRig.SETTLE_MS)
        release.complete(Unit)

        // The original was refused as a body the frame path cannot carry, so it had no stream
        // to offer; the re-issue asked for itself and was told the same.
        assertEquals(502, withTimeout(ResumeRig.TIMEOUT_MS) { reissue.await() }.status.value)
        assertEquals(2, upstream.received.size)
    }

    /** A laptop that sleeps twice: the resumed exchange is resumable when its own client goes. */
    @Test
    fun `a resumed client that drops too is resumed again, still from one upstream call`() =
        withResume {
            streams(frames)
            val request = messagesRequest()
            dropMidStream(request)
            dropMidStream(request, drops = 2)

            val third = async { post(request).bodyAsText() }
            awaitEvents(ResumeRig.STARTED, 3)
            release.complete(Unit)

            assertEquals(whole, third.await())
            assertEquals(1, upstream.received.size, "three requests, one upstream call")
            val completed = awaitEvents(ResumeRig.COMPLETED, 3)
            assertEquals(2, completed.count { it.flag("resumed") })
            assertEquals(2, completed.count { it.flag("clientDisconnected") })
        }

    /** The upstream cuts its stream [CUT_AFTER] frames in, once the first client has left. */
    private fun ResumeRig.cutsAfterTheDrop() {
        upstream.reply = {
            FakeUpstream.Reply(
                contentType = ContentType.Text.EventStream,
                frames = frames,
                beforeFrame = { index -> if (index == LEFT_AFTER) release.await() },
                cutAfterFrames = CUT_AFTER,
            )
        }
    }

    @Test
    fun `an answer whose upstream failed after the client left is not served as whole`() =
        withResume {
            cutsAfterTheDrop()
            val request = messagesRequest()
            dropMidStream(request)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)

            streams(frames)
            assertEquals(whole, post(request).bodyAsText(), "asked again, answered whole")
            assertEquals(2, upstream.received.size, "a broken answer is not worth resuming")
            assertFalse(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
        }

    /**
     * The other half of criterion 4: the joiner was already attached when the stream broke, so
     * there was no completion to refuse it. It is cut exactly where the original was cut, with no
     * terminal frame, which is what the provider would have left its own client with. What must not
     * happen is the joiner hanging on an end that never comes.
     */
    @Test
    fun `a re-issue that joined before the upstream failed is cut where the original was`() =
        withResume {
            cutsAfterTheDrop()
            val request = messagesRequest()
            dropMidStream(request)

            val reissue = async { post(request).bodyAsText() }
            awaitEvents(ResumeRig.STARTED, 2)
            release.complete(Unit)

            val served = withTimeout(ResumeRig.TIMEOUT_MS) { reissue.await() }
            assertEquals(frames.take(CUT_AFTER).joinToString(""), served, "cut, not padded out")
            assertEquals(1, upstream.received.size)
            assertTrue(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
        }

    /**
     * The hand-over swept across every point there is one. The upstream is parked at frame N when
     * the re-issue is posted, so the buffer holds N frames at that moment, and the release is fired
     * without waiting for the joiner to attach — deliberately, because that is what varies where in
     * the stream the attach actually lands: sometimes mid-append, sometimes after the end, and at a
     * different depth each time round. Every N must give the fixture back whole, so no arrival
     * point loses a frame at the seam or sends one twice. The awaited, certainly-in-flight
     * hand-over is the first test in this class and the first in `ResumeMatchTest`.
     */
    @Test
    fun `a joiner at any point of an in-flight stream gets exactly the fixture`() =
        frames.indices.drop(1).forEach(::handOverAt)

    private fun handOverAt(joinAt: Int) = withResume {
        streams(frames, holdAt = joinAt)
        val request = messagesRequest()
        dropMidStream(request, after = joinAt)

        val reissue = async { post(request).bodyAsText() }
        release.complete(Unit)

        assertEquals(whole, reissue.await(), "the hand-over at frame $joinAt")
        assertEquals(1, upstream.received.size, "one call, joining at frame $joinAt")
    }

    private companion object {
        /** Where the upstream's stream breaks: after the drop at [LEFT_AFTER], before the end. */
        const val CUT_AFTER = 9
    }
}
