package dev.peashoot.core

import io.ktor.http.Headers
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Token counts of one exchange, as the provider reports them. */
data class Usage(val input: Int, val output: Int, val cacheRead: Int, val cacheWrite: Int)

/** One tool_use block in the response; path from `file_path` or `path`, command from `command`. */
data class ToolCall(val name: String, val path: String?, val command: String?)

/** One tool_result block in the request's last message; name from the tool_use it answers. */
data class ToolResult(val name: String?, val bytes: Int)

/** What the provider's rate-limit headers said, when any were present. */
data class RateLimit(val remainingTokens: Long?, val remainingRequests: Long?, val resetAt: String?)

/** The Anthropic Messages surface: its request shape and its frame grammar. */
object Messages {
    const val SURFACE = "anthropic-messages"

    /** The request's `model`, or null. */
    fun model(json: JsonObject?): String? = json?.get("model").text()

    /** The content of the first message whose role is user, as its JSON text, or null. */
    fun firstUserMessage(json: JsonObject?): String? =
        messages(json)
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["role"].text() == "user" }
            ?.get("content")
            ?.toString()

    /**
     * The tool_result blocks in the LAST message (the results this turn feeds back), named by the
     * tool_use with the same id in the message before it; bytes is the UTF-8 length of the block's
     * `content` as JSON text, so a string content counts its own bytes and an absent content is 0.
     */
    fun toolResults(json: JsonObject?): List<ToolResult> {
        val messages = messages(json)
        val last = messages.lastOrNull() as? JsonObject ?: return emptyList()
        val names = toolNames(messages.getOrNull(messages.size - 2))
        return blocks(last)
            .filter { it["type"].text() == "tool_result" }
            .map { ToolResult(names[it["tool_use_id"].text()], contentBytes(it["content"])) }
    }

    /** Subscription traffic: any `anthropic-beta` value mentions oauth. */
    fun isOAuth(headers: Headers): Boolean =
        headers.getAll(BETA).orEmpty().any { it.contains("oauth", ignoreCase = true) }

    /**
     * Null when the provider sent none of the three headers; a non-numeric value is a null field.
     */
    fun rateLimit(headers: Headers): RateLimit? {
        val tokens = headers[TOKENS_REMAINING]
        val requests = headers[REQUESTS_REMAINING]
        val reset = headers[TOKENS_RESET] ?: headers[REQUESTS_RESET]
        if (tokens == null && requests == null && reset == null) return null
        return RateLimit(tokens?.toLongOrNull(), requests?.toLongOrNull(), reset)
    }

    private const val BETA = "anthropic-beta"
    private const val TOKENS_REMAINING = "anthropic-ratelimit-tokens-remaining"
    private const val REQUESTS_REMAINING = "anthropic-ratelimit-requests-remaining"
    private const val TOKENS_RESET = "anthropic-ratelimit-tokens-reset"
    private const val REQUESTS_RESET = "anthropic-ratelimit-requests-reset"

    /**
     * Reads a response's frames as they arrive and accumulates what the event line reports. Never
     * throws: a frame it cannot parse leaves everything as it was, so a cut or malformed stream
     * still reports whatever arrived before it.
     */
    class Reader {
        var model: String? = null
            private set

        var usage: Usage? = null
            private set

        var stopReason: String? = null
            private set

        /** Closed tool calls, in the order the response opened them. */
        val tools: List<ToolCall>
            get() = closed

        private val closed = mutableListOf<ToolCall>()
        private val open = mutableMapOf<Int, OpenTool>()

        fun read(frame: Frame) {
            val event = frame.event
            val json =
                jsonObjectOrNull(if (event == null) frame.raw else sseData(frame.raw)) ?: return
            when (event) {
                null -> readWholeBody(json)
                "message_start" -> readMessageStart(json)
                "content_block_start" -> openTool(json)
                "content_block_delta" -> appendInput(json)
                "content_block_stop" -> closeTool(json)
                "message_delta" -> readMessageDelta(json)
                else -> Unit // ping, error, and anything a later API version adds
            }
        }

        /** A non-streaming body says everything at once; anything but a message says nothing. */
        private fun readWholeBody(json: JsonObject) {
            if (json["type"].text() != "message") return
            model = json["model"].text() ?: model
            usage = merged(json["usage"])
            stopReason = json["stop_reason"].text() ?: stopReason
            blocks(json)
                .filter { it["type"].text() == "tool_use" }
                .forEach { block ->
                    val name = block["name"].text()
                    if (name != null) closed += toolCall(name, block["input"] as? JsonObject)
                }
        }

        private fun readMessageStart(json: JsonObject) {
            val message = json["message"] as? JsonObject ?: return
            model = message["model"].text() ?: model
            usage = merged(message["usage"])
        }

        private fun readMessageDelta(json: JsonObject) {
            stopReason = (json["delta"] as? JsonObject)?.get("stop_reason").text() ?: stopReason
            usage = merged(json["usage"])
        }

        private fun openTool(json: JsonObject) {
            val block = json["content_block"] as? JsonObject
            val index = json["index"].int()
            if (block == null || index == null || block["type"].text() != "tool_use") return
            val name = block["name"].text() ?: return
            // A block that already carries its whole input streams no deltas after it.
            val input = (block["input"] as? JsonObject)?.takeIf { it.isNotEmpty() }
            open[index] = OpenTool(name, StringBuilder(input?.toString().orEmpty()))
        }

        private fun appendInput(json: JsonObject) {
            val delta = json["delta"] as? JsonObject
            val index = json["index"].int()
            if (delta == null || index == null || delta["type"].text() != "input_json_delta") return
            open[index]?.input?.append(delta["partial_json"].text().orEmpty())
        }

        private fun closeTool(json: JsonObject) {
            val index = json["index"].int() ?: return
            val tool = open.remove(index) ?: return
            closed += toolCall(tool.name, jsonObjectOrNull(tool.input.toString()))
        }

        /** Each of the four keys the usage object carries overrides what we had; the rest stand. */
        private fun merged(element: JsonElement?): Usage? {
            val fields = element as? JsonObject ?: return usage
            val current = usage ?: Usage(0, 0, 0, 0)
            return Usage(
                input = fields["input_tokens"].int() ?: current.input,
                output = fields["output_tokens"].int() ?: current.output,
                cacheRead = fields["cache_read_input_tokens"].int() ?: current.cacheRead,
                cacheWrite = fields["cache_creation_input_tokens"].int() ?: current.cacheWrite,
            )
        }

        private class OpenTool(val name: String, val input: StringBuilder)
    }
}

