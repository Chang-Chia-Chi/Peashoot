package dev.peashoot.core

import io.ktor.http.Headers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prefix relation ADR 0002 rests on, away from any server: which re-issue of a Messages request
 * counts as the same request sent again after its stream was cut, and which does not. The rules are
 * the defaults, so what is compared is what the fingerprint would have been compared over.
 */
class ContinuableTest {
    private fun continuableOf(body: String): Continuable? {
        val bytes = body.toByteArray()
        val normalized =
            Rules.DEFAULT.normalized(
                "POST",
                "/v1/messages",
                Headers.Empty,
                jsonObjectOrNull(body),
                bytes,
            )
        return Messages.continuable(normalized)
    }

    /** Whether [reissue] continues [original], both spelled as whole request bodies. */
    private fun continues(original: String, reissue: String): Boolean {
        val theirs = assertNotNull(continuableOf(reissue), reissue)
        return assertNotNull(continuableOf(original), original).continuedBy(theirs)
    }

    private fun request(blocks: String, model: String = "claude-sonnet-5"): String =
        """{"model":"$model","stream":true,"system":[{"type":"text","text":"count"}],""" +
            """"messages":[{"role":"user","content":[$blocks]},""" +
            """{"role":"system","content":"a reminder after the user's turn"}]}"""

    @Test
    fun `an appended text block, with a newline on the last one, continues the original`() {
        assertTrue(continues(request(ORIGINAL), request("$PADDED,$APPENDED")))
    }

    /** The allowance is trailing whitespace on the last block alone, and nothing else. */
    @Test
    fun `whitespace may only be added, and only at the end of the last block`() {
        assertTrue(continues(request(ORIGINAL), request("$ORIGINAL,$APPENDED")), "none at all")
        assertFalse(continues(request(ORIGINAL), request("$REWORDED,$APPENDED")), "reworded")
        assertFalse(continues(request(PADDED), request("$ORIGINAL,$APPENDED")), "taken away")
        assertFalse(
            continues(request("$ORIGINAL,$SECOND"), request("$PADDED,$SECOND,$APPENDED")),
            "an earlier block does not get the allowance",
        )
    }

    @Test
    fun `everything appended must be a text block`() {
        assertFalse(continues(request(ORIGINAL), request("$PADDED,$IMAGE")))
        assertFalse(continues(request(ORIGINAL), request("$PADDED,$APPENDED,$IMAGE")))
    }

    @Test
    fun `the original's blocks must be a prefix, and the stem must be equal`() {
        assertFalse(continues(request("$ORIGINAL,$SECOND"), request(PADDED)), "shorter")
        assertFalse(continues(request(ORIGINAL), request("$SECOND,$APPENDED")), "a changed first")
        assertFalse(
            continues(request(ORIGINAL), request("$PADDED,$APPENDED", model = "claude-opus-5")),
            "a changed model is a changed stem",
        )
    }

    /**
     * A request continues itself. Resume prefers an equal fingerprint anyway, and both answers are
     * the same buffered answer, so this is a superset of the exact match and not a second rule.
     */
    @Test
    fun `an identical request continues the original`() {
        assertTrue(continues(request(ORIGINAL), request(ORIGINAL)))
    }

    /**
     * It is the last *user* message that may grow, not the last message: the spike's capture had a
     * `system`-role message after it, and reading the array's last entry would have found that one.
     */
    @Test
    fun `the last user message is the one that may grow`() {
        val original =
            """{"model":"m","messages":[{"role":"user","content":[$ORIGINAL]},""" +
                """{"role":"system","content":"after"}]}"""
        val reissue =
            """{"model":"m","messages":[{"role":"user","content":[$PADDED,$APPENDED]},""" +
                """{"role":"system","content":"after"}]}"""
        assertTrue(
            assertNotNull(continuableOf(original))
                .continuedBy(assertNotNull(continuableOf(reissue)))
        )
    }

    /** A request with no content array to grow has no continuation story: exact match only. */
    @Test
    fun `a request the relation cannot read has none`() {
        assertNull(
            continuableOf("""{"model":"m","messages":[{"role":"user","content":"a string"}]}""")
        )
        assertNull(
            continuableOf("""{"model":"m","messages":[{"role":"assistant","content":[]}]}""")
        )
        assertNull(continuableOf("""{"model":"m","messages":[]}"""))
        assertNull(continuableOf("""{"model":"m"}"""))
        assertNull(
            continuableOf("""{"model":"m","messages":[{"role":"user","content":["bare"]}]}"""),
            "a content holding anything but objects is not a block list",
        )
    }

    /** Only Messages was measured, so every other surface resumes on an equal fingerprint alone. */
    @Test
    fun `no other surface offers a continuation`() {
        val normalized =
            Rules.DEFAULT.normalized(
                "POST",
                "/v1/chat/completions",
                Headers.Empty,
                jsonObjectOrNull(CHAT),
                CHAT.toByteArray(),
            )
        assertNull(ChatCompletions.continuable(normalized))
        assertNull(Responses.continuable(normalized))
    }

    private companion object {
        const val ORIGINAL = """{"type":"text","text":"Count from 1 to 300."}"""
        const val PADDED = """{"type":"text","text":"Count from 1 to 300.\n"}"""
        const val REWORDED = """{"type":"text","text":"Count from 1 to 301."}"""
        const val SECOND = """{"type":"text","text":"a second block"}"""
        const val APPENDED = """{"type":"text","text":"Carry on from where you stopped."}"""
        const val IMAGE =
            """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVB"}}"""
        const val CHAT =
            """{"model":"gpt-4o-mini","messages":[{"role":"user","content":"count"}]}"""
    }
}
