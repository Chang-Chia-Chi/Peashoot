package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the OpenAI Responses surface reads out of a request and out of a response's frames. */
class ResponsesTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/openai-responses/$name")) { name }.readBytes()

    /** Every frame in arrival order, as the deriver feeds them. */
    private fun readFixture(name: String): FrameReader =
        Responses.reader().also { reader -> FrameParser.parse(fixture(name)).forEach(reader::read) }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a streamed turn gives its model, usage, status, and every function call`() {
        val reader = readFixture("stream-with-function-call.sse")

        assertEquals("gpt-5-codex-2026-03-01", reader.model, "the completed response resolves it")
        // 92 input tokens of which 64 were cached: the cached part is not counted twice.
        assertEquals(Usage(input = 28, output = 48, cacheRead = 64, cacheWrite = 0), reader.usage)
        assertEquals("completed", reader.stopReason)
        assertEquals(
            listOf(
                ToolCall("shell", path = null, command = "echo peashoot"),
                ToolCall("Read", path = "src/main.kt", command = null),
            ),
            reader.tools,
            "two calls whose argument deltas interleaved, each assembled under its own item id",
        )
    }

    @Test
    fun `the completed response's own output does not list a call the stream already opened`() {
        // `response.completed` repeats every item whole in `output[]`. Keyed by the same item id
        // the added/done events and the deltas used, those repeats land on the calls already open
        // instead of doubling them, which is the only reason reading both shapes is safe.
        assertEquals(2, readFixture("stream-with-function-call.sse").tools.size)
    }

    @Test
    fun `a turn that ran out of room says so, and reported no usage at all`() {
        val reader = readFixture("stream-incomplete.sse")

        assertEquals("gpt-5-2026-01-15", reader.model)
        assertNull(reader.usage, "a null usage is silence, not a free turn")
        assertEquals("incomplete", reader.stopReason)
        assertEquals(emptyList(), reader.tools, "an assistant message is not a function call")
    }

    @Test
    fun `a non-streaming body says everything at once`() {
        val reader = readFixture("non-streaming-function-call.json")

        assertEquals("gpt-5-codex-2026-03-01", reader.model)
        assertEquals(Usage(input = 80, output = 17, cacheRead = 0, cacheWrite = 0), reader.usage)
        assertEquals("completed", reader.stopReason)
        assertEquals(
            listOf(ToolCall("shell", path = null, command = "echo peashoot")),
            reader.tools,
            "the reasoning item beside it is not a call",
        )
    }

    @Test
    fun `the status is the last one a frame reported, so a cut stream says it never finished`() {
        val reader = Responses.reader()
        val created = FrameParser.parse(fixture("stream-with-function-call.sse")).first()

        reader.read(created)

        assertEquals("in_progress", reader.stopReason)
        assertEquals(
            "gpt-5-codex",
            reader.model,
            "the alias, until the completed frame resolves it",
        )
        assertNull(reader.usage)
    }

    @Test
    fun `a frame that parses as nothing leaves the reader as it was`() {
        val reader = readFixture("stream-incomplete.sse")

        listOf(": keep-alive\n\n", "\n", "data: not json\n\n", "event: ping\ndata: \n\n").forEach {
            reader.read(Frame(it, 0))
        }

        assertEquals("gpt-5-2026-01-15", reader.model)
        assertEquals("incomplete", reader.stopReason)
        assertNull(reader.usage)
    }

    @Test
    fun `a failed response reports the status it failed with`() {
        val reader = Responses.reader()

        reader.read(
            Frame(
                "event: response.failed\ndata: " +
                    """{"type":"response.failed","sequence_number":3,"response":{""" +
                    """"id":"resp_1","object":"response","status":"failed","model":"gpt-5",""" +
                    """"error":{"code":"server_error","message":"upstream fell over"}}}""" +
                    "\n\n",
                0,
            )
        )

        assertEquals("failed", reader.stopReason)
        assertEquals("gpt-5", reader.model)
    }

    @Test
    fun `a call streamed with no item id falls back to its output index`() {
        val reader = Responses.reader()

        listOf(
                """{"type":"response.output_item.added","output_index":0,""" +
                    """"item":{"type":"function_call","name":"shell","arguments":""}}""",
                """{"type":"response.function_call_arguments.delta","output_index":0,""" +
                    """"delta":"{\"command\":\"echo hi\"}"}""",
            )
            .forEach { reader.read(Frame("data: $it\n\n", 0)) }

        assertEquals(listOf(ToolCall("shell", path = null, command = "echo hi")), reader.tools)
    }

    @Test
    fun `a tool result is named by the function call it answers`() {
        assertEquals(
            listOf(ToolResult("Read", bytes = 13), ToolResult("shell", bytes = 8)),
            Responses.toolResults(json(TOOL_TURN)),
        )
        assertEquals(
            emptyList(),
            Responses.toolResults(json("""{"input":[{"role":"user","content":"hi"}]}""")),
            "a turn that feeds nothing back has no results",
        )
        assertEquals(
            emptyList(),
            Responses.toolResults(json("""{"input":"just a string"}""")),
            "`input` may be one string, which carries no results either",
        )
        assertEquals(emptyList(), Responses.toolResults(null), "and a GET has no body at all")
    }

    @Test
    fun `the request's model is the model it named`() {
        assertEquals("gpt-5-codex", Responses.model(json(TOOL_TURN)))
        assertNull(Responses.model(json("{}")))
        assertNull(Responses.model(null))
    }

    @Test
    fun `the rate limit is what the provider's headers said, or nothing`() {
        assertEquals(
            RateLimit(remainingTokens = 9_000, remainingRequests = 59, resetAt = "6m0s"),
            Responses.rateLimit(
                headersOf(
                    "x-ratelimit-remaining-tokens" to listOf("9000"),
                    "x-ratelimit-remaining-requests" to listOf("59"),
                    "x-ratelimit-reset-tokens" to listOf("6m0s"),
                )
            ),
        )
        assertNull(Responses.rateLimit(Headers.Empty))
    }

    @Test
    fun `nothing on this surface is subscription traffic`() {
        assertFalse(Responses.isOAuth(headersOf("anthropic-beta", "oauth-2025-04-20")))
    }

    private companion object {
        /**
         * A turn that feeds two results back. `input` carries the whole conversation, the calls
         * that asked for them included, so a result is named from the array it arrived in.
         */
        const val TOOL_TURN =
            """{"model":"gpt-5-codex","input":[""" +
                """{"role":"user","content":"read it"},""" +
                """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"Read",""" +
                """"arguments":"{}"},""" +
                """{"type":"function_call","id":"fc_2","call_id":"call_2","name":"shell",""" +
                """"arguments":"{}"},""" +
                """{"type":"function_call_output","call_id":"call_1","output":"file contents"},""" +
                """{"type":"function_call_output","call_id":"call_2","output":"peashoot"}]}"""
    }
}
