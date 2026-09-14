package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Who sent an exchange, detected from its headers in the documented order. */
class ClientTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the peashoot session header wins over the client's own session`() {
        assertEquals(
            Client("claude-code", session = "injected-1", agent = null, parentAgent = null),
            Client.detect(
                headersOf(
                    "x-peashoot-session" to listOf("injected-1"),
                    "x-claude-code-session-id" to listOf("sess-1"),
                ),
                null,
            ),
        )
    }

    @Test
    fun `a Claude Code request carries its session, agent, and parent agent`() {
        assertEquals(
            Client("claude-code", session = "sess-1", agent = "agent-1", parentAgent = "parent-1"),
            Client.detect(
                headersOf(
                    "x-claude-code-session-id" to listOf("sess-1"),
                    "x-claude-code-agent-id" to listOf("agent-1"),
                    "x-claude-code-parent-agent-id" to listOf("parent-1"),
                ),
                null,
            ),
        )
        assertEquals(
            Client("claude-code", session = "sess-1", agent = null, parentAgent = null),
            Client.detect(headersOf("x-claude-code-session-id", "sess-1"), null),
            "a main-thread turn has no agent and no parent",
        )
    }

    @Test
    fun `a Codex request is detected from its originator`() {
        assertEquals(
            Client("codex", session = "cx-1", agent = null, parentAgent = null),
            Client.detect(
                headersOf("originator" to listOf("codex_cli_rs"), "session-id" to listOf("cx-1")),
                null,
            ),
        )
    }

    @Test
    fun `an official SDK is detected from its stainless headers`() {
        assertEquals(
            Client("sdk-python", session = null, agent = null, parentAgent = null),
            Client.detect(
                headersOf(
                    "X-Stainless-Lang" to listOf("Python"),
                    "x-stainless-package-version" to listOf("0.39.0"),
                ),
                null,
            ),
        )
        assertEquals(
            Client("sdk", session = null, agent = null, parentAgent = null),
            Client.detect(headersOf("X-Stainless-Retry-Count", "0"), null),
            "an SDK that names no language is still an SDK",
        )
    }

    @Test
    fun `any other client is its user-agent product token`() {
        assertEquals(
            Client("opencode", session = null, agent = null, parentAgent = null),
            Client.detect(headersOf("user-agent", "OpenCode/1.2.3 (darwin arm64)"), null),
        )
        assertEquals(
            Client("curl", session = null, agent = null, parentAgent = null),
            Client.detect(headersOf("User-Agent", "curl 8.7.1"), null),
        )
    }

    @Test
    fun `a request with nothing to go on is an unknown client`() {
        assertEquals(
            Client("unknown", session = null, agent = null, parentAgent = null),
            Client.detect(Headers.Empty, null),
        )
        assertEquals(
            Client("unknown", session = null, agent = null, parentAgent = null),
            Client.detect(headersOf("user-agent", "   "), null),
            "a blank user-agent names nothing",
        )
    }

    @Test
    fun `with no session header the first user message groups the conversation's turns`() {
        val headers = headersOf("x-stainless-lang", "python")

        val first = checkNotNull(Client.detect(headers, json(ONE_TURN)).session)
        val later = Client.detect(headers, json(TWO_TURNS)).session
        val other = Client.detect(headers, json(OTHER_CONVERSATION)).session

        val cached = Client.detect(headers, json(CACHED_FIRST_TURN)).session

        assertEquals(first, later, "the same conversation, one turn further on")
        assertEquals(
            first,
            cached,
            "the cache breakpoint moves off the first message between turns; the session must not",
        )
        assertNotEquals(first, other, "another first user message is another conversation")
        assertTrue(first.startsWith("sdk-python:"), first)
        assertEquals("sdk-python:".length + 16, first.length, first)
        assertNull(Client.detect(headers, json("""{"messages":[]}""")).session)
        assertNull(Client.detect(headers, null).session)
    }

    private companion object {
        const val ONE_TURN =
            """{"model":"claude-sonnet-4-5-20250929",""" +
                """"messages":[{"role":"user","content":"hello peashoot"}]}"""

        const val TWO_TURNS =
            """{"model":"claude-sonnet-4-5-20250929","messages":[""" +
                """{"role":"user","content":"hello peashoot"},""" +
                """{"role":"assistant","content":"hi"},""" +
                """{"role":"user","content":"and again"}]}"""

        /** The same first message as [ONE_TURN], carrying the turn's cache breakpoint. */
        const val CACHED_FIRST_TURN =
            """{"model":"claude-sonnet-4-5-20250929","messages":[{"role":"user","content":[""" +
                """{"type":"text","text":"hello peashoot",""" +
                """"cache_control":{"type":"ephemeral"}}]}]}"""

        const val OTHER_CONVERSATION =
            """{"model":"claude-sonnet-4-5-20250929",""" +
                """"messages":[{"role":"user","content":"something else entirely"}]}"""
    }
}
