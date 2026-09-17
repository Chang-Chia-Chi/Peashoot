package dev.peashoot.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The redaction file in the data directory, written from [Redaction.DEFAULT] on first start. */
const val REDACT_FILE = "redact.json"

/**
 * An Anthropic or OpenAI API key: `sk-ant-api03-…`, `sk-proj-…`, `sk-…`. Twenty characters is
 * shorter than any key either issues and longer than any word that happens to follow `sk-`.
 */
private const val API_KEY_PATTERN = "\\bsk-[A-Za-z0-9_-]{20,}"

/** One thing a redaction changed: where it was, the text a rule matched, and what it became. */
data class Redacted(val where: String, val matched: String, val becomes: String)

/**
 * What an exported cassette must not carry, as `rules.json`-shaped replacements. A pointer names
 * strings in the request body, as a matching rule's does. The empty pointer, RFC 6901's whole
 * document, names every string in the body at any depth and the response text too, each frame's raw
 * text or a whole body: a stream is SSE text, not a JSON document a pointer could reach into.
 *
 * Such a rule runs over that raw text, SSE field names and JSON escapes included, so a pattern that
 * matches across them can break a frame on export. Nothing checks for it: the default only ever
 * replaces key characters, and the dry run shows what any other rule would change.
 *
 * ponytail: a key the provider streams split across two deltas is in neither frame whole, so no
 * rule matches it. Upgrade: redact the joined text and map the edits back onto frames.
 */
data class Redaction(val rules: List<Replacement>) {
    /** The body with every rule applied, each change added to [hits]. */
    fun request(json: JsonObject, hits: MutableList<Redacted>): JsonObject =
        rules.fold(json as JsonElement) { acc, rule ->
            val segments = if (rule.pointer.isEmpty()) null else rule.pointer.segments()
            acc.redact(rule, segments, "", hits)
        } as JsonObject

    /** Text no pointer reaches: only whole-document rules apply, each change added to [hits]. */
    fun text(text: String, where: String, hits: MutableList<Redacted>): String =
        rules
            .filter { it.pointer.isEmpty() }
            .fold(text) { acc, rule -> rule.redact(acc, where, hits) }

    companion object {
        val DEFAULT = Redaction(listOf(Replacement("", API_KEY_PATTERN, "[REDACTED]")))

        /** Reads the redaction file; every failure names the rule, as the rule file's do. */
        fun parse(text: String): Redaction {
            val parsed =
                try {
                    Json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    error("$REDACT_FILE: ${e.message}")
                }
            return Redaction(replacements(parsed, REDACT_FILE, "", wholeDocument = true))
        }

        fun defaultJson(): String =
            PRETTY.encodeToString(JsonArray.serializer(), DEFAULT.rules.toJson())
    }
}

/** Replacements in the shape both files keep. */
internal fun List<Replacement>.toJson(): JsonArray = buildJsonArray {
    forEach { rule ->
        add(
            buildJsonObject {
                put("pointer", rule.pointer)
                put("pattern", rule.pattern)
                put("replacement", rule.replacement)
            }
        )
    }
}

/**
 * [rule] applied to the strings [segments] names, or to every string when they are null. [path] is
 * the pointer walked so far, which is where a hit says it was.
 */
private fun JsonElement.redact(
    rule: Replacement,
    segments: List<String>?,
    path: String,
    hits: MutableList<Redacted>,
): JsonElement {
    val rest = segments?.drop(1)
    return when {
        this is JsonPrimitive ->
            if (isString && segments.isNullOrEmpty()) {
                JsonPrimitive(rule.redact(content, "request $path", hits))
            } else this
        segments?.isEmpty() == true -> this
        this is JsonObject ->
            JsonObject(
                mapValues { (key, value) ->
                    if (segments == null || segments.first().selects(key)) {
                        val token = key.replace("~", "~0").replace("/", "~1")
                        value.redact(rule, rest, "$path/$token", hits)
                    } else value
                }
            )
        this is JsonArray ->
            JsonArray(
                mapIndexed { i, value ->
                    if (segments == null || segments.first().selects("$i")) {
                        value.redact(rule, rest, "$path/$i", hits)
                    } else value
                }
            )
        else -> this
    }
}

/**
 * The same replacement `Regex.replace` makes, one match at a time, so each hit can say what its own
 * match became once `$1` and escapes are expanded.
 */
private fun Replacement.redact(text: String, where: String, hits: MutableList<Redacted>): String {
    val matcher = regex.toPattern().matcher(text)
    val out = StringBuilder()
    var end = 0
    while (matcher.find()) {
        // appendReplacement copies the text since the last match, then the replacement.
        val from = out.length + matcher.start() - end
        matcher.appendReplacement(out, replacement)
        hits += Redacted(where, matcher.group(), out.substring(from))
        end = matcher.end()
    }
    matcher.appendTail(out)
    return out.toString()
}
