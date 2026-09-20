package dev.peashoot.core

import io.ktor.http.Headers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The OpenAI Responses surface: its request shape and its frame grammar. Codex speaks this one and
 * nothing else. A streaming response is SSE blocks carrying an `event:` line and a `data:` object
 * with a `type` and a `sequence_number`; a non-streaming one is the response object itself, which
 * is also what `GET /v1/responses/{id}` and a cancel answer with. The streamed events and the
 * finished object say the same things under the same keys — an `output_item` is an entry of the
 * completed response's `output`, and the response object an event carries is the same object — so
 * one reader covers every shape and there is no second code path to keep in step.
 *
 * Recorded response ids are served verbatim on replay, and `previous_response_id` is a body field
 * like any other, so it stays in the fingerprint untouched and a chained pair of calls replays with
 * no id mapping anywhere: call 2's fingerprint names the id call 1's replay just handed back.
 */
object Responses : Surface {
    override val name = "openai-responses"

    private const val PATH = "/v1/responses"

    /**
     * `POST /v1/responses`, and everything under it: `GET /v1/responses/{id}` with or without
     * `?stream=true&starting_after=N`, `POST /v1/responses/{id}/cancel`, and whatever else the API
     * hangs there. The separator is required, so a future `/v1/responses_beta` is not silently
     * ours.
     */
    override fun owns(path: String): Boolean = path == PATH || path.startsWith("$PATH/")

