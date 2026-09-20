package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which surface a request belongs to: its path, and its headers where two surfaces share one. */
class SurfaceTest {
    @Test
    fun `a path one surface owns decides on its own`() {
        assertEquals(Messages, surfaceOf("/v1/messages", Headers.Empty))
        assertEquals(Messages, surfaceOf("/v1/messages/count_tokens", Headers.Empty))
        assertEquals(ChatCompletions, surfaceOf("/v1/chat/completions", Headers.Empty))
        assertEquals(
            ChatCompletions,
            surfaceOf("/v1/chat/completions", headersOf("anthropic-version", "2023-06-01")),
            "a header cannot move a request off the surface whose path it asked for",
        )
    }

    @Test
    fun `a query string is not part of the path`() {
        assertEquals(ChatCompletions, surfaceOf("/v1/chat/completions?beta=true", Headers.Empty))
        assertEquals(
            Responses,
            surfaceOf("/v1/responses/resp_1?stream=true&starting_after=3", Headers.Empty),
        )
    }

    @Test
    fun `everything under the responses path is the Responses surface`() {
        listOf(
                "/v1/responses",
                "/v1/responses/resp_1",
                "/v1/responses/resp_1/cancel",
                "/v1/responses/resp_1/input_items",
            )
            .forEach { assertEquals(Responses, surfaceOf(it, Headers.Empty), it) }
        assertEquals(
            Messages,
            surfaceOf("/v1/responses_beta", Headers.Empty),
            "the separator is required, so a neighbouring path is not silently this surface's",
        )
    }

    @Test
    fun `model listing, which both surfaces pass through, is decided by the sender`() {
        assertEquals(
            Messages,
            surfaceOf("/v1/models", headersOf("anthropic-version", "2023-06-01")),
        )
        assertEquals(
            Messages,
            surfaceOf(
                "/v1/models",
                headersOf(
                    "anthropic-version" to listOf("2023-06-01"),
                    "x-stainless-lang" to listOf("python"),
                ),
            ),
            "the Anthropic SDK is Stainless-generated too, so its own version header comes first",
        )
        assertEquals(ChatCompletions, surfaceOf("/v1/models", headersOf("openai-project", "p-1")))
        assertEquals(
            Messages,
            surfaceOf("/v1/models", headersOf("X-Stainless-Lang", "js")),
            "both SDKs are Stainless-generated, so that header alone names neither: it would " +
                "speak only where the two are hard to tell apart, and send an Anthropic SDK " +
                "that omitted its version header to OpenAI",
        )
        assertEquals(
            ChatCompletions,
            surfaceOf("/v1/models", headersOf("user-agent", "OpenAI/Python 1.109.1")),
        )
    }

    @Test
    fun `a request with nothing to go on takes the surface that was here first`() {
        assertEquals(Messages, surfaceOf("/v1/models", Headers.Empty))
        assertEquals(Messages, surfaceOf("/v1/files/abc", headersOf("user-agent", "curl/8.7.1")))
    }

    @Test
    fun `each surface names itself for the event line`() {
        assertEquals("anthropic-messages", Messages.name)
        assertEquals("openai-chat", ChatCompletions.name)
        assertEquals("openai-responses", Responses.name)
    }
}
