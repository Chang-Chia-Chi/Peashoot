package dev.peashoot.core

import io.ktor.http.Headers
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
        /**
         * The order is fixed: an injected session wins outright, then Claude Code, then Codex, then
         * any official SDK, then whatever the user-agent names. A client that sends no session of
         * its own still groups its turns, by the conversation's first user message.
         */
        fun detect(headers: Headers, json: JsonObject?): Client {
            val injected = headers[PEASHOOT_SESSION]
            val detected =
                when {
                    headers[CLAUDE_CODE_SESSION] != null ->
                        Client(
                            "claude-code",
                            session = headers[CLAUDE_CODE_SESSION],
                            agent = headers[CLAUDE_CODE_AGENT],
                            parentAgent = headers[CLAUDE_CODE_PARENT_AGENT],
                        )
                    // ponytail: thread-id and x-openai-subagent are mapped when the Responses
                    // surface arrives (#25); Codex sends no sub-agent headers on this one.
                    headers[ORIGINATOR] == CODEX -> Client("codex", headers[SESSION_ID])
                    headers.names().any { it.startsWith(STAINLESS, ignoreCase = true) } ->
                        Client(sdkType(headers), session = null)
                    else -> Client(userAgentToken(headers), session = null)
                }
            val session = injected ?: detected.session ?: fallbackSession(detected.type, json)
            return detected.copy(session = session)
        }

        private const val PEASHOOT_SESSION = "x-peashoot-session"
        private const val CLAUDE_CODE_SESSION = "x-claude-code-session-id"
        private const val CLAUDE_CODE_AGENT = "x-claude-code-agent-id"
        private const val CLAUDE_CODE_PARENT_AGENT = "x-claude-code-parent-agent-id"
        private const val ORIGINATOR = "originator"
        private const val CODEX = "codex_cli_rs"
        private const val SESSION_ID = "session-id"
        private const val STAINLESS = "x-stainless-"
        private const val STAINLESS_LANG = "x-stainless-lang"
        private const val UNKNOWN = "unknown"

        /** Enough hex to tell conversations apart without a session id's worth of line noise. */
        private const val SESSION_HASH_LENGTH = 16

        private fun sdkType(headers: Headers): String =
            headers[STAINLESS_LANG]?.lowercase()?.let { "sdk-$it" } ?: "sdk"

        /** The product token: whatever comes before the first `/` or space. */
        private fun userAgentToken(headers: Headers): String =
            headers["user-agent"]?.substringBefore('/')?.substringBefore(' ')?.lowercase()?.takeIf {
                it.isNotBlank()
            } ?: UNKNOWN

        private fun fallbackSession(type: String, json: JsonObject?): String? =
            Messages.firstUserMessage(json)?.let { "$type:${sha256(it).take(SESSION_HASH_LENGTH)}" }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
    }
}
