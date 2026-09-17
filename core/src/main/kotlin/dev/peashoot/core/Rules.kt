package dev.peashoot.core

import io.ktor.http.Headers
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The rule file in the data directory, written from [Rules.DEFAULT] on first start. */
const val RULES_FILE = "rules.json"

/**
 * The billing line Claude Code sends as its first system block, naming the client version:
 * `x-anthropic-billing-header: cc_version=2.1.274.101; cc_entrypoint=sdk-cli;`. It is in the body,
 * where no header allowlist can reach it, and it changes with every client release.
 */
private const val BILLING_PATTERN = "(?m)^x-anthropic-billing-header:.*$"

/**
 * Claude Code's environment block, from its heading to the blank line that ends it: the working
 * directory, whether that directory is a git repo, the platform, the shell, and the OS version.
 */
private const val ENVIRONMENT_PATTERN =
    "(?s)# Environment\\nYou have been invoked in the following environment:.*?" + "(?:\\n\\n|\\z)"

/**
 * One regex replacement at a pointer, applied to string values only. The pattern stays the rule's
 * own text so a hand edit reads back as it was written; compiling it here is also what turns a bad
 * one into a startup failure rather than a surprise at the first request that meets it.
 */
data class Replacement(val pointer: String, val pattern: String, val replacement: String) {
    val regex: Regex = Regex(pattern)
}

/**
 * What two requests must share to be the same request. Rules are data, not code: the file is meant
 * to be read and edited, and a rule's only observable effect is whether a replay hits.
 *
 * The empty rule set is exact mode. It keeps no header and changes no field, so two requests match
 * only when they are literally the same request.
 */
data class Rules(
    /**
     * Header allowlist, lower-case. Everything absent from it, attribution included, is ignored.
     */
    val keepHeaders: Set<String> = emptySet(),
    /**
     * RFC 6901 pointers, with `*` matching any array index or object key, removed from the body.
     * Each applies to what the one before it left, so two pointers into the same array read against
     * the shortened one: `/m/0` then `/m/1` over `[a, b, c]` leaves `[b]`, not `[c]`.
     */
    val ignorePointers: List<String> = emptyList(),
    val replace: List<Replacement> = emptyList(),
) {
    /** The body with every ignored pointer removed and then every replacement applied. */
    fun normalize(json: JsonObject?): JsonObject? {
        if (json == null) return null
        val pruned = ignorePointers.fold(json as JsonElement) { acc, p -> acc.remove(p.segments()) }
        val replaced =
            replace.fold(pruned) { acc, rule ->
                acc.mapStrings(rule.pointer.segments()) { rule.regex.replace(it, rule.replacement) }
            }
        return replaced as? JsonObject
    }

    /** The kept headers, lower-cased, so a header's own spelling never reaches the hash. */
    fun keptHeaders(headers: Headers): JsonObject = buildJsonObject {
        headers
            .entries()
            .filter { it.key.lowercase() in keepHeaders }
            .forEach { entry ->
                putJsonArray(entry.key.lowercase()) {
                    entry.value.forEach { add(JsonPrimitive(it)) }
                }
            }
    }

    /**
     * What identifies a request: SHA-256 over canonical JSON of the method, the path, the kept
     * headers, and the normalized body. A body that is not JSON has no normalized form, so its
     * bytes stand in for it under their own key, and two such requests match only byte for byte.
     */
    fun fingerprint(
        method: String,
        path: String,
        headers: Headers,
        json: JsonObject?,
        body: ByteArray = ByteArray(0),
    ): String {
        val canonical = buildJsonObject {
            put("method", method)
            put("path", path)
            put("headers", keptHeaders(headers))
            val normalized = normalize(json)
            if (normalized != null) put("body", normalized)
            // Hex, not text: decoding maps every malformed byte to the same character, and two
            // bodies differing only there are not the same request.
            else put("rawBody", body.toHexString())
        }
            .canonical()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).toHexString()
    }

    companion object {
        /**
         * What ships. The header allowlist is what a provider actually reads; everything else, the
         * attribution headers among them, varies per machine and per run.
         *
         * All three were checked against a request captured from Claude Code 2.1.274 rather than
         * assumed. The environment block travels in a message, not in `system` where an earlier
         * draft of this design put it; the `system` pointer is kept for it anyway, because it costs
         * nothing where the block is absent and a client that moves it back is still covered.
         */
        val DEFAULT =
            Rules(
                keepHeaders =
                    setOf("content-type", "anthropic-version", "anthropic-beta", "openai-beta"),
                ignorePointers = listOf("/metadata", "/stream"),
                replace =
                    listOf(
                        Replacement("/system/*/text", BILLING_PATTERN, ""),
                        Replacement("/system/*/text", ENVIRONMENT_PATTERN, ""),
                        Replacement("/messages/*/content/*/text", ENVIRONMENT_PATTERN, ""),
                    ),
            )

        /** Exact mode: no header kept, no field ignored, nothing replaced. */
        val EXACT = Rules()

        /**
         * Reads the rule file. Every failure names the rule that caused it, because the file is
         * hand-edited and the next thing its author needs is which entry to fix.
         */
        fun parse(text: String): Rules {
            val parsed =
                try {
                    Json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    // The parser locates the edit; our own message could only say "not JSON".
                    error("$RULES_FILE: ${e.message}")
                }
            val root =
                parsed as? JsonObject ?: error("$RULES_FILE: not a JSON object: ${text.take(80)}")
            // A key nobody reads is the worst kind of wrong file: `replacements` for `replace`
            // loads clean, changes nothing, and every replay misses with nothing in any log.
            val unknown = root.keys - setOf("keepHeaders", "ignorePointers", "replace")
            check(unknown.isEmpty()) {
                "$RULES_FILE: no such rule: ${unknown.sorted().joinToString()}"
            }
            return Rules(
                keepHeaders =
                    root
                        .stringList("keepHeaders")
                        .mapIndexed { i, name ->
                            check(name.isNotBlank()) { "$RULES_FILE: keepHeaders[$i] is blank" }
                            name.lowercase()
                        }
                        .toSet(),
                ignorePointers =
                    root.stringList("ignorePointers").onEachIndexed { i, pointer ->
                        checkPointer(pointer, RULES_FILE, "ignorePointers[$i]")
                    },
                replace =
                    root["replace"]?.let { replacements(it, RULES_FILE, "replace") }.orEmpty(),
            )
        }

        /**
         * The file written on first start: the defaults, in the shape a hand edit keeps. Indented,
         * because the whole point of the patterns being data is that someone can read them.
         */
        fun defaultJson(): String = PRETTY.encodeToString(JsonObject.serializer(), defaultObject())

        private fun defaultObject(): JsonObject = buildJsonObject {
            putJsonArray("keepHeaders") {
                DEFAULT.keepHeaders.sorted().forEach { add(JsonPrimitive(it)) }
            }
            putJsonArray("ignorePointers") {
                DEFAULT.ignorePointers.forEach { add(JsonPrimitive(it)) }
            }
            put("replace", DEFAULT.replace.toJson())
        }
    }
}

