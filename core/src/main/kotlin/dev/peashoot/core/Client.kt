package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Who sent the exchange: detected from headers in the documented order (design section 9). */
data class Client(
    val type: String,
    val session: String?,
    /** Only a sub-agent has either: a main-thread turn leaves both unset. */
    val agent: String? = null,
    val parentAgent: String? = null,
) {
    companion object {
        private const val PEASHOOT_SESSION = "x-peashoot-session"
        private const val CLAUDE_CODE_SESSION = "x-claude-code-session-id"
        private const val CLAUDE_CODE_AGENT = "x-claude-code-agent-id"
        private const val CLAUDE_CODE_PARENT_AGENT = "x-claude-code-parent-agent-id"
        private const val ORIGINATOR = "originator"
        private const val CODEX = "codex_cli_rs"
        private const val SESSION_ID = "session-id"
        private const val THREAD_ID = "thread-id"
        private const val CODEX_PARENT_THREAD = "x-codex-parent-thread-id"
        private const val OPENAI_SUBAGENT = "x-openai-subagent"
        private const val STAINLESS = "x-stainless-"
        private const val STAINLESS_LANG = "x-stainless-lang"
        private const val UNKNOWN = "unknown"

        /** Enough hex to tell conversations apart without a session id's worth of line noise. */
        private const val SESSION_HASH_LENGTH = 16

        /**
         * The order is fixed: an injected session wins outright, then Claude Code, then Codex, then
         * any official SDK, then whatever the user-agent names. A client that sends no session of
         * its own still groups its turns, by the conversation's first user message.
         */
        fun detect(headers: Headers, json: JsonObject?): Client {
            val injected = headers[PEASHOOT_SESSION]
            val detected =
                headers[CLAUDE_CODE_SESSION]?.let { session ->
                    Client(
                        "claude-code",
                        session = session,
                        agent = headers[CLAUDE_CODE_AGENT],
                        parentAgent = headers[CLAUDE_CODE_PARENT_AGENT],
                    )
                }
                    ?: when {
                        headers[ORIGINATOR] == CODEX -> codex(headers)
                        headers.names().any { it.startsWith(STAINLESS, ignoreCase = true) } ->
                            Client(sdkType(headers), session = null)
                        else -> Client(userAgentToken(headers), session = null)
                    }
            val session = injected ?: detected.session ?: fallbackSession(detected.type, json)
            return detected.copy(session = session).bounded()
        }

        /**
         * Codex. One CLI session runs several threads — the main one, and a `review` or `compact`
         * thread spawned off it — so the session groups them and the thread is what tells them
         * apart. `thread-id` is on every request, the main thread's included, so it is not on its
         * own a sub-agent: what makes a thread one is that it names where it came from, in
         * `x-codex-parent-thread-id` or `x-openai-subagent`. A main-thread turn leaves both unset,
         * which is the same promise the Claude Code branch above makes, and is what keeps the farm
         * from drawing every Codex turn as a helper of itself.
         *
         * Header names verified against Codex's own source; see
         * `docs/research/codex-responses-transport.md`.
         */
        private fun codex(headers: Headers): Client {
            val parentThread = headers[CODEX_PARENT_THREAD]
            val spawned = parentThread != null || headers[OPENAI_SUBAGENT] != null
            return Client(
                "codex",
                session = headers[SESSION_ID],
                agent = headers[THREAD_ID].takeIf { spawned },
                parentAgent = parentThread,
            )
        }

        private fun sdkType(headers: Headers): String =
            headers[STAINLESS_LANG]?.lowercase()?.let { "sdk-$it" } ?: "sdk"

        /** The product token: whatever comes before the first `/` or space. */
        private fun userAgentToken(headers: Headers): String =
            headers[HttpHeaders.UserAgent]
                ?.substringBefore('/')
                ?.substringBefore(' ')
                ?.lowercase()
                ?.takeIf { it.isNotBlank() } ?: UNKNOWN

        private fun fallbackSession(type: String, json: JsonObject?): String? =
            Messages.firstUserMessage(json)?.let { "$type:${sha256(it).take(SESSION_HASH_LENGTH)}" }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
    }
}

/**
 * How much of a client-chosen identifier is kept. Generous against every real one — a UUID is 36
 * characters, Codex's thread ids and Claude Code's session ids are shorter — and small enough that
 * a client sending a megabyte under this name cannot put a megabyte on every event line, in every
 * stored row, and in the farm's own labels.
 */
private const val IDENTIFIER_LENGTH = 128

/**
 * A line break, a tab, or any other control character: what would forge a field or a whole line in
 * anything that writes one of these as text rather than as JSON. `\r` and `\n` are control
 * characters themselves, so the one class covers them.
 */
private val CONTROL_CHARACTERS = Regex("\\p{Cntrl}")

/**
 * Every field of a [Client] comes off a header or a user-agent that whoever sent the request chose,
 * and each is then written into `events.jsonl`, into the store, into the Gource log, and onto the
 * farm's labels. Bounded here, where they enter, rather than at each of those: #80 fixed exactly
 * this hole in one writer, and a rule that has to be remembered by the next writer is one that will
 * not be. Sanitising before truncating leaves one well-formed value rather than a forged field, and
 * the writers keep their own guards — this is the first of two, not the replacement for either.
 */
private fun Client.bounded(): Client =
    Client(type.bounded(), session?.bounded(), agent?.bounded(), parentAgent?.bounded())

private fun String.bounded(): String = CONTROL_CHARACTERS.replace(this, "_").take(IDENTIFIER_LENGTH)
