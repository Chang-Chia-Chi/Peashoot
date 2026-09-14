package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What the Anthropic Messages surface reads out of a request and out of a response's frames. */
class MessagesTest {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/anthropic-messages/$name")) { name }
            .readBytes()

    /** Every frame in arrival order, as the deriver feeds them. */
    private fun read(frames: List<Frame>): Messages.Reader =
        Messages.Reader().also { reader -> frames.forEach(reader::read) }

    private fun readFixture(name: String): Messages.Reader = read(FrameParser.parse(fixture(name)))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a streamed turn gives its model, merged usage, stop reason, and tool call`() {
        val reader = readFixture("stream-with-tool-use.sse")

        assertEquals("claude-sonnet-4-5-20250929", reader.model)
        // message_start says 25 in and 1 out; the message_delta's output_tokens overrides the 1.
        assertEquals(Usage(input = 25, output = 42, cacheRead = 0, cacheWrite = 0), reader.usage)
        assertEquals("tool_use", reader.stopReason)
        assertEquals(listOf(ToolCall("Bash", path = null, command = "echo peashoot")), reader.tools)
    }

    @Test
    fun `a turn with file tools gives the usage in its message_delta and every tool call`() {
        val reader = readFixture("stream-with-file-tools.sse")

        assertEquals("claude-sonnet-4-5-20250929", reader.model)
        assertEquals(
            Usage(input = 1200, output = 95, cacheRead = 8000, cacheWrite = 300),
            reader.usage,
        )
        assertEquals("tool_use", reader.stopReason)
        assertEquals(
            listOf(
                ToolCall("Read", path = "src/Main.kt", command = null),
                ToolCall("Edit", path = "src/Main.kt", command = null),
                ToolCall("Bash", path = null, command = "./gradlew test"),
            ),
            reader.tools,
        )
    }

    @Test
    fun `a stream that ends in an error keeps the usage from its message_start and has no tools`() {
        val reader = readFixture("stream-ending-in-error.sse")

        assertEquals("claude-sonnet-4-5-20250929", reader.model)
        assertEquals(Usage(input = 25, output = 1, cacheRead = 0, cacheWrite = 0), reader.usage)
        assertNull(reader.stopReason)
        assertEquals(emptyList(), reader.tools)
    }

    @Test
    fun `a non-streaming error body contributes nothing`() {
        val reader = read(listOf(Frame(fixture("non-streaming-401.json").decodeToString(), 0)))

        assertNull(reader.model)
        assertNull(reader.usage)
        assertNull(reader.stopReason)
        assertEquals(emptyList(), reader.tools)
    }

    @Test
    fun `a non-streaming message body gives every field at once`() {
        val reader = read(listOf(Frame(NON_STREAMING_MESSAGE, 0)))

        assertEquals("claude-opus-4-5-20251101", reader.model)
        assertEquals(Usage(input = 7, output = 11, cacheRead = 3, cacheWrite = 2), reader.usage)
        assertEquals("tool_use", reader.stopReason)
        assertEquals(
            listOf(
                ToolCall("Read", path = "src/Main.kt", command = null),
                ToolCall("NotebookEdit", path = "notes/run.ipynb", command = null),
            ),
            reader.tools,
        )
    }

    @Test
    fun `tool results come from the last message, named by the tool_use they answer`() {
        // "package dev" is 11 bytes of its own; the array content is the 35 bytes of
        // [{"type":"text","text":"peashoot"}]; the third answers a tool_use nothing declared.
        assertEquals(
            listOf(
                ToolResult("Read", bytes = 11),
                ToolResult("Bash", bytes = 35),
                ToolResult(null, bytes = 6),
            ),
            Messages.toolResults(json(TOOL_RESULT_REQUEST)),
        )
    }

    @Test
    fun `the model and the conversation's first user message are read from the request`() {
        val request = json(TOOL_RESULT_REQUEST)

