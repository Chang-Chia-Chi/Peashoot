package dev.peashoot.core

import io.ktor.http.Headers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The OpenAI Chat Completions surface: its request shape and its frame grammar. A streaming
 * response is `data:` chunks, each one JSON, terminated by `data: [DONE]`; a non-streaming one is a
 * single body. The two say the same things under the same keys — a chunk's `choices[].delta` is the
 * body's `choices[].message` a piece at a time — so one reader covers both and there is no second
 * code path to keep in step.
 */
object ChatCompletions : Surface {
    override val name = "openai-chat"

    private const val PATH = "/v1/chat/completions"

    override fun owns(path: String): Boolean = path == PATH

    /**
     * The `tool` messages that end the request: the results this turn feeds back, each named by the
     * `tool_calls` entry carrying its id in the message before them. Bytes is the UTF-8 length of
     * the content as JSON text, so a string content counts its own bytes, as on the other surface.
     */
    override fun toolResults(json: JsonObject?): List<ToolResult> {
        val messages =
            (json?.get("messages") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val results = messages.takeLastWhile { it["role"].text() == TOOL_ROLE }
        val names = toolCallNames(messages.getOrNull(messages.size - results.size - 1))
        return results.map {
            ToolResult(names[it["tool_call_id"].text()], contentBytes(it["content"]))
        }
    }

    override fun rateLimit(headers: Headers): RateLimit? = openAiRateLimit(headers)

    override fun reader(): FrameReader = Reader()

    /**
     * `data: [DONE]` closes a stream, and a finished body — `object` `chat.completion`, where a
     * chunk says `chat.completion.chunk` — is the whole answer in one frame. Both are read through
     * [chunkText], so the same line does for either shape, as the reader's own does.
     */
    override fun terminates(frame: Frame): Boolean {
        val text = chunkText(frame.raw)
        return text.trim() == DONE || jsonObjectOrNull(text)?.get("object").text() == COMPLETION
    }

    private const val DONE = "[DONE]"
    private const val COMPLETION = "chat.completion"

    /** Every tool the assistant asked for, by its id, so the results can be named. */
    private fun toolCallNames(message: JsonObject?): Map<String, String> =
        toolCalls(message)
            .mapNotNull { call ->
                val id = call["id"].text() ?: return@mapNotNull null
                val name = (call["function"] as? JsonObject)?.get("name").text()
                name?.let { id to it }
            }
            .toMap()

    /**
     * One response's chunks. A key a later chunk names overrides what an earlier one said and the
     * rest keep what they had, so the reader is correct at every point in the stream, not only at
     * its end. Usage arrives only when the request set `stream_options.include_usage`, in a final
     * chunk whose `choices` is empty; a response that carries none leaves it null, because absent
     * usage is absent and reporting it as zero would say the turn was free.
     */
    class Reader : FrameReader {
        override var model: String? = null
            private set

        override var usage: Usage? = null
            private set

        override var stopReason: String? = null
            private set

        /**
         * Fragments so far, by the `index` that groups them: a call's name arrives in its first
         * fragment and its `function.arguments` is the concatenation of every fragment's. Sorted,
         * because the event line lists calls in the order the response opened them while the
         * fragments of two calls may interleave. A call whose name never arrived is not one.
         *
         * ponytail: one `choices` entry. Asking for `n > 1` completions with tools would give each
         * choice its own index 0, and the two would concatenate into one corrupted call. Upgrade:
         * key this by choice index as well, the day anything sends `n > 1`.
         */
        private val open = sortedMapOf<Int, OpenTool>()

        override val tools: List<ToolCall>
            get() =
                open.values.mapNotNull { tool ->
                    tool.name?.let { toolCall(it, jsonObjectOrNull(tool.arguments.toString())) }
                }

        override fun read(frame: Frame) {
            val json = jsonObjectOrNull(chunkText(frame.raw)) ?: return
            model = json["model"].text() ?: model
            usage = usageOf(json["usage"]) ?: usage
            (json["choices"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .forEach(::readChoice)
        }

        private fun readChoice(choice: JsonObject) {
            stopReason = choice["finish_reason"].text() ?: stopReason
            // Each cast on its own: a server that sends `"delta": null` beside a real `message`
            // would otherwise have its whole message thrown away, JSON null not being Kotlin's.
            val delta = choice["delta"] as? JsonObject
            val part = delta ?: choice["message"] as? JsonObject ?: return
            // A finished message holds every call at once, so its array positions name them. A
            // delta holds fragments, and its array is one element long chunk after chunk, so
            // position there names only the first call and must not be used.
            toolCalls(part).forEachIndexed { position, call ->
                readToolCall(call, position.takeIf { delta == null })
            }
        }

        /**
         * A streamed fragment names the `index` it belongs to. A finished message's calls carry
         * none, so their [position] in its array stands in; one body is one frame, so those
         * positions cannot shift under us. A delta that names neither is [streamedKey]'s to place.
         */
        private fun readToolCall(call: JsonObject, position: Int?) {
            val function = call["function"] as? JsonObject ?: return
            val id = call["id"].text()
            val tool =
                open.getOrPut(call["index"].int() ?: position ?: streamedKey(id)) { OpenTool() }
            id?.let { tool.id = it }
            function["name"].text()?.let { tool.name = it }
            tool.arguments.append(function["arguments"].text().orEmpty())
        }

        /**
         * Where a streamed fragment that named no `index` belongs. Servers exist that send each
         * call whole in its own chunk and number none of them — Ollama among them, which is the
         * setup this surface is meant to be testable against — and keyed by position every one of
         * those would be call 0, running two calls' arguments together into one that parses as
         * nothing. The id is what is left to tell them apart: a fragment naming an id the open call
         * does not have starts the next call, and one naming no id at all continues the open one,
         * which is how the halves of a split `arguments` string stay together.
         */
        private fun streamedKey(id: String?): Int {
            val last = open.keys.lastOrNull() ?: return 0
            return if (id == null || id == open.getValue(last).id) last else last + 1
        }

        private class OpenTool(
            var id: String? = null,
            var name: String? = null,
            val arguments: StringBuilder = StringBuilder(),
        )
    }
}

private const val TOOL_ROLE = "tool"

/** The `tool_calls` of a message or of a delta; both hold them under the same key. */
private fun toolCalls(message: JsonObject?): List<JsonObject> =
    (message?.get("tool_calls") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

/**
 * OpenAI's counts in the four the event line keeps. `prompt_tokens` includes the cached prefix,
 * where Anthropic's `input_tokens` excludes it, so the cached part is taken back out rather than
 * priced twice under two headings. There is no cache write here: OpenAI's prompt caching is
 * automatic and charges nothing to fill, so the field is 0 because it is really zero.
 *
 * Null for anything but a usage object, which is every chunk of a stream but its last, and every
 * chunk of one that never asked for usage.
 */
private fun usageOf(element: JsonElement?): Usage? {
    val fields = element as? JsonObject ?: return null
    // Floored, so a server reporting a negative cached count cannot give a negative cache read,
    // and with it a negative cost. Flooring the input alone would leave that one through.
    val cached =
        ((fields["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens").int() ?: 0)
            .coerceAtLeast(0)
    return Usage(
        // Never below zero: a server that reports a cached count and no prompt total would
        // otherwise give a negative input, and with it a negative cost.
        input = ((fields["prompt_tokens"].int() ?: 0) - cached).coerceAtLeast(0),
        output = fields["completion_tokens"].int() ?: 0,
        cacheRead = cached,
        cacheWrite = 0,
    )
}
