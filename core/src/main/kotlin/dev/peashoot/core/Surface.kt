package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject

/**
 * One provider API as the proxy sees it: which requests are its own, what their shape says, the
 * grammar of the frames that answer them, and what of that reaches the event line (design section
 * 9). Everything else in the pipeline — routing, fingerprinting, the store, replay, the client
 * writer — is the same whichever surface a request belongs to, which is why the difference is this
 * one adapter and not a `when` spread through the interceptors.
 *
 * Sealed, because the one `when` that does have to name them — which provider a surface is relayed
 * to — must not have an `else`: a fourth surface defaulting to whichever provider was written last
 * would be relayed to the wrong one, with its key, by a branch nobody remembered to add.
 */
sealed interface Surface {
    /** What the event line calls this surface. */
    val name: String

    /**
     * Whether this surface alone owns [path]. `GET /v1/models` is passed through by both surfaces
     * and owned by neither, so [surfaceOf] settles that one from the headers instead.
     */
    fun owns(path: String): Boolean

    /** The request's `model`, or null. Both providers spell it the same, so both read it here. */
    fun model(json: JsonObject?): String? = json?.get("model").text()

    /** The tool results the request feeds back, for the event line's `toolResults`. */
    fun toolResults(json: JsonObject?): List<ToolResult>

    /**
     * Subscription traffic, billed by the plan rather than by the token, so its cost is unknown
     * rather than computed. Only Anthropic sells one, so the answer is no unless a surface says.
     */
    fun isOAuth(headers: Headers): Boolean = false

    /** What the provider's rate-limit headers said, when it sent any. */
    fun rateLimit(headers: Headers): RateLimit?

    /** A reader for one response: single-use, fed every frame of it in arrival order. */
    fun reader(): FrameReader
}

/**
 * Reads a response's frames as they arrive and accumulates what the event line reports. Never
 * throws: a frame it cannot parse leaves everything as it was, so a cut or malformed stream still
 * reports whatever arrived before it.
 */
interface FrameReader {
    val model: String?

    val usage: Usage?

    /** `stop_reason` on Messages and `finish_reason` on Chat Completions: one field on the line. */
    val stopReason: String?

    /** The tool calls the response asked for, in the order it opened them. */
    val tools: List<ToolCall>

    fun read(frame: Frame)
}

/** Every surface the proxy speaks, in the order [surfaceOf] asks them for a path. */
private val SURFACES: List<Surface> = listOf(Messages, ChatCompletions, Responses)

/**
 * The surface a request belongs to (design section 4, step 2). The path decides wherever one
 * surface owns it, which is every request that carries a body; only the model listing both surfaces
 * pass through is owned by neither, and there the sender's headers decide, because they are all
 * such a request has.
 *
 * `x-stainless-*` is not asked at all, though the OpenAI SDKs send it, precisely because the
 * Anthropic ones send it identically: it could only speak where the two are already hard to tell
 * apart, and there it would send an Anthropic SDK that omitted its version header to OpenAI, which
 * answers 401. The OpenAI SDKs are named by their own `openai-*` headers and their `OpenAI/…`
 * user-agent instead, both of which only they send. A request with nothing to go on takes Messages,
 * the surface that was here first, so no path Claude Code already sends moves.
 *
 * Secret headers are stripped before an exchange exists, so `authorization` and `x-api-key` are not
 * part of this decision and cannot be: a request is routed by what it is willing to say.
 */
fun surfaceOf(path: String, headers: Headers): Surface =
    SURFACES.firstOrNull { it.owns(path.substringBefore('?')) } ?: headers.sender()

/**
 * The three values a provider's rate-limit headers carried, whatever that provider calls them: null
 * when it sent none of the three, and a null field for one whose value was not a number.
 */
internal fun rateLimitOf(tokens: String?, requests: String?, reset: String?): RateLimit? {
    if (tokens == null && requests == null && reset == null) return null
    return RateLimit(tokens?.toLongOrNull(), requests?.toLongOrNull(), reset)
}

/**
 * The JSON one frame carries: an SSE block's `data:` lines, or a non-streaming body as it stands.
 * `data: [DONE]`, a keep-alive comment, and a blank frame all fail to parse and are ignored, which
 * is what leaves a reader as it was. Shared, because both OpenAI surfaces send both shapes and a
 * copy each would be one provider quirk away from disagreeing about what a frame says.
 */
internal fun chunkText(raw: String): String =
    if (raw.lineSequence().any { it.startsWith("data:") }) sseData(raw) else raw

/**
 * What OpenAI's rate-limit headers said, for either of its surfaces: they are the same four headers
 * on both, so they are read in one place. Either reset dates the window; the token one first, as it
 * is the one that bites.
 */
internal fun openAiRateLimit(headers: Headers): RateLimit? =
    rateLimitOf(
        headers["x-ratelimit-remaining-tokens"],
        headers["x-ratelimit-remaining-requests"],
        headers["x-ratelimit-reset-tokens"] ?: headers["x-ratelimit-reset-requests"],
    )

private val ANTHROPIC_HEADERS = setOf("anthropic-version", "anthropic-beta")

/** `openai-organization`, `openai-project`, `openai-beta`: headers only OpenAI clients send. */
private const val OPENAI_PREFIX = "openai-"

/** The official SDKs' product token: `OpenAI/Python 1.109.1`, `OpenAI/JS 4.68.0`. */
private const val OPENAI_AGENT = "OpenAI"

private fun Headers.sender(): Surface =
    when {
        names().any { it.lowercase() in ANTHROPIC_HEADERS } -> Messages
        names().any { it.startsWith(OPENAI_PREFIX, ignoreCase = true) } -> ChatCompletions
        this[HttpHeaders.UserAgent]?.startsWith(OPENAI_AGENT, ignoreCase = true) == true ->
            ChatCompletions
        else -> Messages
    }