    /**
     * The `function_call_output` items that end the request: the results this turn feeds back, each
     * named by the `function_call` carrying its `call_id`. `input` holds the whole conversation,
     * the calls that asked for them included, so the names come from the same array rather than
     * from the message before as on Chat Completions. Bytes is the UTF-8 length of the output as
     * JSON text, so a string output counts its own bytes, as on the other surfaces.
     */
    override fun toolResults(json: JsonObject?): List<ToolResult> {
        val input = (json?.get("input") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val names =
            input
                .filter { it["type"].text() == FUNCTION_CALL }
                .mapNotNull { call ->
                    val id = call["call_id"].text() ?: return@mapNotNull null
                    call["name"].text()?.let { id to it }
                }
                .toMap()
        return input
            .takeLastWhile { it["type"].text() == FUNCTION_CALL_OUTPUT }
            .map { ToolResult(names[it["call_id"].text()], contentBytes(it["output"])) }
    }

    override fun rateLimit(headers: Headers): RateLimit? = openAiRateLimit(headers)

    override fun reader(): FrameReader = Reader()

    /**
     * One response's frames. A key a later frame names overrides what an earlier one said and the
     * rest keep what they had, so the reader is correct at every point in the stream and not only
     * at its end. Usage arrives on the terminal event alone and a response that carries none leaves
     * it null, because absent usage is absent and reporting it as zero would say the turn was free.
     */
    class Reader : FrameReader {
        override var model: String? = null
            private set

        override var usage: Usage? = null
            private set

        /**
         * The response's own `status`, as the last frame that carried one reported it: `completed`,
         * `incomplete`, `failed`, `cancelled`. This surface has no separate stop reason, and the
         * status is what the other two put on that field. A stream cut before its terminal event
         * therefore says `in_progress`, which is exactly what happened to it.
         */
        override var stopReason: String? = null
            private set

        /**
         * Calls so far, by the item id that groups them, in the order the response opened them —
         * two calls' argument fragments interleave, so insertion order and not arrival order is
         * what the event line wants. A call whose name never arrived is not one.
         */
        private val calls = LinkedHashMap<String, OpenTool>()

        override val tools: List<ToolCall>
            get() =
                calls.values.mapNotNull { tool ->
                    tool.name?.let { toolCall(it, jsonObjectOrNull(tool.arguments.toString())) }
                }

        override fun read(frame: Frame) {
            val json = jsonObjectOrNull(chunkText(frame.raw)) ?: return
            when (json["type"].text()) {
                // A response object carries no `type` of its own, so a frame without one is the
                // whole body: a non-streaming create, a get-by-id, or a cancel's answer.
                null -> readResponse(json)
                ITEM_ADDED,
                ITEM_DONE -> readItem(json["item"] as? JsonObject, itemKey(json))
                ARGUMENTS_DELTA ->
                    opened(itemKey(json)).arguments.append(json["delta"].text().orEmpty())
                ARGUMENTS_DONE -> setArguments(opened(itemKey(json)), json["arguments"].text())
                // `response.created`, `.in_progress`, `.completed`, `.incomplete`, `.failed` all
                // carry the response; every other event carries none and says nothing here.
                else -> readResponse(json["response"] as? JsonObject)
            }
        }

        /**
         * The response object an event carried, or a whole body. Its `output` is read too: the
         * terminal event repeats every item whole, and keyed by the same item id the streamed
         * events used those repeats land on the calls already open instead of doubling them.
         */
        private fun readResponse(response: JsonObject?) {
            if (response == null) return
            model = response["model"].text() ?: model
            usage = usageOf(response["usage"]) ?: usage
            stopReason = response["status"].text() ?: stopReason
            (response["output"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .forEachIndexed { index, item -> readItem(item, key(item["id"].text(), index)) }
        }

        /**
         * Only a `function_call` is a call: a message, a reasoning summary, and the rest are not.
         */
        private fun readItem(item: JsonObject?, key: String) {
            if (item == null || item["type"].text() != FUNCTION_CALL) return
            val tool = opened(key)
            item["name"].text()?.let { tool.name = it }
            setArguments(tool, item["arguments"].text())
        }

        /**
         * A finished item and the `.done` event both carry the whole arguments string, so it
         * replaces the fragments rather than doubling them. An opening item's empty string replaces
         * nothing, which is what lets the deltas that follow it accumulate.
         */
        private fun setArguments(tool: OpenTool, arguments: String?) {
            if (arguments.isNullOrEmpty()) return
            tool.arguments.setLength(0)
            tool.arguments.append(arguments)
        }

        private fun opened(key: String): OpenTool = calls.getOrPut(key) { OpenTool() }

        /**
         * Which call a frame is about: the item's own id wherever one is named — the argument
         * events call it `item_id` and the item itself `id` — and the `output_index` otherwise,
         * which numbers the same items the completed response's `output` array does.
         */
        private fun itemKey(json: JsonObject): String =
            key(
                json["item_id"].text() ?: (json["item"] as? JsonObject)?.get("id").text(),
                json["output_index"].int(),
            )

        /** One key for a frame that named neither, so its fragments at least stay together. */
        private fun key(id: String?, index: Int?): String = id ?: index?.toString() ?: ""

        private class OpenTool(
            var name: String? = null,
            val arguments: StringBuilder = StringBuilder(),
        )
    }
}

private const val FUNCTION_CALL = "function_call"
private const val FUNCTION_CALL_OUTPUT = "function_call_output"
private const val ITEM_ADDED = "response.output_item.added"
private const val ITEM_DONE = "response.output_item.done"
private const val ARGUMENTS_DELTA = "response.function_call_arguments.delta"
private const val ARGUMENTS_DONE = "response.function_call_arguments.done"

/**
 * The Responses counts in the four the event line keeps. `input_tokens` includes the cached prefix
 * where Anthropic's excludes it, so the cached part is taken back out rather than priced twice
 * under two headings — the same correction Chat Completions makes to `prompt_tokens`. Reasoning
 * tokens need no correction of their own: `output_tokens_details.reasoning_tokens` is already part
 * of `output_tokens`, and subtracting it would say the thinking was free. There is no cache write:
 * OpenAI's prompt caching is automatic and charges nothing to fill, so that field is 0 because it
 * is really zero.
 *
 * Null for anything but a usage object, which is every event of a stream before the terminal one
 * and every event of a stream that was cut before it.
 */
private fun usageOf(element: JsonElement?): Usage? {
    val fields = element as? JsonObject ?: return null
    val cached = (fields["input_tokens_details"] as? JsonObject)?.get("cached_tokens").int() ?: 0
    return Usage(
        // Never below zero: a server that reports a cached count and no input total would
        // otherwise give a negative input, and with it a negative cost.
        input = ((fields["input_tokens"].int() ?: 0) - cached).coerceAtLeast(0),
        output = fields["output_tokens"].int() ?: 0,
        cacheRead = cached,
        cacheWrite = 0,
    )
}
