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

/**
 * What the OpenAI Chat Completions surface reads out of a request and out of a response's frames.
 */
class ChatCompletionsTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/openai-chat/$name")) { name }.readBytes()

    /** Every frame in arrival order, as the deriver feeds them. */
    private fun readFixture(name: String): FrameReader =
        ChatCompletions.reader().also { reader ->
            FrameParser.parse(fixture(name)).forEach(reader::read)
        }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /**
     * One streamed chunk carrying [toolCall] in its delta, as a server that omits `index` sends.
     */
    private fun chunk(toolCall: String): Frame =
        Frame("""data: {"choices":[{"index":0,"delta":{"tool_calls":[$toolCall]}}]}""" + "\n\n", 0)

    @Test
    fun `a streamed turn gives its model, usage, finish reason, and every tool call`() {
        val reader = readFixture("stream-with-tool-calls.sse")

        assertEquals("gpt-4o-mini-2024-07-18", reader.model)
        // 92 prompt tokens of which 64 were cached: the cached part is not counted twice.
        assertEquals(Usage(input = 28, output = 48, cacheRead = 64, cacheWrite = 0), reader.usage)
        assertEquals("tool_calls", reader.stopReason)
        assertEquals(
            listOf(
                ToolCall("Read", path = "src/main.kt", command = null),
                ToolCall("Bash", path = null, command = "echo peashoot"),
            ),
            reader.tools,
            "two calls, each assembled from fragments spread over three chunks",
        )
    }

    @Test
    fun `a stream that did not ask for usage reports none, never zero`() {
        val reader = readFixture("stream-without-usage.sse")

        assertEquals("gpt-4o-mini-2024-07-18", reader.model)
        assertNull(reader.usage, "no stream_options.include_usage means no usage was reported")
        assertEquals("stop", reader.stopReason)
        assertEquals(emptyList(), reader.tools)
    }

    @Test
    fun `a non-streaming body says everything at once`() {
        val reader = readFixture("non-streaming-tool-calls.json")

        assertEquals("gpt-4o-mini-2024-07-18", reader.model)
        assertEquals(Usage(input = 80, output = 17, cacheRead = 0, cacheWrite = 0), reader.usage)
        assertEquals("tool_calls", reader.stopReason)
        assertEquals(
            listOf(ToolCall("Bash", path = null, command = "echo peashoot")),
            reader.tools,
        )
    }

    @Test
    fun `the done marker and anything else unparseable leave the reader as it was`() {
        val reader = readFixture("stream-without-usage.sse")

        listOf("data: [DONE]\n\n", ": keep-alive\n\n", "\n", "data: not json\n\n").forEach {
            reader.read(Frame(it, 0))
        }

        assertEquals("gpt-4o-mini-2024-07-18", reader.model)
        assertEquals("stop", reader.stopReason)
        assertNull(reader.usage)
    }

    @Test
    fun `a later chunk carrying a null usage does not erase the usage already reported`() {
        val reader = readFixture("stream-with-tool-calls.sse")
        val reported = reader.usage

        // Every chunk of an include_usage stream but the last carries `"usage": null`, and a
        // provider is free to send one after the count too. Null is silence, not zero.
        reader.read(Frame("""data: {"choices":[],"usage":null}""" + "\n\n", 0))

        assertEquals(reported, reader.usage)
        assertEquals(Usage(input = 28, output = 48, cacheRead = 64, cacheWrite = 0), reader.usage)
    }

    @Test
    fun `a negative cached count cannot make a negative cache read`() {
        val reader = ChatCompletions.reader()

        reader.read(
            Frame(
                """data: {"choices":[],"usage":{"prompt_tokens":10,""" +
                    """"prompt_tokens_details":{"cached_tokens":-5},"completion_tokens":3}}""" +
                    "\n\n",
                0,
            )
        )

        assertEquals(
            Usage(input = 10, output = 3, cacheRead = 0, cacheWrite = 0),
            reader.usage,
            "a nonsense count is floored rather than priced as a negative cost",
        )
    }

    @Test
    fun `a server that streams whole calls with no index keeps them apart by id`() {
        val reader = ChatCompletions.reader()

        // Ollama and friends send each call complete, in its own chunk, with no `index` at all.
        // Keyed by position they would all be call 0 and their arguments would run together.
        listOf(
                chunk(
                    """{"id":"call_a","type":"function","function":""" +
                        """{"name":"Read","arguments":"{\"file_path\":\"a.kt\"}"}}"""
                ),
                chunk(
                    """{"id":"call_b","type":"function","function":""" +
                        """{"name":"Bash","arguments":"{\"command\":\"echo hi\"}"}}"""
                ),
            )
            .forEach(reader::read)

        assertEquals(
            listOf(
                ToolCall("Read", path = "a.kt", command = null),
                ToolCall("Bash", path = null, command = "echo hi"),
            ),
            reader.tools,
        )
    }

    @Test
    fun `a fragment with no index and no id continues the call still open`() {
        val reader = ChatCompletions.reader()

        listOf(
                chunk(
                    """{"id":"call_a","type":"function","function":""" +
                        """{"name":"Bash","arguments":"{\"comm"}}"""
                ),
                chunk("""{"function":{"arguments":"and\":\"echo hi\"}"}}"""),
            )
            .forEach(reader::read)

        assertEquals(listOf(ToolCall("Bash", path = null, command = "echo hi")), reader.tools)
    }

    @Test
    fun `a tool result is named by the tool call it answers`() {
        assertEquals(
            listOf(ToolResult("Read", bytes = 13), ToolResult("Bash", bytes = 8)),
            ChatCompletions.toolResults(json(TOOL_TURN)),
        )
        assertEquals(
            emptyList(),
            ChatCompletions.toolResults(json("""{"messages":[{"role":"user","content":"hi"}]}""")),
            "a turn that feeds nothing back has no results",
        )
        assertEquals(emptyList(), ChatCompletions.toolResults(null))
    }

    @Test
    fun `the request's model is the model it named`() {
        assertEquals("gpt-4o-mini", ChatCompletions.model(json(TOOL_TURN)))
        assertNull(ChatCompletions.model(json("{}")))
        assertNull(ChatCompletions.model(null))
    }

    @Test
    fun `the rate limit is what the provider's headers said, or nothing`() {
        assertEquals(
            RateLimit(remainingTokens = 9_000, remainingRequests = 59, resetAt = "6m0s"),
            ChatCompletions.rateLimit(
                headersOf(
                    "x-ratelimit-remaining-tokens" to listOf("9000"),
                    "x-ratelimit-remaining-requests" to listOf("59"),
                    "x-ratelimit-reset-tokens" to listOf("6m0s"),
                )
            ),
        )
        assertEquals(
            RateLimit(remainingTokens = null, remainingRequests = null, resetAt = "1s"),
            ChatCompletions.rateLimit(headersOf("x-ratelimit-reset-requests", "1s")),
            "the requests reset stands in when there is no token reset",
        )
        assertNull(ChatCompletions.rateLimit(Headers.Empty))
    }

    @Test
    fun `nothing on this surface is subscription traffic`() {
        assertFalse(ChatCompletions.isOAuth(headersOf("anthropic-beta", "oauth-2025-04-20")))
    }

    private companion object {
        /** An assistant turn that asked for two tools, with both results fed back after it. */
        const val TOOL_TURN =
            """{"model":"gpt-4o-mini","messages":[""" +
                """{"role":"user","content":"read it"},""" +
                """{"role":"assistant","content":null,"tool_calls":[""" +
                """{"id":"call_1","type":"function","function":{"name":"Read","arguments":"{}"}},""" +
                """{"id":"call_2","type":"function","function":{"name":"Bash","arguments":"{}"}}""" +
                """]},""" +
                """{"role":"tool","tool_call_id":"call_1","content":"file contents"},""" +
                """{"role":"tool","tool_call_id":"call_2","content":"peashoot"}]}"""
    }
}
