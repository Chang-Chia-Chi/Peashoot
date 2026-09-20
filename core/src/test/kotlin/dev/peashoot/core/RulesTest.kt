package dev.peashoot.core

import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What the rule set makes the same and what it keeps apart. The requests below are the shape Claude
 * Code actually sends, checked against a capture from 2.1.274: a billing block naming the client
 * version, an environment block carried in a message with the machine's own paths in it, and
 * attribution headers. The values are synthetic; only the shape is from the capture.
 */
class RulesTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /**
     * One machine's request. [wd], [platform] and [os] are what the environment block carries and
     * [version] what the billing block names; [prompt] is the only thing a user chose.
     */
    private fun request(
        prompt: String = "count to three",
        wd: String = "C:/dev/Peashoot",
        platform: String = "win32",
        os: String = "Windows 11 Home 10.0.26200",
        version: String = "2.1.274.101",
    ): JsonObject {
        val environment =
            "Some preamble.\\n\\n# Environment\\nYou have been invoked in the following " +
                "environment: \\n - Primary working directory: $wd\\n - Is a git repository: " +
                "true\\n - Platform: $platform\\n - OS Version: $os\\n\\nYou are powered by a model."
        return json(
            """
            {
              "model": "claude-opus-4-5",
              "max_tokens": 4096,
              "stream": true,
              "metadata": {"user_id": "u-$version"},
              "system": [
                {"type": "text",
                 "text": "x-anthropic-billing-header: cc_version=$version; cc_entrypoint=sdk-cli;"},
                {"type": "text", "text": "You are a Claude agent."}
              ],
              "messages": [
                {"role": "user", "content": [{"type": "text", "text": "$prompt"}]},
                {"role": "system", "content": [{"type": "text", "text": "$environment"}]}
              ]
            }
            """
                .trimIndent()
        )
    }

    /** The attribution headers a client adds to every call, none of them on the allowlist. */
    private fun headers(session: String, agent: String): Headers =
        headersOf(
            "content-type" to listOf("application/json"),
            "anthropic-version" to listOf("2023-06-01"),
            "x-claude-session-id" to listOf(session),
            "user-agent" to listOf("claude-cli/$agent"),
        )

    private fun Rules.of(request: JsonObject, headers: Headers) =
        fingerprint("POST", "/v1/messages", headers, request)

    /**
     * The canonical form itself, pinned.
     *
     * Every other test here is relational — this changes the hash, that does not — and every one of
     * them would still pass if the canonical JSON were renamed, reordered, or digested differently,
     * because both sides of each comparison would move together. The fingerprint is not an internal
     * detail: it is a column of every stored exchange and a field of every cassette line anyone has
     * committed, so a change to this value silently stops every recording in the world from
     * replaying. **Changing the constant below to make this test pass is a breaking change.** If it
     * has to be made, it is a schema migration and a cassette version bump, not an edit here.
     */
    @Test
    fun `the canonical form of a request hashes to a value that must never move`() {
        assertEquals(
            "05ee561fe85644853b20c49450ccce435c004cf655b72d35d5c5639f74bf59cd",
            Rules.DEFAULT.fingerprint(
                "POST",
                "/v1/messages",
                headersOf("content-type", "application/json"),
                Json.parseToJsonElement(GOLDEN) as JsonObject,
            ),
        )
    }

    @Test
    fun `attribution headers and the machine-context block do not change the fingerprint`() {
        val laptop =
            Rules.DEFAULT.of(
                request(wd = "C:/dev/Peashoot", platform = "win32", version = "2.1.274.101"),
                headers(session = "sess-laptop", agent = "2.1.274"),
            )
        val ci =
            Rules.DEFAULT.of(
                request(
                    wd = "/home/runner/work",
                    platform = "linux",
                    os = "Ubuntu 24.04",
                    version = "2.2.0.9",
                ),
                headers(session = "sess-ci", agent = "2.2.0"),
            )
        assertEquals(laptop, ci, "same turn, different machine and client version")
    }

    @Test
    fun `a one-word prompt change changes the fingerprint`() {
        val three = Rules.DEFAULT.of(request(prompt = "count to three"), headers("s", "a"))
        val four = Rules.DEFAULT.of(request(prompt = "count to four"), headers("s", "a"))
        assertNotEquals(three, four, "the prompt is the one thing that must matter")
    }

    @Test
    fun `exact mode tells the same two machines apart`() {
        val laptop =
            Rules.EXACT.of(
                request(wd = "C:/dev/Peashoot", platform = "win32", version = "2.1.274.101"),
                headers(session = "sess-laptop", agent = "2.1.274"),
            )
        val ci =
            Rules.EXACT.of(
                request(
                    wd = "/home/runner/work",
                    platform = "linux",
                    os = "Ubuntu 24.04",
                    version = "2.2.0.9",
                ),
                headers(session = "sess-ci", agent = "2.2.0"),
            )
        assertNotEquals(laptop, ci, "the empty rule set normalizes nothing")
    }

    @Test
    fun `the ignored pointers keep the stream flag and the metadata out`() {
        val streaming = Rules.DEFAULT.of(request(), headers("s", "a"))
        val edited =
            request().toMutableMap().apply {
                put("stream", Json.parseToJsonElement("false"))
                put("metadata", Json.parseToJsonElement("""{"user_id":"someone-else"}"""))
            }
        val blocking = Rules.DEFAULT.of(JsonObject(edited), headers("s", "a"))
        assertEquals(streaming, blocking, "neither is part of what was asked")
    }

    @Test
    fun `key order and header case do not change the fingerprint`() {
        val one =
            Rules.DEFAULT.of(
                json("""{"a":1,"b":[1,2]}"""),
                headersOf("Content-Type", "application/json"),
            )
        val other =
            Rules.DEFAULT.of(
                json("""{"b":[1,2],"a":1}"""),
                headersOf("content-type", "application/json"),
            )
        assertEquals(one, other, "canonical JSON sorts keys, and a header name ignores case")
    }

    @Test
    fun `array order does change the fingerprint`() {
        val one = Rules.DEFAULT.of(json("""{"messages":[{"t":"a"},{"t":"b"}]}"""), Headers.Empty)
        val other = Rules.DEFAULT.of(json("""{"messages":[{"t":"b"},{"t":"a"}]}"""), Headers.Empty)
        assertNotEquals(one, other, "a conversation is its order")
    }

    @Test
    fun `a body that is not JSON is matched by its bytes`() {
        val rules = Rules.DEFAULT
        val one = rules.fingerprint("POST", "/v1/x", Headers.Empty, null, "not json".toByteArray())
        val same = rules.fingerprint("POST", "/v1/x", Headers.Empty, null, "not json".toByteArray())
        val other =
            rules.fingerprint("POST", "/v1/x", Headers.Empty, null, "not json!".toByteArray())
        assertEquals(one, same)
        assertNotEquals(one, other)
    }

    @Test
    fun `the default rule file round-trips`() {
        assertEquals(Rules.DEFAULT, Rules.parse(Rules.defaultJson()))
    }

    @Test
    fun `a rule file that is not an object is refused`() {
        val message = rejection { Rules.parse("[]") }
        assertTrue(RULES_FILE in message, message)
    }

    @Test
    fun `a pointer without a leading slash names itself in the error`() {
        val message = rejection { Rules.parse("""{"ignorePointers":["metadata"]}""") }
        assertTrue("ignorePointers[0]" in message, message)
        assertTrue("metadata" in message, message)
    }

    @Test
    fun `a replacement that is not a regular expression names itself in the error`() {
        val message = rejection {
            Rules.parse("""{"replace":[{"pointer":"/a","pattern":"(","replacement":""}]}""")
        }
        assertTrue("replace[0].pattern" in message, message)
    }

    @Test
    fun `a replacement missing its replacement names itself in the error`() {
        val message = rejection { Rules.parse("""{"replace":[{"pointer":"/a","pattern":"x"}]}""") }
        assertTrue("replace[0].replacement" in message, message)
    }

    @Test
    fun `a keepHeaders entry that is not a string names itself in the error`() {
        val message = rejection { Rules.parse("""{"keepHeaders":[7]}""") }
        assertTrue("keepHeaders[0]" in message, message)
    }

    @Test
    fun `a typoed rule name is refused rather than quietly matching nothing`() {
        val message = rejection { Rules.parse("""{"replacements":[]}""") }
        assertTrue("replacements" in message, message)
    }

    @Test
    fun `a rule file that is not JSON reports where the parser stopped`() {
        val message = rejection { Rules.parse("""{"keepHeaders":[],}""") }
        assertTrue(RULES_FILE in message, message)
        assertTrue("not a JSON object" !in message, "the parser's own message should survive")
    }

    @Test
    fun `a replacement whose group the pattern does not have is refused`() {
        val bad = """{"replace":[{"pointer":"/a","pattern":"x","replacement":"US${'$'}5"}]}"""
        val message = rejection { Rules.parse(bad) }
        assertTrue("replace[0].replacement" in message, message)
    }

    @Test
    fun `a replacement ending in a backslash is refused`() {
        val bad = """{"replace":[{"pointer":"/a","pattern":"x","replacement":"\\"}]}"""
        val message = rejection { Rules.parse(bad) }
        assertTrue("replace[0].replacement" in message, message)
    }

    @Test
    fun `an environment block that ends the text is still removed`() {
        val ending =
            "# Environment\\nYou have been invoked in the following " +
                "environment: \\n - Platform: "
        val one = json("""{"messages":[{"content":[{"text":"${ending}win32"}]}]}""")
        val other = json("""{"messages":[{"content":[{"text":"${ending}linux"}]}]}""")
        assertEquals(
            Rules.DEFAULT.of(one, Headers.Empty),
            Rules.DEFAULT.of(other, Headers.Empty),
            "no trailing blank line, and the machine still must not reach the hash",
        )
    }

    @Test
    fun `an empty rule set is what exact mode is written as`() {
        assertEquals(Rules.EXACT, Rules.parse("{}"))
    }

    /** The message of whatever [block] throws; failing to throw at all is itself the failure. */
    private fun rejection(block: () -> Unit): String =
        try {
            block()
            throw AssertionError("expected a rejection")
        } catch (e: IllegalStateException) {
            e.message.orEmpty()
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }

    private companion object {
        /** Small, fixed, and touched by none of the default replace rules. */
        const val GOLDEN =
            """{"model":"claude-sonnet-4-5","max_tokens":16,"stream":true,""" +
                """"messages":[{"role":"user","content":"say peashoot"}]}"""
    }
}