        assertEquals("claude-sonnet-4-5-20250929", Messages.model(request))
        assertEquals("read it", Messages.firstUserMessage(request))
        assertNull(Messages.model(null))
        assertNull(Messages.firstUserMessage(null))
        assertNull(Messages.firstUserMessage(json("""{"messages":[]}""")))
        assertNull(
            Messages.firstUserMessage(
                json("""{"messages":[{"role":"assistant","content":"hi"}]}""")
            ),
            "a conversation that has no user message yet has nothing to group by",
        )
    }

    @Test
    fun `oauth traffic is any anthropic-beta value that mentions oauth`() {
        assertTrue(Messages.isOAuth(headersOf("anthropic-beta", "oauth-2025-04-20")))
        assertTrue(
            Messages.isOAuth(
                headersOf(
                    "anthropic-beta",
                    listOf("fine-grained-tool-streaming", "oauth-2025-04-20"),
                )
            )
        )
        assertFalse(Messages.isOAuth(headersOf("anthropic-beta", "kept")))
        assertFalse(Messages.isOAuth(Headers.Empty))
    }

    @Test
    fun `rate-limit headers are read, and a missing or non-numeric value is a null field`() {
        assertEquals(
            RateLimit(remainingTokens = 9000, remainingRequests = 50, resetAt = RESET_AT),
            Messages.rateLimit(
                headersOf(
                    "anthropic-ratelimit-tokens-remaining" to listOf("9000"),
                    "anthropic-ratelimit-requests-remaining" to listOf("50"),
                    "anthropic-ratelimit-tokens-reset" to listOf(RESET_AT),
                )
            ),
        )
        assertNull(Messages.rateLimit(Headers.Empty), "no rate-limit header means no rate limit")
        assertEquals(
            RateLimit(remainingTokens = null, remainingRequests = 50, resetAt = null),
            Messages.rateLimit(
                headersOf(
                    "anthropic-ratelimit-tokens-remaining" to listOf("unlimited"),
                    "anthropic-ratelimit-requests-remaining" to listOf("50"),
                )
            ),
        )
        assertEquals(
            RateLimit(remainingTokens = null, remainingRequests = null, resetAt = RESET_AT),
            Messages.rateLimit(headersOf("anthropic-ratelimit-requests-reset", RESET_AT)),
            "the requests reset stands in when there is no tokens reset",
        )
    }

    private companion object {
        const val RESET_AT = "2026-09-14T09:00:00Z"

        const val NON_STREAMING_MESSAGE =
            """{"id":"msg_REDACTED","type":"message","role":"assistant",""" +
                """"model":"claude-opus-4-5-20251101","stop_reason":"tool_use",""" +
                """"content":[{"type":"text","text":"Reading."},""" +
                """{"type":"tool_use","id":"toolu_REDACTED","name":"Read",""" +
                """"input":{"file_path":"src/Main.kt"}},""" +
                """{"type":"tool_use","id":"toolu_NOTEBOOK","name":"NotebookEdit",""" +
                """"input":{"notebook_path":"notes/run.ipynb","new_source":"1 + 1"}}],""" +
                """"usage":{"input_tokens":7,"cache_creation_input_tokens":2,""" +
                """"cache_read_input_tokens":3,"output_tokens":11}}"""

        const val TOOL_RESULT_REQUEST =
            """{"model":"claude-sonnet-4-5-20250929","messages":[""" +
                """{"role":"user","content":[{"type":"text","text":"read it"}]},""" +
                """{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"toolu_1","name":"Read",""" +
                """"input":{"file_path":"src/Main.kt"}},""" +
                """{"type":"tool_use","id":"toolu_2","name":"Bash","input":{"command":"ls"}}]},""" +
                """{"role":"user","content":[""" +
                """{"type":"tool_result","tool_use_id":"toolu_1","content":"package dev"},""" +
                """{"type":"tool_result","tool_use_id":"toolu_2",""" +
                """"content":[{"type":"text","text":"peashoot"}]},""" +
                """{"type":"tool_result","tool_use_id":"toolu_9","content":"orphan"}]}]}"""
    }
}
