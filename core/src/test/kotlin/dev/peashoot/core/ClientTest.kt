package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
    fun `a Codex main thread names its thread and is still nobody's helper`() {
        assertEquals(
            Client("codex", session = "cx-1", agent = null, parentAgent = null),
            Client.detect(
                headersOf(
                    "originator" to listOf("codex_cli_rs"),
                    "session-id" to listOf("cx-1"),
                    "thread-id" to listOf("th-1"),
                ),
                null,
            ),
            "every Codex request carries thread-id, so it cannot on its own mean a sub-agent",
        )
    }

    @Test
    fun `a Codex thread that names where it came from is a helper of it`() {
        assertEquals(
            Client("codex", session = "cx-1", agent = "th-2", parentAgent = "th-1"),
            Client.detect(
                headersOf(
                    "originator" to listOf("codex_cli_rs"),
                    "session-id" to listOf("cx-1"),
                    "thread-id" to listOf("th-2"),
                    "x-codex-parent-thread-id" to listOf("th-1"),
                    "x-openai-subagent" to listOf("review"),
                ),
                null,
            ),
        )
        assertEquals(
            Client("codex", session = "cx-1", agent = "th-2", parentAgent = null),
            Client.detect(
                headersOf(
                    "originator" to listOf("codex_cli_rs"),
                    "session-id" to listOf("cx-1"),
                    "thread-id" to listOf("th-2"),
                    "x-openai-subagent" to listOf("compact"),
                ),
                null,
            ),
            "a compact thread that named no parent is still the session's helper, not the session",
        )
    }

    @Test
    fun `the peashoot session header wins over Codex's own too`() {
        assertEquals(
            Client("codex", session = "injected-1", agent = null, parentAgent = null),
            Client.detect(
                headersOf(
                    "x-peashoot-session" to listOf("injected-1"),
                    "originator" to listOf("codex_cli_rs"),
                    "session-id" to listOf("cx-1"),
                ),
                null,
            ),
        )
    }

    /**
     * Every field here came off a header or a user-agent that whoever sent the request chose, and
     * each is written into `events.jsonl`, into the store, into the Gource log, and onto the farm's
     * labels. A live request's header values are checked by the engine's own decoder first, so a
     * bare line break cannot arrive that way — but `detect` also runs over the headers of an
     * exchange rebuilt from the store, and a cassette is a file that may have been hand-edited or
     * imported from somewhere else. Bounded where they enter, so the next writer of a session
     * inherits a value that is already safe rather than a rule it has to remember (#80).
     */
    @Test
    fun `client-chosen identifiers are sanitised and capped where they enter`() {
        val detected =
            Client.detect(
                headersOf(
                    "x-peashoot-session" to listOf("ses\n1790|forged|A|/etc/passwd"),
                    "x-claude-code-agent-id" to listOf("agent\u0000one"),
                    "x-claude-code-parent-agent-id" to listOf("parent\tone"),
                    "x-claude-code-session-id" to listOf("ignored"),
                ),
                null,
            )

        assertEquals("ses_1790|forged|A|/etc/passwd", detected.session, "no line break survives")
        assertEquals("agent_one", detected.agent)
        assertEquals("parent_one", detected.parentAgent, "a tab is a control character too")

        val long = "x".repeat(IDENTIFIER_CAP * 2)
        val capped = Client.detect(headersOf("x-peashoot-session", long), null)
        assertEquals(IDENTIFIER_CAP, assertNotNull(capped.session).length)
        assertEquals(
            IDENTIFIER_CAP,
            Client.detect(headersOf("user-agent", long), null).type.length,
            "the client type comes off a user-agent and is bounded with the rest",
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
        assertEquals(
            Client("sdk-js", session = null, agent = null, parentAgent = null),
            Client.detect(
                headersOf(
                    "x-stainless-lang" to listOf("js"),
                    "x-stainless-package-version" to listOf("4.68.0"),
                    "user-agent" to listOf("OpenAI/JS 4.68.0"),
                ),
                null,
            ),
            "the OpenAI SDKs are Stainless-generated, so the language header names them too",
        )
        assertEquals(
            Client("openai", session = null, agent = null, parentAgent = null),
            Client.detect(headersOf("user-agent", "OpenAI/Python 1.109.1"), null),
            "and a client that sends only the product token falls back to it",
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

        val first = assertNotNull(Client.detect(headers, json(ONE_TURN)).session)
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
        /** What `Client` caps a client-chosen identifier at; a UUID is 36 characters. */
        const val IDENTIFIER_CAP = 128

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
