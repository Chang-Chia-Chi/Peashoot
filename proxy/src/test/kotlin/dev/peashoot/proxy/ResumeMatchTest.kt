package dev.peashoot.proxy

import io.ktor.client.statement.bodyAsText
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

/** The block the re-issue appends, in this test's wording and nothing like the client's. */
private const val APPENDED_TEXT =
    """{"type":"text","text":"The line dropped. Carry on from wherever that leaves you."}"""

/** The original's last block with the newline the client was measured appending to it. */
private const val PADDED_LAST =
    """{"type":"text","text":"Count from 1 to 300, one number per line.\n"}"""

/**
 * The continuation match (ADR 0002) at the proxy's HTTP boundary: Claude Code re-issues a cut
 * Messages stream with the content of its last user message extended, and that re-issue is served
 * the whole buffered answer with no second upstream call. Every other difference is a different
 * request and is owed its own call.
 */
class ResumeMatchTest {
    private val frames = fixtureFrames("/anthropic-messages/stream-with-tool-use.sse")
    private val whole = frames.joinToString("")

    private fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    /**
     * The case story 21 exists for, in the shape the spike recorded it: the drop is mid-stream, the
     * upstream is still streaming when the re-issue lands 31 to 47 ms later, and what the client is
     * given is the whole original answer rather than the tail it asked to carry on from.
     */
    @Test
    fun `a re-issue with the last user message extended joins the answer in flight`() = withResume {
        streams(frames)
        dropMidStream(messagesRequest(ORIGINAL_BLOCKS))

        val reissue = async { post(messagesRequest(CONTINUED_BLOCKS)).bodyAsText() }
        awaitEvents(ResumeRig.STARTED, 2)
        release.complete(Unit)

        assertEquals(whole, reissue.await(), "the whole original answer, not its tail")
        assertEquals(1, upstream.received.size, "and no second upstream call")
        assertTrue(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
    }

    /**
     * The control for every negative below: the same setup, with the one re-issue that is a
     * continuation. Without it a bug that made nothing at all match would pass the whole file.
     */
    @Test
    fun `the control, a continuation of a finished answer is resumed`() = withResume {
        streams(frames)
        dropMidStream(messagesRequest(ORIGINAL_BLOCKS))
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertEquals(whole, post(messagesRequest(CONTINUED_BLOCKS)).bodyAsText())
        assertEquals(1, upstream.received.size)
        assertTrue(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
    }

    /**
     * A re-issue the match must not take for a continuation. The original is buffered, whole, and
     * its client is gone — everything but the request itself says hit — and the provider is still
     * asked.
     */
    private fun goesUpstream(reissue: String) = withResume {
        streams(frames)
        dropMidStream(messagesRequest(ORIGINAL_BLOCKS))
        release.complete(Unit)
        awaitEvents(ResumeRig.COMPLETED, 1)

        assertEquals(whole, post(reissue).bodyAsText())
        assertEquals(2, upstream.received.size, "a request of its own")
        assertFalse(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
    }

    @Test
    fun `an appended block that is not text is a different request`() =
        goesUpstream(messagesRequest("$FIRST_BLOCK,$PADDED_LAST,$APPENDED_IMAGE"))

    @Test
    fun `a changed earlier block is a different request`() =
        goesUpstream(messagesRequest("$OTHER_FIRST_BLOCK,$PADDED_LAST,$APPENDED_TEXT"))

    /** The whitespace allowance is trailing whitespace, not any edit to the same block. */
    @Test
    fun `a last block changed by more than its trailing whitespace is a different request`() =
        goesUpstream(messagesRequest("$FIRST_BLOCK,$REWORDED_LAST,$APPENDED_TEXT"))

    @Test
    fun `content the original is not a prefix of is a different request`() =
        goesUpstream(messagesRequest(FIRST_BLOCK))

    @Test
    fun `a changed system prompt is a different request`() =
        goesUpstream(messagesRequest(CONTINUED_BLOCKS, system = "You count in Welsh."))

    @Test
    fun `a changed tool list is a different request`() =
        goesUpstream(messagesRequest(CONTINUED_BLOCKS, tools = "[]"))

    @Test
    fun `a changed model is a different request`() =
        goesUpstream(messagesRequest(CONTINUED_BLOCKS, model = "claude-opus-5"))

    @Test
    fun `one more message is a different request`() =
        goesUpstream(messagesRequest(CONTINUED_BLOCKS, messagesAfter = LATER_MESSAGE))

    /**
     * Eligibility, the half of ADR 0002 that is not about shape: a client that is still there is a
     * user who asked twice, and the second ask is owed its own sample however it is worded.
     */
    @Test
    fun `a continuation of a request whose client is still there is a second call`() = withResume {
        streams(frames)
        val first = async { post(messagesRequest(ORIGINAL_BLOCKS)).bodyAsText() }
        awaitEvents(ResumeRig.STARTED, 1)
        val second = async { post(messagesRequest(CONTINUED_BLOCKS)).bodyAsText() }
        withTimeout(ResumeRig.TIMEOUT_MS) {
            while (upstream.received.size < 2) delay(ResumeRig.POLL_MS)
        }
        release.complete(Unit)

        assertEquals(whole, first.await())
        assertEquals(whole, second.await())
        assertFalse(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
    }

    /**
     * Only Messages has a measured continuation story, so Chat Completions resumes on an equal
     * fingerprint and on nothing else. An extended last message there is simply a new question.
     */
    @Test
    fun `Chat Completions has no continuation story, so an extended message asks again`() =
        withResume {
            val chat = fixtureFrames("/openai-chat/stream-with-tool-calls.sse")
            streams(chat)
            dropMidStream(CHAT_REQUEST, CHAT_PATH)
            release.complete(Unit)
            awaitEvents(ResumeRig.COMPLETED, 1)

            assertEquals(chat.joinToString(""), post(CHAT_EXTENDED, CHAT_PATH).bodyAsText())
            assertEquals(2, upstream.received.size, "only Messages was ever measured")
            assertFalse(awaitEvents(ResumeRig.COMPLETED, 2).any { it.flag("resumed") })
        }

    private companion object {
        const val APPENDED_IMAGE =
            """{"type":"image","source":{"type":"base64","media_type":"image/png",""" +
                """"data":"iVBORw0KGgo="}}"""
        const val OTHER_FIRST_BLOCK = """{"type":"text","text":"different context entirely"}"""
        const val REWORDED_LAST =
            """{"type":"text","text":"Count from 1 to 300, one number per line!"}"""
        const val LATER_MESSAGE = ""","{"role":"user","content":"and once more"}"""
        const val CHAT_EXTENDED =
            """{"model":"gpt-4o-mini","stream":true,"stream_options":{"include_usage":true},""" +
                """"messages":[{"role":"user","content":"count to three\ncarry on"}]}"""
    }
}
