package dev.peashoot.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which frame ends a whole answer, per surface, against the captured streams themselves.
 *
 * This is what stands between a re-issue and a broken answer served as a complete one (#26, ADR
 * 0002). An upstream cut short mostly does not raise — the socket ends and the body is over — so
 * nothing but the presence of the surface's own terminal frame tells a whole answer from a cut one,
 * and every fixture here is checked from both ends: the real last frame terminates, and no frame
 * before it does, which is the same as saying every truncation of the stream is judged incomplete.
 */
class TerminalFrameTest {
    private fun frames(resource: String): List<Frame> =
        FrameParser.parse(
            checkNotNull(javaClass.getResourceAsStream(resource)) { resource }.readBytes()
        )

    private fun body(resource: String): Frame =
        Frame(
            checkNotNull(javaClass.getResourceAsStream(resource)) { resource }
                .readBytes()
                .decodeToString(),
            0,
        )

    /** The last frame ends it, and cutting the stream anywhere earlier does not. */
    private fun assertOnlyTheLastEnds(surface: Surface, resource: String) {
        val frames = frames(resource)
        assertTrue(frames.size > 1, resource)
        assertTrue(surface.terminates(frames.last()), "$resource ends whole")
        frames.dropLast(1).forEachIndexed { index, frame ->
            assertFalse(surface.terminates(frame), "$resource cut after frame $index is not whole")
        }
    }

    @Test
    fun `a Messages stream ends on message_stop and nowhere else`() {
        assertOnlyTheLastEnds(Messages, "/anthropic-messages/stream-with-tool-use.sse")
        assertOnlyTheLastEnds(Messages, "/anthropic-messages/stream-with-file-tools.sse")
    }

    /**
     * A stream the provider ended with an error event is complete HTTP and an incomplete answer.
     * Resume refuses it, so the client asking again gets a fresh ask rather than the failure it has
     * already seen.
     */
    @Test
    fun `a Messages stream that ends in an error event ends nothing`() {
        val frames = frames("/anthropic-messages/stream-ending-in-error.sse")
        assertFalse(Messages.terminates(frames.last()))
        assertFalse(frames.any { Messages.terminates(it) }, "no frame of it ends the answer")
    }

    @Test
    fun `a Chat Completions stream ends on the done marker and nowhere else`() {
        assertOnlyTheLastEnds(ChatCompletions, "/openai-chat/stream-with-tool-calls.sse")
        assertOnlyTheLastEnds(ChatCompletions, "/openai-chat/stream-without-usage.sse")
    }

    /**
     * `response.incomplete` is a model that stopped early, which is still the whole of what the
     * provider had to say, so it ends the stream exactly as `response.completed` does.
     */
    @Test
    fun `a Responses stream ends on completed or incomplete and nowhere else`() {
        assertOnlyTheLastEnds(Responses, "/openai-responses/stream-with-function-call.sse")
        assertOnlyTheLastEnds(Responses, "/openai-responses/stream-incomplete.sse")
    }

    /**
     * `stream: false` is one frame, and that frame is the whole answer. Messages has no captured
     * non-streaming success to read — the only body fixture beside it is a 401 — so its shape is
     * spelled out here, which is the one thing on this surface no capture pins.
     */
    @Test
    fun `a non-streaming body ends itself, on every surface`() {
        assertTrue(Messages.terminates(Frame(MESSAGE_BODY, 0)))
        assertTrue(ChatCompletions.terminates(body("/openai-chat/non-streaming-tool-calls.json")))
        assertTrue(Responses.terminates(body("/openai-responses/non-streaming-function-call.json")))
    }

    /**
     * An error body is a complete HTTP response and no answer at all. Resume never reaches this — a
     * non-2xx is refused before the terminal frame is asked about — but the two guards say the same
     * thing, and a body that says nothing must not be read as a finished answer.
     */
    @Test
    fun `a body that is not an answer ends nothing`() {
        assertFalse(Messages.terminates(body("/anthropic-messages/non-streaming-401.json")))
        assertFalse(ChatCompletions.terminates(Frame(ERROR_BODY, 0)))
        assertFalse(Responses.terminates(Frame(ERROR_BODY, 0)))
    }

    @Test
    fun `what carries no answer at all ends nothing`() {
        listOf(Messages, ChatCompletions, Responses).forEach { surface ->
            assertFalse(surface.terminates(Frame(": keep-alive\n\n", 0)), "${surface.name} ping")
            assertFalse(surface.terminates(Frame("", 0)), "${surface.name} empty")
            assertFalse(surface.terminates(Frame("not json at all", 0)), "${surface.name} rubbish")
        }
    }

    /**
     * A failed response is the provider reporting that the turn did not happen. Like Messages'
     * `error` event it does not end an answer, so a re-issue after one goes upstream.
     */
    @Test
    fun `a failed Responses event ends nothing`() {
        assertFalse(Responses.terminates(Frame("event: response.failed\ndata: {}\n\n", 0)))
        assertFalse(Responses.terminates(Frame("event: response.in_progress\ndata: {}\n\n", 0)))
    }

    private companion object {
        const val MESSAGE_BODY =
            """{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
                """"stop_reason":"end_turn"}"""
        const val ERROR_BODY = """{"error":{"message":"Rate limit reached","type":"tokens"}}"""
    }
}