/** The rule file is read by people, so it is written for them. */
internal val PRETTY = Json { prettyPrint = true }

/**
 * A replacement is not plain text: `Regex.replace` reads `$` as a group reference and a backslash
 * as an escape, so a hand-written one can throw at the first request it ever matches, past every
 * startup check. Read here instead, while the file is still in hand.
 */
private fun Replacement.checkReplacement(file: String, rule: String) {
    val groups = regex.toPattern().matcher("").groupCount()
    var i = 0
    while (i < replacement.length) {
        val char = replacement[i]
        if (char == '\\') {
            check(i + 1 < replacement.length) {
                "$file: $rule.replacement ends in a backslash, which escapes nothing"
            }
            i += 2
            continue
        }
        if (char == '$') {
            val digits = replacement.substring(i + 1).takeWhile(Char::isDigit)
            check(digits.isNotEmpty()) {
                "$file: $rule.replacement has a $$ with no group number after it"
            }
            check(digits.toInt() <= groups) {
                "$file: $rule.replacement uses group $digits, but the pattern has $groups"
            }
            i += digits.length
        }
        i++
    }
}

private fun JsonObject.stringList(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    val array = value as? JsonArray ?: error("$RULES_FILE: $key must be a list, not $value")
    return array.mapIndexed { i, element ->
        val primitive = element as? JsonPrimitive
        check(primitive != null && primitive.isString) {
            "$RULES_FILE: $key[$i] must be a string, not $element"
        }
        primitive.content
    }
}

/**
 * The replacements [value] lists, as [file] holds them under [key], which every failure names. The
 * redaction file is one bare list, so its [key] is empty and its rules may point at the whole
 * document; a matching rule may not, since the body is always an object.
 */