/** The parse every surface starts from: null when the text is empty, not JSON, or not an object. */
internal fun jsonObjectOrNull(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun toolCall(name: String, input: JsonObject?): ToolCall =
    ToolCall(
        name,
        path = input?.get("file_path").text() ?: input?.get("path").text(),
        command = input?.get("command").text(),
    )

/** The `data:` lines of one SSE block, their prefix and one optional space removed. */
private fun sseData(raw: String): String =
    raw.splitToSequence('\n')
        .filter { it.startsWith("data:") }
        .joinToString("\n") { it.removePrefix("data:").removePrefix(" ").trimEnd('\r') }

private fun messages(json: JsonObject?): List<JsonElement> =
    (json?.get("messages") as? JsonArray) ?: emptyList()

private fun blocks(message: JsonObject?): List<JsonObject> =
    ((message?.get("content") as? JsonArray) ?: emptyList()).mapNotNull { it as? JsonObject }

/** Every tool_use the assistant asked for, by its id, so the results can be named. */
private fun toolNames(message: JsonElement?): Map<String, String> =
    blocks(message as? JsonObject)
        .filter { it["type"].text() == "tool_use" }
        .mapNotNull { block ->
            val id = block["id"].text() ?: return@mapNotNull null
            val name = block["name"].text() ?: return@mapNotNull null
            id to name
        }
        .toMap()

/** A string content is its own bytes; anything else is the bytes of its JSON text. */
private fun contentBytes(content: JsonElement?): Int =
    when (content) {
        null -> 0
        is JsonPrimitive -> content.takeIf { it.isString }?.content?.utf8Length() ?: 0
        else -> content.toString().utf8Length()
    }

private fun String.utf8Length(): Int = toByteArray().size

/** A JSON string, or null for anything else: a number, a bool, JSON null, an object, an array. */
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