internal fun replacements(
    value: JsonElement,
    file: String,
    key: String,
    wholeDocument: Boolean = false,
): List<Replacement> {
    val array =
        value as? JsonArray
            ?: error("$file: ${key.ifEmpty { "the file" }} must be a list, not $value")
    return array.mapIndexed { i, element ->
        val at = "$key[$i]"
        val rule = element as? JsonObject ?: error("$file: $at must be an object")
        val pointer = rule.requiredString(file, at, "pointer")
        if (!(wholeDocument && pointer.isEmpty())) checkPointer(pointer, file, "$at.pointer")
        val pattern = rule.requiredString(file, at, "pattern")
        val replacement = rule.requiredString(file, at, "replacement")
        val compiled =
            try {
                Replacement(pointer, pattern, replacement)
            } catch (e: IllegalArgumentException) {
                error("$file: $at.pattern is not a regular expression: ${e.message}")
            }
        compiled.checkReplacement(file, at)
        compiled
    }
}

private fun JsonObject.requiredString(file: String, rule: String, key: String): String {
    val primitive = this[key] as? JsonPrimitive
    check(primitive != null && primitive.isString) {
        "$file: $rule.$key is required and must be a string"
    }
    return primitive.content
}

private fun checkPointer(pointer: String, file: String, rule: String) =
    check(pointer.startsWith("/")) {
        "$file: $rule must be a JSON pointer starting with /, not '$pointer'"
    }

/**
 * An RFC 6901 pointer split into its reference tokens, `~1` decoded to `/` and `~0` to `~`. The
 * leading slash is the document itself, which no rule may name, so the list is never empty.
 */
internal fun String.segments(): List<String> =
    removePrefix("/").split("/").map { it.replace("~1", "/").replace("~0", "~") }

/** Whether a segment selects this key or index: `*` takes any of them. */
internal fun String.selects(key: String) = this == "*" || this == key

/**
 * The element with whatever [segments] names removed; a pointer that matches nothing changes it.
 */
private fun JsonElement.remove(segments: List<String>): JsonElement =
    when {
        segments.isEmpty() -> this
        this is JsonObject ->
            buildJsonObject {
                forEach { (key, value) ->
                    when {
                        !segments.first().selects(key) -> put(key, value)
                        segments.size == 1 -> Unit // the leaf: dropping it is the point
                        else -> put(key, value.remove(segments.drop(1)))
                    }
                }
            }
        this is JsonArray ->
            buildJsonArray {
                forEachIndexed { index, value ->
                    when {
                        !segments.first().selects(index.toString()) -> add(value)
                        // Dropped rather than left as a hole: a pointer into an array names a
                        // value, and a null in its place would still be a value in the hash.
                        segments.size == 1 -> Unit
                        else -> add(value.remove(segments.drop(1)))
                    }
                }
            }
        else -> this
    }

/** Every string [segments] names, put through [transform]; anything else is left as it is. */
private fun JsonElement.mapStrings(
    segments: List<String>,
    transform: (String) -> String,
): JsonElement =
    when {
        segments.isEmpty() -> {
            val primitive = this as? JsonPrimitive
            if (primitive != null && primitive.isString) JsonPrimitive(transform(primitive.content))
            else this
        }
        this is JsonObject ->
            buildJsonObject {
                forEach { (key, value) ->
                    if (segments.first().selects(key)) {
                        put(key, value.mapStrings(segments.drop(1), transform))
                    } else put(key, value)
                }
            }
        this is JsonArray ->
            buildJsonArray {
                forEachIndexed { index, value ->
                    if (segments.first().selects(index.toString())) {
                        add(value.mapStrings(segments.drop(1), transform))
                    } else add(value)
                }
            }
        else -> this
    }

/**
 * Canonical JSON: object keys sorted, no insignificant whitespace, every number exactly as it was
 * written. Sorting is what makes two requests differing only in key order the same request, and
 * leaving numbers alone is what keeps `1.0` from quietly becoming `1`.
 */
internal fun JsonElement.canonical(): String =
    when (this) {
        is JsonObject ->
            entries
                .sortedBy { it.key }
                .joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${it.value.canonical()}" }
        is JsonArray -> joinToString(",", "[", "]") { it.canonical() }
        JsonNull -> "null"
        is JsonPrimitive -> toString()
    }
